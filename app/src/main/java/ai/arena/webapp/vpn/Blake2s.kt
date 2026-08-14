package ai.arena.webapp.vpn

/**
 * BLAKE2s-256 (RFC 7693), включая keyed-режим (для HMAC-BLAKE2s,
 * который WireGuard использует в HKDF и mac1).
 */
object Blake2s {

    private val IV = intArrayOf(
        0x6A09E667.toInt(), 0xBB67AE85.toInt(), 0x3C6EF372.toInt(), 0xA54FF53A.toInt(),
        0x510E527F.toInt(), 0x9B05688C.toInt(), 0x1F83D9AB.toInt(), 0x5BE0CD19.toInt()
    )

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
    )

    private fun rotl(v: Int, c: Int): Int = (v shl c) or (v ushr (32 - c))

    private fun rotr(v: Int, c: Int): Int = (v ushr c) or (v shl (32 - c))

    private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
        v[a] = v[a] + v[b] + x
        v[d] = rotr(v[d] xor v[a], 16)
        v[c] = v[c] + v[d]
        v[b] = rotr(v[b] xor v[c], 12)
        v[a] = v[a] + v[b] + y
        v[d] = rotr(v[d] xor v[a], 8)
        v[c] = v[c] + v[d]
        v[b] = rotr(v[b] xor v[c], 7)
    }

    private fun compress(
        h: IntArray,
        block: ByteArray,
        blockLen: Int,
        t: Long,
        last: Boolean
    ): IntArray {
        val v = IntArray(16)
        for (i in 0 until 8) v[i] = h[i]
        for (i in 0 until 8) v[i + 8] = IV[i]
        v[12] = v[12] xor (t and 0xffffffffL).toInt()
        v[13] = v[13] xor (t ushr 32).toInt()
        if (last) v[14] = v[14].inv()

        val m = IntArray(16)
        var p = 0
        for (i in 0 until blockLen) {
            m[p / 4] = m[p / 4] or ((block[i].toInt() and 0xff) shl ((p % 4) * 8))
            p++
        }

        for (r in 0 until 10) {
            val s = SIGMA[r]
            g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
            g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        val out = IntArray(8)
        for (i in 0 until 8) out[i] = h[i] xor v[i] xor v[i + 8]
        return out
    }

    /** BLAKE2s-256. [key] может быть пустым (unkeyed) или до 32 байт (keyed). */
    fun hash(data: ByteArray, key: ByteArray = ByteArray(0)): ByteArray {
        require(key.size <= 32)
        val h = IV.copyOf()
        // param block (LE): byte0=digest_length(0x20), byte1=key_length,
        // byte2=fanout(1), byte3=depth(1)
        h[0] = h[0] xor (0x01010020 or (key.size shl 8))
        val block = ByteArray(64)
        var t = 0L
        var pos = 0

        if (key.isNotEmpty()) {
            System.arraycopy(key, 0, block, 0, key.size)
            var hh = compress(h, block, 64, 64L, data.isEmpty())
            System.arraycopy(hh, 0, h, 0, 8)
            t = 64
        }

        while (pos < data.size) {
            if (data.size - pos > 64) {
                val chunk = data.copyOfRange(pos, pos + 64)
                t += 64
                val hh = compress(h, chunk, 64, t, false)
                System.arraycopy(hh, 0, h, 0, 8)
                pos += 64
            } else {
                val chunk = data.copyOfRange(pos, data.size)
                t += chunk.size
                val hh = compress(h, chunk, chunk.size, t, true)
                System.arraycopy(hh, 0, h, 0, 8)
                pos = data.size
            }
        }
        if (pos == 0 && key.isEmpty()) {
            val hh = compress(h, ByteArray(64), 0, 0L, true)
            System.arraycopy(hh, 0, h, 0, 8)
        }
        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] and 0xff).toByte()
            out[i * 4 + 1] = ((h[i] ushr 8) and 0xff).toByte()
            out[i * 4 + 2] = ((h[i] ushr 16) and 0xff).toByte()
            out[i * 4 + 3] = ((h[i] ushr 24) and 0xff).toByte()
        }
        return out
    }

    /** HMAC-BLAKE2s (WireGuard использует её в HKDF и mac1). */
    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        var k = key
        if (k.size > 64) k = hash(k)
        val pad = ByteArray(64)
        System.arraycopy(k, 0, pad, 0, k.size)
        val ipad = ByteArray(64)
        val opad = ByteArray(64)
        for (i in 0 until 64) {
            ipad[i] = (pad[i].toInt() xor 0x36).toByte()
            opad[i] = (pad[i].toInt() xor 0x5c).toByte()
        }
        val inner = hash(ipad + message)
        return hash(opad + inner)
    }
}
