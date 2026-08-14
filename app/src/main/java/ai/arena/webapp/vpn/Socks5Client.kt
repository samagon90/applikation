package ai.arena.webapp.vpn

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * SOCKS5-клиент (RFC 1928): greeting, опциональная user/pass-авторизация,
 * CONNECT с доменным адресом (ATYP=3 — прокси сам резолвит имя).
 * Используется для туннелирования всего трафика VPN через публичные
 * SOCKS5-прокси (схема tun2socks, как в CyberPortal X).
 */
class Socks5Client(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val username: String = "",
    private val password: String = ""
) {

    val address: String get() = "$proxyHost:$proxyPort"

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun writeAll(out: OutputStream, b: ByteArray) {
        out.write(b)
        out.flush()
    }

    /**
     * Туннельное TCP-соединение к [targetHost]:[targetPort] через прокси.
     * [protect] вызывается сразу после создания сокета (VpnService.protect).
     */
    fun connect(
        targetHost: String,
        targetPort: Int,
        timeoutMs: Int = 6000,
        protect: ((Socket) -> Unit)? = null
    ): Socket? {
        val s = Socket()
        try {
            protect?.invoke(s)
            s.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()
            val input = s.getInputStream()

            // greeting: VER=5, NMETHODS=1, 0x00 (без авторизации)
            writeAll(out, byteArrayOf(5, 1, 0))
            val m = ByteArray(2)
            if (!readFully(input, m) || m[0].toInt() != 5) {
                runCatching { s.close() }
                return null
            }
            when (m[1].toInt() and 0xff) {
                0 -> { /* ok */ }
                2 -> {
                    if (username.isEmpty()) {
                        runCatching { s.close() }
                        return null
                    }
                    val ub = username.toByteArray(Charsets.US_ASCII)
                    val pb = password.toByteArray(Charsets.US_ASCII)
                    val auth = ByteArray(3 + ub.size + pb.size)
                    auth[0] = 1
                    auth[1] = ub.size.toByte()
                    System.arraycopy(ub, 0, auth, 2, ub.size)
                    auth[2 + ub.size] = pb.size.toByte()
                    System.arraycopy(pb, 0, auth, 3 + ub.size, pb.size)
                    writeAll(out, auth)
                    val r = ByteArray(2)
                    if (!readFully(input, r) || r[1].toInt() != 0) {
                        runCatching { s.close() }
                        return null
                    }
                }
                else -> {
                    runCatching { s.close() }
                    return null
                }
            }

            // CONNECT: CMD=1, ATYP=3 (DOMAINNAME)
            val hostBytes = targetHost.toByteArray(Charsets.US_ASCII)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 5
            req[1] = 1
            req[2] = 0
            req[3] = 3
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            val p = 5 + hostBytes.size
            req[p] = ((targetPort shr 8) and 0xff).toByte()
            req[p + 1] = (targetPort and 0xff).toByte()
            writeAll(out, req)

            val head = ByteArray(4)
            if (!readFully(input, head)) {
                runCatching { s.close() }
                return null
            }
            if (head[1].toInt() != 0) { // REP != 0x00 — соединение отклонено
                runCatching { s.close() }
                return null
            }
            // пропускаем bound address
            val extra = when (head[3].toInt() and 0xff) {
                1 -> 6 // IPv4 + port
                4 -> 18 // IPv6 + port
                3 -> {
                    val l = input.read()
                    if (l < 0) {
                        runCatching { s.close() }
                        return null
                    }
                    1 + l + 2
                }
                else -> {
                    runCatching { s.close() }
                    return null
                }
            }
            if (extra > 0 && !readFully(input, ByteArray(extra))) {
                runCatching { s.close() }
                return null
            }
            s.soTimeout = 0
            return s
        } catch (t: Throwable) {
            runCatching { s.close() }
            return null
        }
    }
}
