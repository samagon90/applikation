package ai.arena.webapp.vpn

import java.math.BigInteger

/**
 * X25519 (RFC 7748) — согласование ключей на Curve25519.
 * Реализована через BigInteger для простоты и надёжности:
 * используется один раз на handshake, скорость не критична.
 */
object X25519 {

    private val P = BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
    private val A24 = BigInteger("121665")

    fun generateKeyPair(): KeyPair {
        val privateKey = ByteArray(32)
        java.security.SecureRandom().nextBytes(privateKey)
        val publicKey = publicKey(privateKey)
        return KeyPair(privateKey, publicKey)
    }

    /** Публичный ключ из приватного (умножение базовой точки). */
    fun publicKey(privateKey: ByteArray): ByteArray {
        require(privateKey.size == 32)
        val k = privateKey.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()
        return scalarMult(k, ByteArray(32).also { it[0] = 9 })
    }

    /**
     * X25519(scalar, u-coordinate) — RFC 7748 ladder.
     * [u] — 32-байтная u-координата точки-основания.
     */
    fun scalarMult(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32)
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = (k[31].toInt() and 127).toByte()
        k[31] = (k[31].toInt() or 64).toByte()

        var x1 = BigInteger(1, u.reversedArray()).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = (k[t / 8].toInt() shr (t % 8)) and 1
            swap = swap xor kt
            // cswap(swap, x2, x3); cswap(swap, z2, z3)
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            // ladder step
            val a = (x2 + z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = (x2 - z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P)
            val d = (x3 - z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val t0 = (da + cb).mod(P)
            val x3n = t0.multiply(t0).mod(P)
            val t1 = (da - cb).mod(P)
            val z3n = x1.multiply(t1.multiply(t1).mod(P)).mod(P)
            val x2n = aa.multiply(bb).mod(P)
            val z2n = e.multiply(aa + A24.multiply(e).mod(P)).mod(P)
            x2 = x2n; z2 = z2n; x3 = x3n; z3 = z3n
        }
        if (swap == 1) {
            val tx = x2; x2 = x3; x3 = tx
            val tz = z2; z2 = z3; z3 = tz
        }
        // result = x2 * z2^(p-2) mod p
        val inv = z2.modPow(P.subtract(BigInteger.TWO), P)
        val out = x2.multiply(inv).mod(P)
        val bytes = out.toByteArray()
        val result = ByteArray(32)
        // big-endian -> little-endian 32 bytes
        var idx = bytes.size - 1
        var i = 0
        while (i < 32) {
            result[i] = if (idx >= 0) bytes[idx] else 0
            idx--
            i++
        }
        return result
    }

    data class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
}
