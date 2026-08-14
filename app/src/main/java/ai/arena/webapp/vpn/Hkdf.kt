package ai.arena.webapp.vpn

/**
 * HKDF (RFC 5869) на HMAC-BLAKE2s — как в WireGuard.
 * KDF1(ck, data) = HKDF(salt=ck, ikm=data, info="")
 * KDF2(ck, data) = HKDF(salt=ck, ikm="", info=data)
 */
object Hkdf {

    /** HKDF-Extract + Expand, [outLen] до 32 байт (BLAKE2s). */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, outLen: Int): ByteArray {
        val prk = Blake2s.hmac(salt, ikm)
        var t = ByteArray(0)
        val out = ByteArray(outLen)
        var counter = 1
        var pos = 0
        while (pos < outLen) {
            t = Blake2s.hmac(prk, t + info + counter.toByte())
            val n = minOf(t.size, outLen - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    /** WireGuard KDF1: HKDF(ck, data, ""). */
    fun kdf1(ck: ByteArray, data: ByteArray): ByteArray = hkdf(ck, data, ByteArray(0), 32)

    /** WireGuard KDF2: HKDF(ck, "", data). */
    fun kdf2(ck: ByteArray, data: ByteArray): ByteArray = hkdf(ck, ByteArray(0), data, 32)

    /** WireGuard KDF3: HKDF(ck, "", data), 64 байта (для transport-ключей). */
    fun kdf3(ck: ByteArray, data: ByteArray): ByteArray = hkdf(ck, ByteArray(0), data, 64)
}
