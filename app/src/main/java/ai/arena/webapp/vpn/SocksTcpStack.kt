package ai.arena.webapp.vpn

import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * TCP-over-SOCKS5 стек (tun2socks): принимает IP-пакеты из TUN,
 * для каждого TCP-соединения устройства открывает SOCKS5-туннель
 * к цели и перекачивает данные в обе стороны.
 *
 * DNS работает через виртуальные IP: устройство спрашивает у нас,
 * мы отвечаем виртуальным адресом, а при соединении передаём прокси
 * доменное имя (ATYP=3) — прокси резолвит сам, ничего не резолвим локально.
 */
class SocksTcpStack(
    private val proxyProvider: () -> List<Socks5Client>,
    private val dns: DnsResolver,
    private val sendToDevice: (ByteArray) -> Unit,
    private val protectSocket: ((Socket) -> Unit)? = null,
    private val onProxyFail: () -> Unit = {}
) {

    companion object {
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_PSH = 0x08
        const val TCP_ACK = 0x10
    }

    private val sessions = ConcurrentHashMap<String, Sess>()
    private val rng = SecureRandom()
    private var ipId = 1

    private fun nextIpId(): Int = synchronized(this) {
        ipId++
        if (ipId > 0xffff) ipId = 1
        ipId
    }

    // --------------------------------------------------------- из устройства

    fun onDevicePacket(packet: ByteArray) {
        val ip = parseIp(packet) ?: return
        if (ip.protocol != 6) return
        val seg = parseTcp(ip.payload) ?: return
        val key = "${ipStr(ip.src)}:${ip.srcPort}->${ipStr(ip.dst)}:${ip.dstPort}"

        if (seg.flags and TCP_SYN != 0 && seg.flags and TCP_ACK == 0) {
            val existing = sessions[key]
            if (existing != null) {
                existing.onSynRetransmit(seg.seq)
                return
            }
            val domain = if (dns.isVirtualIp(ip.dst)) dns.domainFor(ip.dst) else null
            val target = domain ?: ipStr(ip.dst)
            val sess = Sess(ip.src, ip.srcPort, ip.dst, ip.dstPort, target, seg.seq)
            sessions[key] = sess
            sess.start()
        } else {
            sessions[key]?.onSegment(ip, seg)
        }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        for (s in sessions.values) s.tick(now)
        sessions.entries.removeIf { it.value.isDead() }
    }

    fun closeAll() {
        for (s in sessions.values) s.close()
        sessions.clear()
    }

    // ------------------------------------------------------------- сессия

    private inner class Sess(
        val deviceIp: ByteArray,
        val devicePort: Int,
        val deviceDst: ByteArray,
        val dstPort: Int,
        val target: String,
        initSeq: Int
    ) {

        private val devIsn = rng.nextInt()
        private var ourSeq = devIsn + 1
        private var devLastSeq = initSeq

        @Volatile private var established = false
        @Volatile private var closing = false
        @Volatile private var dead = false

        private var synAckSentAt = 0L
        private var synAckRetries = 0

        private var socket: Socket? = null
        private val queue = LinkedBlockingQueue<Any>()
        private val endMarker = Any()
        private val devUnacked = ArrayDeque<OutSeg>()
        private var writer: Thread? = null
        private var reader: Thread? = null

        fun start() {
            sendSynAck()
            Thread({ connectLoop() }, "socks-$target").start()
        }

        private fun connectLoop() {
            var sock: Socket? = null
            val clients = proxyProvider()
            for (client in clients.take(3)) {
                sock = client.connect(target, dstPort, protect = protectSocket)
                if (sock != null) break
            }
            if (sock == null) {
                synchronized(this) {
                    if (!dead) {
                        sendRst()
                        dead = true
                    }
                }
                onProxyFail()
                return
            }
            synchronized(this) {
                socket = sock
                established = true
            }
            // подтверждаем соединение ещё раз (устройство могло переспросить)
            sendSynAck()
            writer = Thread({ writeLoop() }, "socks-w-$target")
            writer!!.start()
            reader = Thread({ readLoop() }, "socks-r-$target")
            reader!!.start()
        }

        fun onSynRetransmit(seq: Int) {
            if (!established && !dead && synAckRetries < 6) {
                sendSynAck()
            }
        }

        fun onSegment(ip: ParsedIp, seg: TcpSegment) {
            if (dead) return
            if (seg.flags and TCP_RST != 0) {
                close()
                return
            }
            // ACK от устройства — снимаем подтверждённые сегменты
            if (seg.flags and TCP_ACK != 0) {
                while (devUnacked.isNotEmpty()) {
                    val first = devUnacked.first()
                    val end = first.seq + first.data.size + (if (first.fin) 1 else 0)
                    if (ge(seg.ack, end)) devUnacked.removeFirst() else break
                }
            }
            // FIN от устройства
            if (seg.flags and TCP_FIN != 0) {
                closing = true
                sendAckToDevice(seg.seq + seg.payload.size + 1)
                queue.offer(endMarker) // маркер конца для writer'а
                return
            }
            // данные устройства → прокси
            if (seg.payload.isNotEmpty()) {
                devLastSeq = seg.seq + seg.payload.size
                if (established && !closing) {
                    if (queue.size < 512) queue.offer(seg.payload)
                }
                sendAckToDevice(seg.seq + seg.payload.size)
            }
        }

        private fun readLoop() {
            val sock = socket ?: return
            val buf = ByteArray(16384)
            try {
                while (true) {
                    val n = sock.getInputStream().read(buf)
                    if (n < 0) break
                    synchronized(this) {
                        val pkt = buildTcpIp(
                            deviceDst, deviceIp, dstPort, devicePort,
                            ourSeq, devLastSeq + 1, TCP_PSH or TCP_ACK,
                            tcpOptions(0), buf.copyOfRange(0, n)
                        )
                        devUnacked.addLast(OutSeg(ourSeq, buf.copyOfRange(0, n), System.currentTimeMillis(), 0, false))
                        ourSeq += n
                        sendToDevice(pkt)
                    }
                }
                // EOF от сервера → FIN устройству
                synchronized(this) {
                    if (!dead && !closing) {
                        closing = true
                        sendFinToDevice()
                    }
                }
            } catch (t: Throwable) {
                synchronized(this) {
                    if (!dead && !closing) {
                        closing = true
                        sendFinToDevice()
                    }
                }
            }
        }

        private fun writeLoop() {
            val sock = socket ?: return
            try {
                while (true) {
                    val item = queue.take()
                    if (item === endMarker) break
                    val data = item as ByteArray
                    if (data.isNotEmpty()) {
                        sock.getOutputStream().write(data)
                    }
                }
                sock.close()
            } catch (t: Throwable) {
                runCatching { sock.close() }
            }
        }

        private fun sendSynAck() {
            if (dead) return
            val pkt = buildTcpIp(
                deviceDst, deviceIp, dstPort, devicePort,
                devIsn, devLastSeq + 1, TCP_SYN or TCP_ACK,
                tcpOptions(1280), ByteArray(0)
            )
            sendToDevice(pkt)
            synAckSentAt = System.currentTimeMillis()
            synAckRetries++
        }

        private fun sendAckToDevice(ack: Int) {
            if (dead) return
            val pkt = buildTcpIp(
                deviceDst, deviceIp, dstPort, devicePort,
                ourSeq, ack, TCP_ACK, tcpOptions(0), ByteArray(0)
            )
            sendToDevice(pkt)
        }

        private fun sendFinToDevice() {
            val pkt = buildTcpIp(
                deviceDst, deviceIp, dstPort, devicePort,
                ourSeq, devLastSeq + 1, TCP_FIN or TCP_ACK,
                tcpOptions(0), ByteArray(0)
            )
            devUnacked.addLast(OutSeg(ourSeq, ByteArray(0), System.currentTimeMillis(), 0, true))
            ourSeq += 1
            sendToDevice(pkt)
        }

        private fun sendRst() {
            val pkt = buildTcpIp(
                deviceDst, deviceIp, dstPort, devicePort,
                devIsn, devLastSeq + 1, TCP_RST or TCP_ACK,
                tcpOptions(0), ByteArray(0)
            )
            sendToDevice(pkt)
        }

        fun tick(now: Long) {
            if (dead) return
            // ретрансмит SYN-ACK, пока устройство не ответило
            if (!established && synAckRetries > 0 && now - synAckSentAt > 800 && synAckRetries < 6) {
                sendSynAck()
            }
            if (!established && synAckRetries >= 6 && now - synAckSentAt > 15_000) {
                close()
                return
            }
            // ретрансмит наших данных устройству (TUN-буфер редко теряет, но на всякий случай)
            var first = true
            val it = devUnacked.iterator()
            while (it.hasNext()) {
                val seg = it.next()
                val rto = if (first) 800L * (1 shl seg.retries.coerceAtMost(3)) else 5000L
                if (now - seg.sentAt >= rto) {
                    seg.sentAt = now
                    seg.retries++
                    val flags = TCP_PSH or TCP_ACK or (if (seg.fin) TCP_FIN else 0)
                    val pkt = buildTcpIp(
                        deviceDst, deviceIp, dstPort, devicePort,
                        seg.seq, devLastSeq + 1, flags, tcpOptions(0), seg.data
                    )
                    sendToDevice(pkt)
                }
                first = false
            }
            // зачистка закрытых сессий
            if (closing && (socket?.isClosed != false) && devUnacked.isEmpty()) {
                dead = true
            }
            if (established && socket?.isClosed == true && closing && devUnacked.isEmpty()) {
                dead = true
            }
        }

        fun close() {
            if (dead) return
            dead = true
            runCatching { socket?.close() }
        }

        fun isDead(): Boolean = dead

        private fun ge(a: Int, b: Int): Boolean = a - b >= 0
    }

    class OutSeg(
        val seq: Int,
        val data: ByteArray,
        var sentAt: Long,
        var retries: Int,
        val fin: Boolean
    )

    // ------------------------------------------------------------ парсинг

    class TcpSegment(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Int,
        val ack: Int,
        val flags: Int,
        val window: Int,
        val payload: ByteArray
    )

    class ParsedIp(
        val src: ByteArray,
        val dst: ByteArray,
        val protocol: Int,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    fun parseIp(packet: ByteArray): ParsedIp? {
        if (packet.size < 20) return null
        val v = (packet[0].toInt() shr 4) and 0xf
        if (v != 4) return null
        val ihl = (packet[0].toInt() and 0xf) * 4
        val totalLen = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        val proto = packet[9].toInt() and 0xff
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)
        val payload = if (totalLen > 0) {
            packet.copyOfRange(ihl, minOf(packet.size, totalLen))
        } else {
            packet.copyOfRange(ihl, packet.size)
        }
        var srcPort = 0
        var dstPort = 0
        if (proto == 6 && payload.size >= 4) {
            srcPort = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
            dstPort = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        }
        return ParsedIp(src, dst, proto, srcPort, dstPort, payload)
    }

    fun parseTcp(segment: ByteArray): TcpSegment? {
        if (segment.size < 20) return null
        val srcPort = ((segment[0].toInt() and 0xff) shl 8) or (segment[1].toInt() and 0xff)
        val dstPort = ((segment[2].toInt() and 0xff) shl 8) or (segment[3].toInt() and 0xff)
        val seq = readInt(segment, 4)
        val ack = readInt(segment, 8)
        val off = (segment[12].toInt() shr 4) and 0xf
        val flags = segment[13].toInt() and 0xff
        val window = ((segment[14].toInt() and 0xff) shl 8) or (segment[15].toInt() and 0xff)
        val headerLen = off * 4
        if (segment.size < headerLen) return null
        val payload = segment.copyOfRange(headerLen, segment.size)
        return TcpSegment(srcPort, dstPort, seq, ack, flags, window, payload)
    }

    fun tcpOptions(mss: Int): ByteArray {
        if (mss <= 0) return ByteArray(0)
        val b = ByteArray(4)
        b[0] = 2
        b[1] = 4
        b[2] = ((mss shr 8) and 0xff).toByte()
        b[3] = (mss and 0xff).toByte()
        return b
    }

    fun buildTcpIp(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        seq: Int,
        ack: Int,
        flags: Int,
        options: ByteArray,
        data: ByteArray
    ): ByteArray {
        val off = 5 + (options.size / 4)
        val segLen = off * 4 + data.size
        val buf = ByteBuffer.allocate(20 + segLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45.toByte())
        buf.put(0x00)
        buf.putShort((20 + segLen).toShort())
        buf.putShort(nextIpId().toShort())
        buf.putShort(0x4000.toShort()) // DF
        buf.put(64)
        buf.put(6)
        buf.putShort(0)
        buf.put(src)
        buf.put(dst)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq)
        buf.putInt(ack)
        buf.put((off shl 4).toByte())
        buf.put((flags and 0x3f).toByte())
        buf.putShort(16384.toShort())
        buf.putShort(0)
        buf.putShort(0)
        if (options.isNotEmpty()) buf.put(options)
        val pad = (4 - options.size % 4) % 4
        for (i in 0 until pad) buf.put(0)
        if (data.isNotEmpty()) buf.put(data)
        val pkt = buf.array()
        val csum = tcpChecksum(src, dst, pkt, 20, segLen)
        pkt[36] = ((csum shr 8) and 0xff).toByte()
        pkt[37] = (csum and 0xff).toByte()
        return pkt
    }

    fun buildUdpIp(src: ByteArray, dst: ByteArray, srcPort: Int, dstPort: Int, payload: ByteArray): ByteArray {
        val udpLen = 8 + payload.size
        val buf = ByteBuffer.allocate(20 + udpLen).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45.toByte())
        buf.put(0x00)
        buf.putShort((20 + udpLen).toShort())
        buf.putShort(nextIpId().toShort())
        buf.putShort(0x0000)
        buf.put(64)
        buf.put(17)
        buf.putShort(0)
        buf.put(src)
        buf.put(dst)
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putShort(udpLen.toShort())
        buf.putShort(0)
        buf.put(payload)
        return buf.array()
    }

    private fun tcpChecksum(src: ByteArray, dst: ByteArray, pkt: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        for (i in 0 until 4 step 2) {
            sum += ((src[i].toInt() and 0xff) shl 8) or (src[i + 1].toInt() and 0xff)
            sum += ((dst[i].toInt() and 0xff) shl 8) or (dst[i + 1].toInt() and 0xff)
        }
        sum += 6 + len
        var i = off
        while (i < off + len - 1) {
            sum += ((pkt[i].toInt() and 0xff) shl 8) or (pkt[i + 1].toInt() and 0xff)
            i += 2
        }
        if (i == off + len - 1) {
            sum += (pkt[i].toInt() and 0xff) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xffff) + (sum shr 16)
        }
        return (sum.inv()).toInt() and 0xffff
    }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    fun ipStr(ip: ByteArray): String =
        "${ip[0].toInt() and 0xff}.${ip[1].toInt() and 0xff}.${ip[2].toInt() and 0xff}.${ip[3].toInt() and 0xff}"
}
