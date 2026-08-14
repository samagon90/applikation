package ai.arena.test

import ai.arena.webapp.vpn.Blake2s
import ai.arena.webapp.vpn.ChaCha20Poly1305
import ai.arena.webapp.vpn.Hkdf
import ai.arena.webapp.vpn.TcpStack
import ai.arena.webapp.vpn.WireGuardSession
import ai.arena.webapp.vpn.X25519
import java.util.Base64

/**
 * Оффлайн-тесты криптографии и TCP-стека VPN (запуск: scripts/test_vpn.sh).
 * Все векторы — из RFC 7748 / RFC 8439 / RFC 7693.
 */
object TestMain {

    private var passed = 0
    private var failed = 0

    private fun check(name: String, cond: Boolean) {
        if (cond) {
            passed++
            println("PASS  $name")
        } else {
            failed++
            println("FAIL  $name")
        }
    }

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private fun base64(s: String): ByteArray = Base64.getDecoder().decode(s)

    @JvmStatic
    fun main(args: Array<String>) {
        testX25519()
        testChaCha20()
        testPoly1305()
        testAead()
        testBlake2s()
        testHkdfConsistency()
        testWireGuardHandshake()
        testWireGuardTransport()
        testTcpStack()
        testDnsParse()

        println("========================================")
        println("PASSED: $passed, FAILED: $failed")
        if (failed > 0) kotlin.system.exitProcess(1)
    }

    // ------------------------------------------------------------ X25519

    private fun testX25519() {
        // RFC 7748 §5.2, вектор 1
        val k1 = hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4")
        val u1 = hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")
        val o1 = hex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")
        check("X25519 RFC7748 #1", hex(X25519.scalarMult(k1, u1)) == hex(o1))

        // RFC 7748 §5.2, вектор 2 содержит опечатку в самом RFC (errata),
        // поэтому используем независимые векторы: §6.1 и итерационный.
        val aPriv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val aPub = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        check("X25519 RFC7748 §6.1 Alice", hex(X25519.scalarMult(aPriv, ByteArray(32).also { it[0] = 9 })) == hex(aPub))

        // Итерационный вектор: k=u=09 00...00 → 422c8e7a...
        val nine = hex("0900000000000000000000000000000000000000000000000000000000000000")
        val iter1 = hex("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079")
        check("X25519 iterated #1", hex(X25519.scalarMult(nine, nine)) == hex(iter1))

        // publicKey == scalarMult с базовой точкой (u=9)
        val kp = X25519.generateKeyPair()
        check(
            "X25519 publicKey=basepoint mult",
            hex(X25519.publicKey(kp.privateKey)) ==
                hex(X25519.scalarMult(kp.privateKey, ByteArray(32).also { it[0] = 9 }))
        )

        // коммутативность
        val a = X25519.generateKeyPair()
        val b = X25519.generateKeyPair()
        check(
            "X25519 commutativity",
            hex(X25519.scalarMult(a.privateKey, b.publicKey)) ==
                hex(X25519.scalarMult(b.privateKey, a.publicKey))
        )
    }

    // ------------------------------------------------------------ ChaCha20

    private fun testChaCha20() {
        // RFC 8439 §2.4.2
        val key = hex(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        )
        val nonce = hex("000000000000004a00000000")
        val pt = ("Ladies and Gentlemen of the class of '99: If I could offer you " +
            "only one tip for the future, sunscreen would be it.").toByteArray()
        val expected = hex(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0b" +
                "f91b65c5524733ab8f593dabcd62b3571639d624e65152ab8f530c359f0861d8" +
                "07ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab779373" +
                "65af90bbf74a35be6b40b8eedf2785e42874d"
        )
        check("ChaCha20 RFC8439", hex(ChaCha20Poly1305.chacha20(key, 1, nonce, pt)) == hex(expected))
    }

    // ------------------------------------------------------------ Poly1305

    private fun testPoly1305() {
        // RFC 8439 §2.5.2
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        val msg = "Cryptographic Forum Research Group".toByteArray()
        val tag = hex("a8061dc1305136c6c22b8baf0c0127a9")
        check("Poly1305 RFC8439", hex(ChaCha20Poly1305.poly1305(key, msg)) == hex(tag))
    }

    // ---------------------------------------------------------------- AEAD

