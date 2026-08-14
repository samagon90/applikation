package ai.arena.webapp.vpn

/**
 * ChaCha20 (RFC 8439) + Poly1305 + AEAD (ChaCha20-Poly1305),
 * в конфигурации WireGuard: 32-байтный ключ, 96-битный nonce
 * (в WireGuard — 64-битный счётчик + 32 нуля).
 */
object ChaCha20Poly1305 {

    // ------------------------------------------------------------- ChaCha20

    private fun rotl(v: Int, c: Int): Int = (v shl c) or (v ushr (32 - c))

    fun quarterRound(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 16)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 12)
        s[a] += s[b]; s[d] = rotl(s[d] xor s[a], 8)
        s[c] += s[d]; s[b] = rotl(s[b] xor s[c], 7)
    }

    private fun block(key: ByteArray, counter: Int, nonce: ByteArray): IntArray {
        val s = IntArray(16)
        s[0] = 0x61707865
        s[1] = 0x3320646e
        s[2] = 0x79622d32
        s[3] = 0x6b206574
        for (i in 0 until 8) {
            s[4 + i] = leInt(key, i * 4)
        }
        s[12] = counter
        for (i in 0 until 3) {
            s[13 + i] = leInt(nonce, i * 4)
        }
        val x = s.copyOf()
        repeat(10) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 1, 5, 9, 13)
            quarterRound(x, 2, 6, 10, 14)
            quarterRound(x, 3, 7, 11, 15)
            quarterRound(x, 0, 5, 10, 15)
            quarterRound(x, 1, 6, 11, 12)
            quarterRound(x, 2, 7, 8, 13)
            quarterRound(x, 3, 4, 9, 14)
        }
        for (i in 0 until 16) x[i] += s[i]
        return x
    }

    /** Шифрует/расшифровывает (XOR с keystream). */
    fun chacha20(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = data.copyOf()
        var pos = 0
        var c = counter
        while (pos < data.size) {
            val ks = IntArray(16)
            val blk = block(key, c, nonce)
            for (i in 0 until 16) ks[i] = blk[i]
            var i = 0
            while (pos < data.size && i < 64) {
                out[pos] = (out[pos].toInt() xor ((ks[i / 4] ushr ((i % 4) * 8)) and 0xff)).toByte()
                pos++
                i++
            }
            c++
        }
        return out
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)

    private fun leLong(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((b[off + i].toLong() and 0xff) shl (8 * i))
        }
        return v
    }

    private fun leBytes32(v: Int): ByteArray {
        val b = ByteArray(4)
        for (i in 0 until 4) b[i] = ((v ushr (8 * i)) and 0xff).toByte()
        return b
    }

    private fun leBytes64(v: Long): ByteArray {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = ((v ushr (8 * i)) and 0xff).toByte()
        return b
    }

    // -------------------------------------------------------------- Poly1305

    private val P1305 = java.math.BigInteger.ONE.shiftLeft(130).subtract(java.math.BigInteger.valueOf(5))

    fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        require(key.size == 32)
        val r = key.copyOfRange(0, 16)
        r[3] = (r[3].toInt() and 15).toByte()
        r[7] = (r[7].toInt() and 15).toByte()
        r[11] = (r[11].toInt() and 15).toByte()
        r[15] = (r[15].toInt() and 15).toByte()
        r[4] = (r[4].toInt() and 252).toByte()
        r[8] = (r[8].toInt() and 252).toByte()
        r[12] = (r[12].toInt() and 252).toByte()

        val rInt = java.math.BigInteger(1, r.reversedArray())
        val sInt = java.math.BigInteger(1, key.copyOfRange(16, 32).reversedArray())

        var acc = java.math.BigInteger.ZERO
        var pos = 0
        while (pos < msg.size) {
            val chunkLen = minOf(16, msg.size - pos)
            val chunk = ByteArray(17)
            for (i in 0 until chunkLen) chunk[i] = msg[pos + i]
            chunk[chunkLen] = 1
            // chunk как little-endian число
            acc = acc.add(java.math.BigInteger(1, chunk.reversedArray())).mod(P1305)
            acc = acc.multiply(rInt).mod(P1305)
            pos += 16
        }
        val tagInt = acc.add(sInt).mod(java.math.BigInteger.ONE.shiftLeft(128))
        val t = tagInt.toByteArray()
        val tag = ByteArray(16)
        // little-endian
        var idx = t.size - 1
        var i = 0
        while (i < 16) {
            tag[i] = if (idx >= 0) t[idx] else 0
            idx--
            i++
        }
        return tag
    }

    // ----------------------------------------------------------------- AEAD

    /**
     * ChaCha20-Poly1305 AEAD (WireGuard-вариант): nonce = counter(LE64) || 0(4).
     */
    fun aeadSeal(
        key: ByteArray,
        counter: Long,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val nonce = ByteArray(12)
        val c = leBytes64(counter)
        System.arraycopy(c, 0, nonce, 0, 8)
        // nonce[8..12] = 0 (WireGuard-вариант)
        return aeadSealNonce(key, nonce, plaintext, aad)
    }

    /** AEAD с произвольным 12-байтным nonce (для тестов по RFC 8439). */
    fun aeadSealNonce(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val polyKey = chacha20(key, 0, nonce, ByteArray(64)).copyOfRange(0, 32)
        val ciphertext = chacha20(key, 1, nonce, plaintext)

        // tag over aad || pad || ct || pad || len(aad) || len(ct)
        val pad16a = ByteArray((16 - aad.size % 16) % 16)
        val pad16c = ByteArray((16 - ciphertext.size % 16) % 16)
        val macData = ByteArray(aad.size + pad16a.size + ciphertext.size + pad16c.size + 16)
        var p = 0
        System.arraycopy(aad, 0, macData, p, aad.size); p += aad.size
        System.arraycopy(pad16a, 0, macData, p, pad16a.size); p += pad16a.size
        System.arraycopy(ciphertext, 0, macData, p, ciphertext.size); p += ciphertext.size
        System.arraycopy(pad16c, 0, macData, p, pad16c.size); p += pad16c.size
        val la = leBytes64(aad.size.toLong())
        val lc = leBytes64(ciphertext.size.toLong())
        System.arraycopy(la, 0, macData, p, 8); p += 8
        System.arraycopy(lc, 0, macData, p, 8)

        val tag = poly1305(polyKey, macData)
        return ciphertext + tag
    }

    fun aeadOpen(
        key: ByteArray,
        counter: Long,
        ciphertextWithTag: ByteArray,
        aad: ByteArray
    ): ByteArray? {
        val nonce = ByteArray(12)
        val c = leBytes64(counter)
        System.arraycopy(c, 0, nonce, 0, 8)
        return aeadOpenNonce(key, nonce, ciphertextWithTag, aad)
    }

    /** AEAD-open с произвольным 12-байтным nonce (для тестов по RFC 8439). */
    fun aeadOpenNonce(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray
    ): ByteArray? {
        if (ciphertextWithTag.size < 16) return null
        val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - 16)
        val tag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - 16, ciphertextWithTag.size)

        val polyKey = chacha20(key, 0, nonce, ByteArray(64)).copyOfRange(0, 32)

        val pad16a = ByteArray((16 - aad.size % 16) % 16)
        val pad16c = ByteArray((16 - ciphertext.size % 16) % 16)
        val macData = ByteArray(aad.size + pad16a.size + ciphertext.size + pad16c.size + 16)
        var p = 0
        System.arraycopy(aad, 0, macData, p, aad.size); p += aad.size
        System.arraycopy(pad16a, 0, macData, p, pad16a.size); p += pad16a.size
        System.arraycopy(ciphertext, 0, macData, p, ciphertext.size); p += ciphertext.size
        System.arraycopy(pad16c, 0, macData, p, pad16c.size); p += pad16c.size
        System.arraycopy(leBytes64(aad.size.toLong()), 0, macData, p, 8); p += 8
        System.arraycopy(leBytes64(ciphertext.size.toLong()), 0, macData, p, 8)

        val expected = poly1305(polyKey, macData)
        if (!java.security.MessageDigest.isEqual(expected, tag)) return null

        return chacha20(key, 1, nonce, ciphertext)
    }
}
