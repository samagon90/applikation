package ai.arena.webapp.vpn

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * WireGuard-сессия: Noise IK-хендшейк (инициатор) и транспортное
 * шифрование пакетов. Без PSK, как в Cloudflare WARP.
 *
 * Реализация по whitepaper WireGuard:
 *   Construction = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
 *   Identifier   = "WireGuard v1 zx2c4 Jason@zx2c4.com"
 *   Lmac1        = "mac1----"
 */
class WireGuardSession(
    private val staticPrivateKey: ByteArray,
    private val responderStaticPublicKey: ByteArray
) {

    companion object {
        private const val CONSTRUCTION = "Noise_IKpsk2_25519_ChaChaPoly_BLAKE2s"
        private const val IDENTIFIER = "WireGuard v1 zx2c4 Jason@zx2c4.com"
        private val LMAC1 = "mac1----".toByteArray(Charsets.US_ASCII)

        const val MESSAGE_INIT = 1
        const val MESSAGE_RESPONSE = 2
        const val MESSAGE_COOKIE_REPLY = 3
        const val MESSAGE_TRANSPORT_DATA = 4

        /** Начальный chaining key: HASH(CONSTRUCTION). */
        fun initialCk(): ByteArray = Blake2s.hash(CONSTRUCTION.toByteArray(Charsets.US_ASCII))

        /** Начальный hash: HASH(ck || IDENTIFIER) || HASH(|| S_pub_r). */
        fun initialHash(responderStaticPublicKey: ByteArray): ByteArray {
            val ck = initialCk()
            var h = Blake2s.hash(ck + IDENTIFIER.toByteArray(Charsets.US_ASCII))
            h = Blake2s.hash(h + responderStaticPublicKey)
            return h
        }

        fun mac1HashKey(responderStaticPublicKey: ByteArray): ByteArray =
            Blake2s.hash(LMAC1 + responderStaticPublicKey)

        fun mac1(key: ByteArray, messagePrefix: ByteArray): ByteArray =
            Blake2s.hash(key + messagePrefix)

        private fun le32(v: Int): ByteArray {
            val b = ByteArray(4)
            for (i in 0 until 4) b[i] = ((v ushr (8 * i)) and 0xff).toByte()
            return b
        }

        private fun le64(v: Long): ByteArray {
            val b = ByteArray(8)
            for (i in 0 until 8) b[i] = ((v ushr (8 * i)) and 0xff).toByte()
            return b
        }

        private fun readLe32(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xff) or
                ((b[off + 1].toInt() and 0xff) shl 8) or
                ((b[off + 2].toInt() and 0xff) shl 16) or
                ((b[off + 3].toInt() and 0xff) shl 24)

        private fun readLe64(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b[off + i].toLong() and 0xff) shl (8 * i))
            return v
        }
    }

    private val rng = SecureRandom()
    private val staticPublicKey = X25519.publicKey(staticPrivateKey)

    private val senderIndex = rng.nextInt()
    private var responderIndex = 0

    @Volatile
    private var sendKey: ByteArray = ByteArray(32)
    @Volatile
    private var recvKey: ByteArray = ByteArray(32)

    @Volatile
    private var keyReady = false

    private val sendCounter = AtomicLong(0)
    private val mac1Key = mac1HashKey(responderStaticPublicKey)

    /** Собрать init-сообщение (внутреннее состояние — chaining state). */
    class HandshakeState(
        var ck: ByteArray,
        var h: ByteArray,
        val ephemeralPrivate: ByteArray,
        val ephemeralPublic: ByteArray
    )

    fun buildInitMessage(): Pair<ByteArray, HandshakeState> {
        val ck = initialCk()
        val h = initialHash(responderStaticPublicKey)

        val ephKeyPair = X25519.generateKeyPair()
        val ephPriv = ephKeyPair.privateKey
        val ephPub = ephKeyPair.publicKey

        var ck1 = Hkdf.kdf1(ck, ephPub)
        var h1 = Blake2s.hash(h + ephPub)

        // Зашифровать статический публичный ключ
        val dh = X25519.scalarMult(ephPriv, responderStaticPublicKey)
        val key = Hkdf.kdf2(ck1, dh)
        val encryptedStatic = ChaCha20Poly1305.aeadSeal(key, 0L, staticPublicKey, h1)

        ck1 = Hkdf.kdf1(ck1, encryptedStatic)
        h1 = Blake2s.hash(h1 + encryptedStatic)

        val msg = ByteArray(120)
        msg[0] = MESSAGE_INIT.toByte()
        // reserved 3 bytes = 0
        val sidx = le32(senderIndex)
        System.arraycopy(sidx, 0, msg, 4, 4)
        System.arraycopy(ephPub, 0, msg, 8, 32)
        System.arraycopy(encryptedStatic, 0, msg, 40, 48)
        // mac1 over everything before mac1/mac2
        val m1 = mac1(mac1Key, msg.copyOfRange(0, 104))
        System.arraycopy(m1, 0, msg, 104, 16)
        // mac2 = 16 нулей

        return Pair(msg, HandshakeState(ck1, h1, ephPriv, ephPub))
    }

    /** Обработать response-сообщение, извлечь transport-ключи. */
    fun consumeResponse(message: ByteArray, state: HandshakeState): Boolean {
        if (message.size < 124 || message[0].toInt() != MESSAGE_RESPONSE) return false

        responderIndex = readLe32(message, 4)
        val receiver = readLe32(message, 8)
        if (receiver != senderIndex) return false

        val ephR = message.copyOfRange(12, 44)
        val encStaticR = message.copyOfRange(44, 92)

        // Проверяем mac1 (часть аутентификации хендшейка)
        val mac1Key = mac1HashKey(responderStaticPublicKey)
        val mac1Bytes = message.copyOfRange(92, 108)
        val mac1Expected = mac1(mac1Key, message.copyOfRange(0, 92)).copyOfRange(0, 16)
        if (!java.security.MessageDigest.isEqual(mac1Bytes, mac1Expected)) return false

        var ck = Hkdf.kdf1(state.ck, ephR)
        var h = Blake2s.hash(state.h + ephR)

        // Расшифровать статический ключ респондера
        val dh = X25519.scalarMult(state.ephemeralPrivate, ephR)
        val key = Hkdf.kdf2(ck, dh)
        val staticR = ChaCha20Poly1305.aeadOpen(key, 0L, encStaticR, h)
            ?: return false
        // Проверяем, что респондер отдал правильный ключ
        if (!staticR.contentEquals(responderStaticPublicKey)) return false

        ck = Hkdf.kdf1(ck, encStaticR)
        h = Blake2s.hash(h + encStaticR)

        // Ключи транспорта (инициатор): DH(s_i, e_r), DH(e_i, e_r), DH(e_i, s_r)
        val d1 = X25519.scalarMult(staticPrivateKey, ephR)
        ck = Hkdf.kdf1(ck, d1)
        h = Blake2s.hash(h + d1)
        val d2 = X25519.scalarMult(state.ephemeralPrivate, ephR)
        ck = Hkdf.kdf1(ck, d2)
        h = Blake2s.hash(h + d2)
        val d3 = X25519.scalarMult(state.ephemeralPrivate, responderStaticPublicKey)
        ck = Hkdf.kdf1(ck, d3)
        h = Blake2s.hash(h + d3)

        var temp = Hkdf.kdf2(ck, ByteArray(0))
        val tSend = temp.copyOfRange(0, 32)
        ck = Hkdf.kdf1(ck, temp)
        temp = Hkdf.kdf2(ck, ByteArray(0))
        val tRecv = temp.copyOfRange(0, 32)

        sendKey = tSend
        recvKey = tRecv
        sendCounter.set(0)
        keyReady = true
        return true
    }

    val isKeyReady: Boolean get() = keyReady

    /** Зашифровать IP-пакет в WireGuard data packet. */
    fun encryptPacket(payload: ByteArray): ByteArray? {
        if (!keyReady) return null
        val counter = sendCounter.getAndIncrement()
        if (counter < 0 || counter > (1L shl 60)) return null // нужен rekey

        val header = ByteArray(16)
        header[0] = MESSAGE_TRANSPORT_DATA.toByte()
        val ridx = le32(responderIndex)
        System.arraycopy(ridx, 0, header, 4, 4)
        val ctr = le64(counter)
        System.arraycopy(ctr, 0, header, 8, 8)

        val sealed = ChaCha20Poly1305.aeadSeal(sendKey, counter, payload, header)
        return header + sealed
    }

    /** Расшифровать входящий WireGuard data packet (payload). */
    fun decryptPacket(packet: ByteArray): ByteArray? {
        if (!keyReady || packet.size < 32) return null
        val type = packet[0].toInt()
        if (type != MESSAGE_TRANSPORT_DATA) return null
        val counter = readLe64(packet, 8)
        val header = packet.copyOfRange(0, 16)
        val body = packet.copyOfRange(16, packet.size)
        return ChaCha20Poly1305.aeadOpen(recvKey, counter, body, header)
    }

    /** Keepalive-пакет (пустой payload). */
    fun buildKeepalive(): ByteArray? {
        if (!keyReady) return null
        val counter = sendCounter.getAndIncrement()
        val header = ByteArray(16)
        header[0] = MESSAGE_TRANSPORT_DATA.toByte()
        val ridx = le32(responderIndex)
        System.arraycopy(ridx, 0, header, 4, 4)
        val ctr = le64(counter)
        System.arraycopy(ctr, 0, header, 8, 8)
        val sealed = ChaCha20Poly1305.aeadSeal(sendKey, counter, ByteArray(0), header)
        return header + sealed
    }
}