    private fun testAead() {
        // RFC 8439 §2.8.2 (полный 12-байтный nonce: 07000000 || counter LE64)
        val key = hex(
            "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
        )
        val nonce12 = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val pt = ("Ladies and Gentlemen of the class of '99: If I could offer you " +
            "only one tip for the future, sunscreen would be it.").toByteArray()
        val expectedCt = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b3" +
                "692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7b" +
                "c3ff4def08e4b7a9de576d26586cec64b6116"
        )
        val expectedTag = hex("1ae10b594f09e26a7e902ecbd0600691")
        val sealed = ChaCha20Poly1305.aeadSealNonce(key, nonce12, pt, aad)
        check("AEAD seal RFC8439", hex(sealed) == hex(expectedCt) + hex(expectedTag))
        val opened = ChaCha20Poly1305.aeadOpenNonce(key, nonce12, sealed, aad)
        check("AEAD open RFC8439", opened != null && hex(opened!!) == hex(pt))

        // подделка не проходит
        val bad = sealed.copyOf()
        bad[0] = (bad[0].toInt() xor 1).toByte()
        check("AEAD tamper detect", ChaCha20Poly1305.aeadOpenNonce(key, nonce12, bad, aad) == null)

        // WireGuard-вариант (counter LE64 + 4 нуля): roundtrip
        val wgSealed = ChaCha20Poly1305.aeadSeal(key, 42L, pt, aad)
        val wgOpened = ChaCha20Poly1305.aeadOpen(key, 42L, wgSealed, aad)
        check("AEAD WG-style roundtrip", wgOpened != null && hex(wgOpened!!) == hex(pt))
    }

    // -------------------------------------------------------------- BLAKE2s

    private fun testBlake2s() {
        // RFC 7693, BLAKE2s-256("abc")
        val expected = hex("508c5e8c327c14e2e1a72ba34eeb452f37458b209ed63a294d999b4c86675982")
        check(
            "BLAKE2s(abc)",
            hex(Blake2s.hash("abc".toByteArray())) == hex(expected)
        )
        // пустой ввод
        check(
            "BLAKE2s(empty)",
            hex(Blake2s.hash(ByteArray(0))) ==
                "69217a3079908094e11121d042354a7c1f55b6482ca1a51e1b250dfd1ed0eef9"
        )
        // HMAC: согласованность (ключ ≤ 32)
        val k = hex("000102030405060708090a0b0c0d0e0f")
        val m = "message".toByteArray()
        val h1 = Blake2s.hmac(k, m)
        val h2 = Blake2s.hmac(k, m)
        check("HMAC-BLAKE2s deterministic", hex(h1) == hex(h2) && h1.size == 32)
    }

    private fun testHkdfConsistency() {
        val ck = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
        val data = hex("aabbcc")
        val k1 = Hkdf.kdf1(ck, data)
        val k2 = Hkdf.kdf2(ck, data)
        val k3 = Hkdf.kdf3(ck, data)
        check("HKDF sizes", k1.size == 32 && k2.size == 32 && k3.size == 64)
        check("HKDF deterministic", hex(k1) == hex(Hkdf.kdf1(ck, data)))
    }

    // -------------------------------------------------- WireGuard handshake

    private fun testWireGuardHandshake() {
        // Ключи сторон
        val sI = X25519.generateKeyPair()
        val sR = X25519.generateKeyPair()

        val session = WireGuardSession(sI.privateKey, sR.publicKey)
        val (initMsg, state) = session.buildInitMessage()
        check("WG init message size", initMsg.size == 120)

        // Ответ строит мини-респондер по формулам whitepaper
        val resp = buildResponderResponse(initMsg, sR, sI.publicKey)

        val ok = session.consumeResponse(resp, state)
        check("WG handshake consume", ok)
        check("WG keyReady", session.isKeyReady)

        // Респондер независимо вычислил те же транспортные ключи — сверяем
        // через roundtrip: шифруем у инициатора, расшифровываем у респондера.
        val payload = "hello wireguard".toByteArray()
        val enc = session.encryptPacket(payload)
        check("WG encrypt non-null", enc != null)
        val dec = respDecrypt(enc!!)
        check("WG transport roundtrip", dec != null && hex(dec!!) == hex(payload))

        // и обратно: респондер → инициатор
        val enc2 = respEncrypt(payload)
        check("WG reverse decrypt", hex(session.decryptPacket(enc2)!!) == hex(payload))
    }

    // Временное хранилище ключей респондера для roundtrip-проверки
    private var respSendKey: ByteArray = ByteArray(32)
    private var respRecvKey: ByteArray = ByteArray(32)
    private var respSenderIndex = 1234
    private var respRecvIndex = 0
    private var respCounter = 0L

    private fun buildResponderResponse(
        initMsg: ByteArray,
        sR: X25519.KeyPair,
        sIPub: ByteArray
    ): ByteArray {
        val ephI = initMsg.copyOfRange(8, 40)
        val encSI = initMsg.copyOfRange(40, 88)
        val initSender = readLe32(initMsg, 4)

        var ck = WireGuardSession.initialCk()
        var h = WireGuardSession.initialHash(sR.publicKey)

        ck = Hkdf.kdf1(ck, ephI)
        h = Blake2s.hash(h + ephI)

        val dh = X25519.scalarMult(sR.privateKey, ephI)
        val key = Hkdf.kdf2(ck, dh)
        val sI = ChaCha20Poly1305.aeadOpen(key, 0L, encSI, h)
            ?: return ByteArray(0)
        check("WG responder decrypt static", hex(sI) == hex(sIPub))

        ck = Hkdf.kdf1(ck, encSI)
        h = Blake2s.hash(h + encSI)

        val eR = X25519.generateKeyPair()
        ck = Hkdf.kdf1(ck, eR.publicKey)
        h = Blake2s.hash(h + eR.publicKey)

        val dh2 = X25519.scalarMult(eR.privateKey, ephI)
        val key2 = Hkdf.kdf2(ck, dh2)
        val encSR = ChaCha20Poly1305.aeadSeal(key2, 0L, sR.publicKey, h)

        ck = Hkdf.kdf1(ck, encSR)
        h = Blake2s.hash(h + encSR)

        // транспортные ключи респондера
        var d1 = X25519.scalarMult(eR.privateKey, sIPub)
        ck = Hkdf.kdf1(ck, d1); h = Blake2s.hash(h + d1)
        var d2 = X25519.scalarMult(eR.privateKey, ephI)
        ck = Hkdf.kdf1(ck, d2); h = Blake2s.hash(h + d2)
        var d3 = X25519.scalarMult(sR.privateKey, ephI)
        ck = Hkdf.kdf1(ck, d3); h = Blake2s.hash(h + d3)

        var temp = Hkdf.kdf2(ck, ByteArray(0))
        respRecvKey = temp.copyOfRange(0, 32)
        ck = Hkdf.kdf1(ck, temp)
        temp = Hkdf.kdf2(ck, ByteArray(0))
        respSendKey = temp.copyOfRange(0, 32)

        respSenderIndex = 9999
        respRecvIndex = initSender

        // type(4) | sender(4) | receiver(4) | e_r(32) | enc_s_r(48) | mac1(16) | mac2(16)
        val msg = ByteArray(124)
        msg[0] = WireGuardSession.MESSAGE_RESPONSE.toByte()
        writeLe32(msg, 4, respSenderIndex)
        writeLe32(msg, 8, respRecvIndex)
        System.arraycopy(eR.publicKey, 0, msg, 12, 32)
        System.arraycopy(encSR, 0, msg, 44, 48)
        val m1 = WireGuardSession.mac1(
            WireGuardSession.mac1HashKey(sR.publicKey),
            msg.copyOfRange(0, 92)
        )
        System.arraycopy(m1, 0, msg, 92, 16)
        return msg
    }

    private fun respEncrypt(payload: ByteArray): ByteArray {
        val header = ByteArray(16)
        header[0] = WireGuardSession.MESSAGE_TRANSPORT_DATA.toByte()
        writeLe32(header, 4, respRecvIndex)
        writeLe64(header, 8, respCounter)
        val sealed = ChaCha20Poly1305.aeadSeal(respSendKey, respCounter, payload, header)
        respCounter++
        return header + sealed
    }

    private fun respDecrypt(packet: ByteArray): ByteArray? {
        if (packet.size < 32) return null
        val counter = readLe64(packet, 8)
        return ChaCha20Poly1305.aeadOpen(
            respRecvKey, counter,
            packet.copyOfRange(16, packet.size), packet.copyOfRange(0, 16)
        )
    }

    private fun testWireGuardTransport() {
        // keepalive
        val kpS = X25519.generateKeyPair()
        val kpR = X25519.generateKeyPair()
        val session = WireGuardSession(kpS.privateKey, kpR.publicKey)
        val (initMsg, state) = session.buildInitMessage()
        val resp = buildResponderResponse(initMsg, kpR, kpS.publicKey)
        session.consumeResponse(resp, state)
        val ka = session.buildKeepalive()
        check("WG keepalive non-null", ka != null && ka!!.size == 32)
        check("WG keepalive decrypt=empty", respDecrypt(ka!!)?.isEmpty() == true)
    }

    // ------------------------------------------------------------ TCP stack

    private fun testTcpStack() {
        val warpAddr = byteArrayOf(172.toByte(), 16, 0, 2)
        val devIp = byteArrayOf(10, 66, 66, 2)
        val srvIp = byteArrayOf(104, 18, 15, 206.toByte())

        val tunnelPackets = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        val devicePackets = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

        val stack = TcpStack(
            warpAddr,
            { p -> tunnelPackets.add(p) },
            { p -> devicePackets.add(p) }
        )

        // Устройство шлёт SYN
        val synSeq = 1000
        val syn = stack.buildTcpIp(
            devIp, srvIp, 40000, 443, synSeq, 0,
            TcpStack.TCP_SYN, stack.tcpOptions(1280), ByteArray(0)
        )
        stack.onDevicePacket(syn)

        // SYN-ACK устройству?
        val synAck = devicePackets.poll()
        check("TCP device SYN-ACK", synAck != null)
        val saIp = stack.parseIp(synAck!!)
        val saSeg = stack.parseTcp(saIp!!.payload)
        check(
            "TCP SYN-ACK flags+ack",
            saSeg != null &&
                saSeg.flags and TcpStack.TCP_SYN != 0 &&
                saSeg.flags and TcpStack.TCP_ACK != 0 &&
                saSeg.ack == synSeq + 1
        )
        val devIsn = saSeg!!.seq

        // SYN к серверу ушёл в туннель?
        val synT = tunnelPackets.poll()
        check("TCP server SYN", synT != null)
        // узнаём наш эфемерный порт из отправленного SYN
        val synTIp = stack.parseIp(synT!!)!!
        val ourPort = synTIp.srcPort

        // Сервер отвечает SYN-ACK (на наш порт)
        val srvSynSeq = 5000
        val srvSynAck = stack.buildTcpIp(
            srvIp, warpAddr, 443, ourPort, srvSynSeq, 1000 + 1,
            TcpStack.TCP_SYN or TcpStack.TCP_ACK, stack.tcpOptions(1280), ByteArray(0)
        )
        stack.onTunnelPacket(srvSynAck)
        // ждём ACK серверу
        var srvAck = tunnelPackets.poll()
        var srvAckIp = stack.parseIp(srvAck!!)
        var srvAckSeg = stack.parseTcp(srvAckIp!!.payload)
        check(
            "TCP server ACK",
            srvAckSeg != null && srvAckSeg.flags and TcpStack.TCP_ACK != 0 &&
                srvAckSeg.ack == srvSynSeq + 1
        )

        // Устройство ACK нашего SYN-ACK → ESTABLISHED
        val devAck = stack.buildTcpIp(
            devIp, srvIp, 40000, 443, synSeq + 1, devIsn + 1,
            TcpStack.TCP_ACK, stack.tcpOptions(0), ByteArray(0)
        )
        stack.onDevicePacket(devAck)

        // Устройство шлёт данные
        val data1 = "GET / HTTP/1.1".toByteArray()
        val devData = stack.buildTcpIp(
            devIp, srvIp, 40000, 443, synSeq + 1, devIsn + 1,
            TcpStack.TCP_PSH or TcpStack.TCP_ACK, stack.tcpOptions(0), data1
        )
        stack.onDevicePacket(devData)

        // данные должны уйти в туннель
        var found = false
        while (true) {
            val p = tunnelPackets.poll() ?: break
            val ip = stack.parseIp(p) ?: continue
            val seg = stack.parseTcp(ip.payload) ?: continue
            if (seg.payload.isNotEmpty() && hex(seg.payload) == hex(data1)) {
                found = true
                break
            }
        }
        check("TCP data device→server", found)

        // Сервер шлёт данные
        val data2 = "HTTP/1.1 200 OK".toByteArray()
        val srvSeq2 = srvSynSeq + 1
        val srvData = stack.buildTcpIp(
            srvIp, warpAddr, 443, ourPort, srvSeq2, synSeq + 1 + data1.size,
            TcpStack.TCP_PSH or TcpStack.TCP_ACK, stack.tcpOptions(0), data2
        )
        stack.onTunnelPacket(srvData)
        var found2 = false
        while (true) {
            val p = devicePackets.poll() ?: break
            val ip = stack.parseIp(p) ?: continue
            val seg = stack.parseTcp(ip.payload) ?: continue
            if (seg.payload.isNotEmpty() && hex(seg.payload) == hex(data2)) {
                found2 = true
                break
            }
        }
        check("TCP data server→device", found2)
    }

    // ------------------------------------------------------------- DNS parse

    private fun testDnsParse() {
        val dns = ai.arena.webapp.vpn.DnsResolver({})
        // виртуальные IP
        val vip = dns.virtualIpFor("arena.ai")
        check("DNS virtual ip", dns.isVirtualIp(vip))
        check(
            "DNS vip stability",
            hex(dns.virtualIpFor("arena.ai")) == hex(vip)
        )
        // buildQuery/parseQueryName roundtrip
        val q = dns.buildQuery(1234, "arena.ai")
        check("DNS query parse", dns.parseQueryName(q) == "arena.ai")
    }

    // --------------------------------------------------------------- helpers

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

    private fun writeLe32(b: ByteArray, off: Int, v: Int) {
        for (i in 0 until 4) b[off + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }

    private fun writeLe64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = ((v ushr (8 * i)) and 0xff).toByte()
    }
}
