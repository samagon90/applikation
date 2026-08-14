package ai.arena.webapp.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fake-DNS для VPN-туннеля: устройство спрашивает у нас (10.66.66.1),
 * мы мгновенно отвечаем виртуальным IP (10.66.66.x) и запоминаем
 * соответствие виртуальный IP → домен. Когда устройство соединяется
 * с виртуальным IP, SocksTcpStack передаёт SOCKS5-прокси доменное имя
 * (ATYP=3) — прокси резолвит его сам. Никаких локальных запросов DNS.
 */
class DnsResolver {

    companion object {
        const val VIRTUAL_BASE = "10.66.66"

        fun ipv4(s: String): ByteArray {
            val parts = s.split('.')
            return byteArrayOf(
                parts[0].toInt().toByte(),
                parts[1].toInt().toByte(),
                parts[2].toInt().toByte(),
                parts[3].toInt().toByte()
            )
        }
    }

    private val domainToVip = ConcurrentHashMap<String, ByteArray>()
    private val vipToDomain = ConcurrentHashMap<String, String>()
    private val counter = AtomicInteger(2)

    fun virtualIpFor(domain: String): ByteArray {
        domainToVip[domain]?.let { return it }
        val idx = counter.getAndIncrement()
        if (idx > 250) counter.set(2)
        val vip = ipv4("$VIRTUAL_BASE.$idx")
        domainToVip[domain] = vip
        vipToDomain[vipStr(vip)] = domain
        return vip
    }

    fun domainFor(virtualIp: ByteArray): String? = vipToDomain[vipStr(virtualIp)]

    fun isVirtualIp(ip: ByteArray): Boolean =
        ip.size >= 3 && ip[0] == 10.toByte() && ip[1] == 66.toByte() && ip[2] == 66.toByte()

    /** Ответить устройству на DNS-запрос (A → виртуальный IP, AAAA → пусто). */
    fun onDeviceDnsQuery(query: ByteArray): ByteArray? {
        if (query.size < 12) return null
        val domain = parseQueryName(query)
        if (domain.isEmpty()) return null
        // QTYPE после имени
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xff
            if (len == 0) { pos++; break }
            if ((len and 0xc0) == 0xc0) { pos += 2; break }
            pos += 1 + len
        }
        if (pos + 2 > query.size) return null
        val qtype = ((query[pos].toInt() and 0xff) shl 8) or (query[pos + 1].toInt() and 0xff)
        return if (qtype == 1) {
            buildDnsResponse(query, listOf(virtualIpFor(domain)))
        } else if (qtype == 28) {
            buildDnsResponse(query, emptyList())
        } else {
            buildDnsResponse(query, emptyList())
        }
    }

    fun buildQuery(id: Int, domain: String): ByteArray {
        val name = ByteArray(domain.length + 2)
        var pos = 0
        for (label in domain.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            name[pos++] = b.size.toByte()
            System.arraycopy(b, 0, name, pos, b.size)
            pos += b.size
        }
        name[pos] = 0
        val out = ByteArray(12 + name.size + 4)
        out[0] = ((id shr 8) and 0xff).toByte()
        out[1] = (id and 0xff).toByte()
        out[2] = 0x01 // RD
        out[3] = 0x00
        out[4] = 0x00; out[5] = 0x01 // QDCOUNT = 1
        out[6] = 0x00; out[7] = 0x00
        out[8] = 0x00; out[9] = 0x00
        out[10] = 0x00; out[11] = 0x00
        System.arraycopy(name, 0, out, 12, name.size)
        out[out.size - 4] = 0
        out[out.size - 3] = 1
        out[out.size - 2] = 0
        out[out.size - 1] = 1
        return out
    }

    fun parseQueryName(query: ByteArray): String {
        val labels = ArrayList<String>()
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xff
            if (len == 0) break
            if ((len and 0xc0) == 0xc0) break
            if (len > 63 || pos + 1 + len > query.size) return ""
            labels.add(String(query, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        return labels.joinToString(".")
    }

    private fun buildDnsResponse(query: ByteArray, ips: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        fun w(b: ByteArray) = out.write(b, 0, b.size)
        out.write(query[0].toInt())
        out.write(query[1].toInt())
        out.write(0x81)
        out.write(0x80)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(ips.size)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        // копируем вопрос целиком (имя + тип + класс)
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xff
            if (len == 0) {
                out.write(0)
                pos++
                break
            }
            if ((len and 0xc0) == 0xc0) {
                w(query.copyOfRange(pos, pos + 2))
                pos += 2
                break
            }
            w(query.copyOfRange(pos, pos + 1 + len))
            pos += 1 + len
        }
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x01)
        if (ips.isEmpty()) {
            return out.toByteArray()
        }
        for (ip in ips) {
            out.write(0xc0)
            out.write(0x0c)
            out.write(0x00)
            out.write(0x01)
            out.write(0x00)
            out.write(0x01)
            out.write(0x00)
            out.write(0x00)
            out.write(0x01)
            out.write(0x2c) // TTL = 300
            out.write(0x00)
            out.write(0x04)
            out.write(ip[0].toInt())
            out.write(ip[1].toInt())
            out.write(ip[2].toInt())
            out.write(ip[3].toInt())
        }
        return out.toByteArray()
    }

    private fun vipStr(ip: ByteArray): String =
        "${ip[0].toInt() and 0xff}.${ip[1].toInt() and 0xff}.${ip[2].toInt() and 0xff}.${ip[3].toInt() and 0xff}"
}
