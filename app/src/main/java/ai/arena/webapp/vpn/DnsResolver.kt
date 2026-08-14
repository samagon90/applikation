package ai.arena.webapp.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Минимальный DNS-клиент/сервер. Резолвит имена через туннель
 * (UDP-запросы к 1.1.1.1, инкапсулированные в WireGuard), отвечает
 * на запросы устройства виртуальными IP (10.66.66.x).
 */
class DnsResolver(
    private val sendToTunnel: (ByteArray) -> Unit
) {

    companion object {
        const val DNS_SERVER_IP = "1.1.1.1"
        const val VIRTUAL_BASE = "10.66.66"
    }

    data class Answer(
        val queryId: Int,
        val domain: String,
        val ips: List<ByteArray>
    )

    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (ByteArray?) -> Unit>()
    private val domainCache = ConcurrentHashMap<String, ByteArray>() // домен -> реальный IP
    private val virtualPool = ConcurrentHashMap<ByteArray, String>() // виртуальный IP -> домен
    private val virtualCounter = AtomicInteger(2)

    // ------------------------------------------------------- Запросы от нас

    /** Резолвить домен через туннель (вызов из VPN-потока, блокирующий). */
    fun resolve(domain: String, timeoutMs: Int = 5000): ByteArray? {
        val cached = domainCache[domain]
        if (cached != null) return cached
        val id = nextId.getAndIncrement() and 0xffff
        val query = buildQuery(id, domain)
        val waiter = Object()
        var result: ByteArray? = null
        pending[id] = { ip -> synchronized(waiter) { result = ip; waiter.notifyAll() } }
        try {
            sendToTunnel(buildUdpIpPacket(DNS_SERVER_IP, 53, query))
            synchronized(waiter) {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (result == null) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    waiter.wait(left)
                }
            }
        } finally {
            pending.remove(id)
        }
        result?.let { domainCache[domain] = it }
        return result
    }

    /** Выдать виртуальный IP для домена (для ответа устройству). */
    fun virtualIpFor(domain: String): ByteArray {
        // ищем существующую выдачу
        for ((vip, d) in virtualPool) if (d == domain) return vip
        val idx = virtualCounter.getAndIncrement()
        if (idx > 254) virtualCounter.set(2)
        val vip = ipv4("$VIRTUAL_BASE.$idx")
        virtualPool[vip] = domain
        return vip
    }

    /** Реальный IP по виртуальному. */
    fun realIpFor(virtualIp: ByteArray): ByteArray? {
        val domain = virtualPool[virtualIp] ?: return null
        return domainCache[domain] ?: resolve(domain)
    }

    fun isVirtualIp(ip: ByteArray): Boolean =
        ip[0] == 10.toByte() && ip[1] == 66.toByte() && ip[2] == 66.toByte()

    /** Обработать входящий DNS-ответ из туннеля. */
    fun onTunnelPacket(packet: ByteArray) {
        val parsed = parseIpPacket(packet) ?: return
        if (parsed.protocol != 17) return // UDP
        val srcPort = parsed.srcPort
        val dstPort = parsed.dstPort
        if (srcPort != 53 || dstPort == 0) return
        val callback = pending[dnsId(parsed.payload)]
        if (callback != null) {
            callback(parseAnswer(parsed.payload))
        }
    }

    /** Обработать DNS-запрос устройства, вернуть ответ или null. */
    fun onDeviceDnsQuery(query: ByteArray): ByteArray? {
        val domain = parseQueryName(query)
        if (domain.isEmpty()) return null
        val id = dnsId(query)
        val type = if (query.size >= 4) ((query[2].toInt() shl 8) or query[3].toInt()) else 0
        if (type == 28) { // AAAA — отвечаем пустым
            return buildDnsResponse(query, emptyList())
        }
        val realIp = resolve(domain)
        return if (realIp != null) {
            buildDnsResponse(query, listOf(realIp))
        } else {
            null
        }
    }

    // ---------------------------------------------------------- DNS формат

    private fun dnsId(query: ByteArray): Int =
        ((query[0].toInt() and 0xff) shl 8) or (query[1].toInt() and 0xff)

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
        // header(12) + name + QTYPE(2) + QCLASS(2)
        val out = ByteArray(12 + name.size + 4)
        out[0] = ((id shr 8) and 0xff).toByte()
        out[1] = (id and 0xff).toByte()
        out[2] = 0x01 // RD
        out[3] = 0x00
        out[4] = 0x00; out[5] = 0x01 // QDCOUNT = 1
        out[6] = 0x00; out[7] = 0x00 // ANCOUNT = 0
        out[8] = 0x00; out[9] = 0x00 // NSCOUNT = 0
        out[10] = 0x00; out[11] = 0x00 // ARCOUNT = 0
        System.arraycopy(name, 0, out, 12, name.size)
        // QTYPE A, QCLASS IN
        out[out.size - 4] = 0
        out[out.size - 3] = 1
        out[out.size - 2] = 0
        out[out.size - 1] = 1
        return out
    }

    fun parseQueryName(query: ByteArray): String {
        // имя начинается с 12 байта (header) + QNAME
        val labels = ArrayList<String>()
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xff
            if (len == 0) break
            if (len > 63 || pos + 1 + len > query.size) return ""
            labels.add(String(query, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        return labels.joinToString(".")
    }

    private fun parseAnswer(dnsResponse: ByteArray): ByteArray? {
        if (dnsResponse.size < 12) return null
        val qdCount = ((dnsResponse[4].toInt() and 0xff) shl 8) or (dnsResponse[5].toInt() and 0xff)
        val anCount = ((dnsResponse[6].toInt() and 0xff) shl 8) or (dnsResponse[7].toInt() and 0xff)
        if (anCount == 0) return null
        var pos = 12
        // пропускаем QNAME
        while (pos < dnsResponse.size) {
            val len = dnsResponse[pos].toInt() and 0xff
            if (len == 0) { pos++; break }
            if ((len and 0xc0) == 0xc0) { pos += 2; break }
            pos += 1 + len
        }
        pos += 4 // QTYPE + QCLASS
        repeat(qdCount.coerceAtMost(4)) { pos += skipResource(dnsResponse, pos) }
        // answers
        repeat(anCount.coerceAtMost(8)) {
            // name (может быть указателем)
            if (pos >= dnsResponse.size) return null
            val len = dnsResponse[pos].toInt() and 0xff
            if (len != 0xc0) {
                // пропускаем имя
                while (pos < dnsResponse.size) {
                    val l = dnsResponse[pos].toInt() and 0xff
                    if (l == 0) { pos++; break }
                    if ((l and 0xc0) == 0xc0) { pos += 2; break }
                    pos += 1 + l
                }
            } else pos += 2
            if (pos + 10 > dnsResponse.size) return null
            val type = ((dnsResponse[pos].toInt() and 0xff) shl 8) or (dnsResponse[pos + 1].toInt() and 0xff)
            val rdLength = ((dnsResponse[pos + 8].toInt() and 0xff) shl 8) or (dnsResponse[pos + 9].toInt() and 0xff)
            pos += 10
            if (type == 1 && rdLength == 4 && pos + 4 <= dnsResponse.size) {
                val ip = dnsResponse.copyOfRange(pos, pos + 4)
                return ip
            }
            pos += rdLength
        }
        return null
    }

    private fun skipResource(data: ByteArray, pos: Int): Int {
        var p = pos
        if (p + 4 > data.size) return data.size
        val rdLength = ((data[p + 8].toInt() and 0xff) shl 8) or (data[p + 9].toInt() and 0xff)
        return 10 + rdLength
    }

    private fun buildDnsResponse(query: ByteArray, ips: List<ByteArray>): ByteArray {
        val id = query[0]
        val id2 = query[1]
        // header: id, flags 0x8180, qd=1, an=n
        val out = java.io.ByteArrayOutputStream()
        out.write(id.toInt())
        out.write(id2.toInt())
        out.write(0x81)
        out.write(0x80)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(ips.size.coerceAtLeast(1))
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        // копируем вопрос целиком
        var pos = 12
        while (pos < query.size) {
            val len = query[pos].toInt() and 0xff
            if (len == 0) { out.write(0); pos++; break }
            out.write(query.copyOfRange(pos, pos + 1 + len))
            pos += 1 + len
        }
        out.write(0x00)
        out.write(0x01) // A
        out.write(0x00)
        out.write(0x01) // IN
        if (ips.isEmpty()) {
            out.write(0x00)
            out.write(0x00)
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
            out.write(0x00)
            out.write(0x3c) // TTL
            out.write(0x00)
            out.write(0x04)
            out.write(ip[0].toInt())
            out.write(ip[1].toInt())
            out.write(ip[2].toInt())
            out.write(ip[3].toInt())
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------ IP utils

    fun buildUdpIpPacket(dstIp: String, dstPort: Int, payload: ByteArray): ByteArray {
        // src IP будет заменён VPN-сервисом (адрес WARP)
        val srcIp = ByteArray(4)
        val dst = ipv4(dstIp)
        return buildIpv4Udp(srcIp, dst, 12345, dstPort, payload)
    }

    fun ipv4(s: String): ByteArray {
        val parts = s.split('.')
        return byteArrayOf(
            parts[0].toInt().toByte(),
            parts[1].toInt().toByte(),
            parts[2].toInt().toByte(),
            parts[3].toInt().toByte()
        )
    }

    fun buildIpv4Udp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val buf = ByteBuffer.allocate(20 + udpLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45.toByte()) // v4, IHL 5
        buf.put(0x00)
        buf.putShort((20 + udpLen).toShort())
        buf.putShort(0x0000) // id
        buf.putShort(0x0000) // flags/frag
        buf.put(64) // ttl
        buf.put(17) // udp
        buf.putShort(0) // checksum (заполнится позже? для UDP в WG не критично — WARP не проверяет? заполним 0)
        buf.put(src)
        buf.put(dst)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0) // checksum = 0 (не проверяется для IPv4 UDP по RFC может быть 0)
        buf.put(payload)
        return buf.array()
    }

    fun parseIpPacket(packet: ByteArray): ParsedIp? {
        if (packet.size < 20) return null
        val v = (packet[0].toInt() shr 4) and 0xf
        if (v != 4) return null
        val ihl = (packet[0].toInt() and 0xf) * 4
        if (packet.size < ihl) return null
        val totalLen = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        val proto = packet[9].toInt() and 0xff
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)
        val payload = if (totalLen > 0) packet.copyOfRange(ihl, minOf(packet.size, totalLen)) else packet.copyOfRange(ihl, packet.size)
        var srcPort = 0
        var dstPort = 0
        if (proto == 17 && payload.size >= 8) {
            srcPort = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
            dstPort = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        }
        return ParsedIp(src, dst, proto, srcPort, dstPort, payload)
    }

    data class ParsedIp(
        val src: ByteArray,
        val dst: ByteArray,
        val protocol: Int,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )
}
