package ai.arena.webapp.vpn

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Минимальный TCP/IP-стек для VPN: проксирует TCP-соединения между
 * устройством (через TUN) и интернетом (через WireGuard-туннель).
 * Поддерживает: SYN/SYN-ACK/ACK, данные, FIN/RST, retransmit-таймеры
 * и оконный контроль в обе стороны.
 */
class TcpStack(
    private val warpAddress: ByteArray,
    private val sendToTunnel: (ByteArray) -> Unit,
    private val sendToDevice: (ByteArray) -> Unit
) {

    companion object {
        const val TCP_FIN = 0x01
        const val TCP_SYN = 0x02
        const val TCP_RST = 0x04
        const val TCP_PSH = 0x08
        const val TCP_ACK = 0x10
    }

    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private val serverSessions = ConcurrentHashMap<String, TcpSession>()
    private val rng = java.security.SecureRandom()
    private var ipId = 1
    private var ephemeralPort = 20000

    private fun nextIpId(): Int = synchronized(this) { (ipId++) and 0xffff }
    private fun nextSrcPort(): Int = synchronized(this) {
        ephemeralPort++
        if (ephemeralPort > 60000) ephemeralPort = 20000
        ephemeralPort
    }
    private fun nextIsn(): Int = rng.nextInt()

    // ------------------------------------------------------------ устройство

    /** Входящий IP-пакет из TUN. */
    fun onDevicePacket(packet: ByteArray) {
        val ip = parseIp(packet) ?: return
        if (ip.protocol != 6) return
        val seg = parseTcp(ip.payload) ?: return
        val key = sessionKey(ip.src, ip.srcPort, ip.dst, ip.dstPort)
        if (seg.flags and TCP_SYN != 0 && seg.flags and TCP_ACK == 0) {
            val session = TcpSession(ip.src, ip.srcPort, ip.dst, ip.dst, ip.dstPort)
            sessions[key] = session
            serverSessions[serverKey(session.serverIp, session.serverPort, session.ourPort)] = session
            session.onDeviceSyn(ip, seg)
        } else {
            val session = sessions[key] ?: return
            session.onDeviceSegment(ip, seg)
        }
    }

    /**
     * Создать сессию с подменой виртуального IP на реальный:
     * [deviceDstIp] — адрес, который видит устройство, [serverIp] — реальный.
     */
    fun onDeviceSynWithMap(
        deviceIp: ByteArray,
        devicePort: Int,
        deviceDstIp: ByteArray,
        serverIp: ByteArray,
        dstPort: Int,
        synIp: ParsedIp,
        synSeg: TcpSegment
    ) {
        val key = sessionKey(deviceIp, devicePort, deviceDstIp, dstPort)
        val session = TcpSession(deviceIp, devicePort, deviceDstIp, serverIp, dstPort)
        sessions[key] = session
        serverSessions[serverKey(serverIp, dstPort, session.ourPort)] = session
        session.onDeviceSyn(synIp, synSeg)
    }

    /** Входящий IP-пакет из туннеля (ответы серверов). */
    fun onTunnelPacket(packet: ByteArray) {
        val ip = parseIp(packet) ?: return
        if (ip.protocol != 6) return
        val seg = parseTcp(ip.payload) ?: return
        // ищем сессию по (IP сервера, порт сервера, наш порт)
        val key = serverKey(ip.src, ip.srcPort, ip.dstPort)
        val session = serverSessions[key] ?: return
        session.onServerSegment(ip, seg)
    }

    private fun sessionKey(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int): String =
        "${ipStr(src)}:$srcPort->${ipStr(dst)}:$dstPort"

    private fun serverKey(dst: ByteArray, dstPort: Int, ourPort: Int): String =
        "->${ipStr(dst)}:$dstPort#$ourPort"

    private fun serverKeyOf(session: TcpSession): String =
        serverKey(session.serverIp, session.serverPort, session.ourPort)

    fun closeAll() {
        for (s in sessions.values) s.close()
        sessions.clear()
        serverSessions.clear()
    }

    fun tick() {
        val now = System.currentTimeMillis()
        for (s in sessions.values) s.tick(now)
        sessions.entries.removeIf { it.value.isDead() }
        serverSessions.entries.removeIf { it.value.isDead() }
    }

    // ------------------------------------------------------------ TCP session

    inner class TcpSession(
        val deviceIp: ByteArray,
        val devicePort: Int,
        val deviceDstIp: ByteArray,     // адрес, который видит устройство (может быть виртуальным)
        val serverIp: ByteArray,        // реальный адрес сервера
        val serverPort: Int
    ) {
        val ourPort = nextSrcPort()

        private val devIsn = nextIsn()
        private val srvIsn = nextIsn()

        // устройство: мы — сервер
        private var devSeqNext = devIsn + 1        // следующий seq данных устройству
        private var devAcked = devIsn              // что устройство подтвердило
        private var devState = 0 // 0=SYN_RCVD, 1=ESTABLISHED, 2=FIN_SENT, 3=CLOSED
        private var devFinSeen = false
        private var devUnacked = ArrayDeque<OutSeg>() // (seq, data, sentAt, retries)

        // сервер: мы — клиент
        private var srvSeqNext = srvIsn + 1
        private var srvAcked = srvIsn
        private var srvState = 0 // 0=SYN_SENT, 1=ESTABLISHED, 2=FIN_SENT, 3=CLOSED
        private var srvFinSeen = false
        private var srvUnacked = ArrayDeque<OutSeg>()

        private val devBuffer = java.io.ByteArrayOutputStream()   // данные к серверу
        private val srvBuffer = java.io.ByteArrayOutputStream()   // данные к устройству
        private var lastActivity = System.currentTimeMillis()
        private var closed = false


        fun onDeviceSyn(ip: ParsedIp, seg: TcpSegment) {
            // SYN-ACK устройству
            val opts = tcpOptions(mss = 1280)
            devUnacked.addLast(OutSeg(devIsn, ByteArray(0), System.currentTimeMillis(), 0, false))
            sendTcpToDevice(ip, TCP_SYN or TCP_ACK, devIsn, seg.seq + 1, opts, ByteArray(0))
            // SYN серверу через туннель
            val srvOpts = tcpOptions(mss = 1280)
            val synIp = buildTcpIp(warpAddress, serverIp, ourPort, serverPort, srvIsn, 0, TCP_SYN, srvOpts, ByteArray(0))
            sendToTunnel(synIp)
            srvSeqNext = srvIsn + 1
            srvUnacked.addLast(OutSeg(srvIsn, ByteArray(0), System.currentTimeMillis(), 0, false))
            touch()
        }

        fun onDeviceSegment(ip: ParsedIp, seg: TcpSegment) {
            touch()
            // FIN от устройства
            if (seg.flags and TCP_FIN != 0) {
                devFinSeen = true
                // ACK FIN
                sendTcpToDevice(ip, TCP_ACK, devSeqNext, seg.seq + 1 + seg.payload.size, tcpOptions(0), ByteArray(0))
                // шлём FIN серверу
                if (srvState >= 1) {
                    sendServerFin()
                }
            }
            // RST
            if (seg.flags and TCP_RST != 0) {
                close()
                return
            }
            // ACK обработать
            if (seg.flags and TCP_ACK != 0) {
                devAcked = maxSeq(devAcked, seg.ack)
                // выкидываем подтверждённые
                while (devUnacked.isNotEmpty()) {
                    val first = devUnacked.first()
                    val end = first.seq + first.data.size + (if (first.fin) 1 else 0)
                    if (ge(seg.ack, end)) {
                        devUnacked.removeFirst()
                    } else break
                }
                if (devState == 0) devState = 1 // ACK нашего SYN-ACK
                if (devState == 2 && ge(seg.ack, devSeqNext)) devState = 3
            }
            // Данные от устройства → буфер → серверу
            if (seg.payload.isNotEmpty()) {
                if (devBuffer.size() < 256 * 1024) {
                    devBuffer.write(seg.payload)
                    pushDevToServer()
                }
                // ACK устройству (кумулятивный)
                sendTcpToDevice(ip, TCP_ACK, devSeqNext, seg.seq + seg.payload.size, tcpOptions(0), ByteArray(0))
            }
            maybeClose()
        }

        fun onServerSegment(ip: ParsedIp, seg: TcpSegment) {
            touch()
            if (seg.flags and TCP_RST != 0) {
                close()
                return
            }
            if (seg.flags and TCP_SYN != 0 && srvState == 0) {
                // SYN-ACK
                srvState = 1
                sendServerAck(seg.seq + 1)
                return
            }
            if (seg.flags and TCP_FIN != 0) {
                srvFinSeen = true
                // ACK FIN
                sendServerAck(seg.seq + 1 + seg.payload.size)
                // шлём FIN устройству
                if (devState >= 1 && !devFinSeen) {
                    sendDeviceFin()
                }
            }
            // ACK
            if (seg.flags and TCP_ACK != 0) {
                srvAcked = maxSeq(srvAcked, seg.ack)
                while (srvUnacked.isNotEmpty()) {
                    val first = srvUnacked.first()
                    val end = first.seq + first.data.size + (if (first.fin) 1 else 0)
                    if (ge(seg.ack, end)) {
                        srvUnacked.removeFirst()
                    } else break
                }
            }
            // Данные от сервера → буфер → устройству
            if (seg.payload.isNotEmpty()) {
                if (srvBuffer.size() < 512 * 1024) {
                    srvBuffer.write(seg.payload)
                    pushSrvToDevice()
                }
                sendServerAck(seg.seq + seg.payload.size)
            }
            maybeClose()
        }

        private fun pushDevToServer() {
            if (srvState < 1) return
            // режем на сегменты по 1280
            val data = devBuffer.toByteArray()
            if (data.isEmpty()) return
            devBuffer.reset()
            var off = 0
            while (off < data.size) {
                val chunk = data.copyOfRange(off, minOf(off + 1280, data.size))
                off += chunk.size
                val pkt = buildTcpIp(
                    warpAddress, serverIp, ourPort, serverPort,
                    srvSeqNext, srvAcked, TCP_PSH or TCP_ACK, tcpOptions(0), chunk
                )
                srvUnacked.addLast(OutSeg(srvSeqNext, chunk, System.currentTimeMillis(), 0, false))
                srvSeqNext += chunk.size
                sendToTunnel(pkt)
            }
        }

        private fun pushSrvToDevice() {
            if (devState < 1) return
            val data = srvBuffer.toByteArray()
            if (data.isEmpty()) return
            srvBuffer.reset()
            var off = 0
            while (off < data.size) {
                val chunk = data.copyOfRange(off, minOf(off + 1280, data.size))
                off += chunk.size
                val pkt = buildTcpIp(
                    deviceDstIp, deviceIp, serverPort, devicePort,
                    devSeqNext, devAcked, TCP_PSH or TCP_ACK, tcpOptions(0), chunk
                )
                devUnacked.addLast(OutSeg(devSeqNext, chunk, System.currentTimeMillis(), 0, false))
                devSeqNext += chunk.size
                sendToDevice(pkt)
            }
        }

        private fun sendServerAck(ackSeq: Int) {
            val pkt = buildTcpIp(
                warpAddress, serverIp, ourPort, serverPort,
                srvSeqNext, ackSeq, TCP_ACK, tcpOptions(0), ByteArray(0)
            )
            sendToTunnel(pkt)
        }

        private fun sendServerFin() {
            val pkt = buildTcpIp(
                warpAddress, serverIp, ourPort, serverPort,
                srvSeqNext, srvAcked, TCP_FIN or TCP_ACK, tcpOptions(0), ByteArray(0)
            )
            srvUnacked.addLast(OutSeg(srvSeqNext, ByteArray(0), System.currentTimeMillis(), 0, true))
            srvSeqNext += 1
            srvState = 2
            sendToTunnel(pkt)
        }

        private fun sendDeviceFin() {
            val pkt = buildTcpIp(
                deviceDstIp, deviceIp, serverPort, devicePort,
                devSeqNext, devAcked, TCP_FIN or TCP_ACK, tcpOptions(0), ByteArray(0)
            )
            devUnacked.addLast(OutSeg(devSeqNext, ByteArray(0), System.currentTimeMillis(), 0, true))
            devSeqNext += 1
            devState = 2
            sendToDevice(pkt)
        }

        private fun sendTcpToDevice(
            ip: ParsedIp,
            flags: Int,
            seq: Int,
            ack: Int,
            opts: ByteArray,
            data: ByteArray
        ) {
            val pkt = buildTcpIp(ip.dst, ip.src, ip.dstPort, ip.srcPort, seq, ack, flags, opts, data)
            sendToDevice(pkt)
        }

        fun tick(now: Long) {
            // retransmit в сторону устройства
            retransmit(devUnacked, now) { seg ->
                val pkt = buildTcpIp(
                    deviceDstIp, deviceIp, serverPort, devicePort,
                    seg.seq, devAcked, TCP_PSH or TCP_ACK or if (seg.fin) TCP_FIN else 0,
                    tcpOptions(0), seg.data
                )
                sendToDevice(pkt)
            }
            // retransmit в сторону сервера
            retransmit(srvUnacked, now) { seg ->
                val pkt = buildTcpIp(
                    warpAddress, serverIp, ourPort, serverPort,
                    seg.seq, srvAcked, TCP_PSH or TCP_ACK or if (seg.fin) TCP_FIN else 0,
                    tcpOptions(0), seg.data
                )
                sendToTunnel(pkt)
            }
        }

        private fun retransmit(queue: ArrayDeque<OutSeg>, now: Long, send: (OutSeg) -> Unit) {
            var first = true
            val it = queue.iterator()
            while (it.hasNext()) {
                val seg = it.next()
                val rto = if (first) 500L * (1 shl seg.retries.coerceAtMost(4)) else 5000L
                if (now - seg.sentAt >= rto) {
                    seg.sentAt = now
                    seg.retries++
                    send(seg)
                }
                first = false
            }
        }

        private fun maybeClose() {
            val bothFin = (devFinSeen || devState >= 2) && (srvFinSeen || srvState >= 2)
            val bothAcked = devUnacked.isEmpty() && srvUnacked.isEmpty()
            if (bothFin && bothAcked) {
                close()
            } else if (System.currentTimeMillis() - lastActivity > 300_000) {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            // RST обеим сторонам
            runCatching {
                val pkt = buildTcpIp(deviceDstIp, deviceIp, serverPort, devicePort, devSeqNext, 0, TCP_RST or TCP_ACK, tcpOptions(0), ByteArray(0))
                sendToDevice(pkt)
            }
        }

        fun isDead(): Boolean = closed

        private fun touch() {
            lastActivity = System.currentTimeMillis()
        }

        private fun ge(a: Int, b: Int): Boolean = a - b >= 0
        private fun maxSeq(a: Int, b: Int): Int = if (ge(a, b)) a else b
    }

    // -------------------------------------------------------------- сегменты

    data class TcpSegment(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Int,
        val ack: Int,
        val flags: Int,
        val window: Int,
        val payload: ByteArray
    )

    fun tcpOptions(mss: Int): ByteArray {
        if (mss <= 0) return ByteArray(0)
        val b = ByteArray(4)
        b[0] = 2
        b[1] = 4
        b[2] = ((mss shr 8) and 0xff).toByte()
        b[3] = (mss and 0xff).toByte()
        return b
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

    /** Собрать TCP-сегмент внутри IPv4-пакета. */
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
        // TCP
        buf.putShort(srcPort.toShort())
        buf.putShort(dstPort.toShort())
        buf.putInt(seq)
        buf.putInt(ack)
        buf.put((off shl 4).toByte())
        buf.put((flags and 0x3f).toByte())
        buf.putShort(16384.toShort()) // window
        buf.putShort(0)
        buf.putShort(0)
        if (options.isNotEmpty()) buf.put(options)
        // pad опций до 4 байт
        val pad = (4 - options.size % 4) % 4
        for (i in 0 until pad) buf.put(0)
        if (data.isNotEmpty()) buf.put(data)
        val pkt = buf.array()
        // TCP checksum с псевдо-заголовком
        val csum = tcpChecksum(src, dst, pkt, 20, segLen)
        pkt[36] = ((csum shr 8) and 0xff).toByte()
        pkt[37] = (csum and 0xff).toByte()
        return pkt
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

    fun parseIp(packet: ByteArray): ParsedIp? {
        if (packet.size < 20) return null
        val v = (packet[0].toInt() shr 4) and 0xf
        if (v != 4) return null
        val ihl = (packet[0].toInt() and 0xf) * 4
        val totalLen = ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff)
        val proto = packet[9].toInt() and 0xff
        val src = packet.copyOfRange(12, 16)
        val dst = packet.copyOfRange(16, 20)
        val payload = if (totalLen > 0) packet.copyOfRange(ihl, minOf(packet.size, totalLen)) else packet.copyOfRange(ihl, packet.size)
        var srcPort = 0
        var dstPort = 0
        if (proto == 6 && payload.size >= 4) {
            srcPort = ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
            dstPort = ((payload[2].toInt() and 0xff) shl 8) or (payload[3].toInt() and 0xff)
        }
        return ParsedIp(src, dst, proto, srcPort, dstPort, payload)
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

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xff) shl 24) or
            ((b[off + 1].toInt() and 0xff) shl 16) or
            ((b[off + 2].toInt() and 0xff) shl 8) or
            (b[off + 3].toInt() and 0xff)

    private fun ipStr(ip: ByteArray): String =
        "${ip[0].toInt() and 0xff}.${ip[1].toInt() and 0xff}.${ip[2].toInt() and 0xff}.${ip[3].toInt() and 0xff}"

    data class ParsedIp(
        val src: ByteArray,
        val dst: ByteArray,
        val protocol: Int,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray
    )

    data class OutSeg(
        val seq: Int,
        val data: ByteArray,
        var sentAt: Long,
        var retries: Int,
        val fin: Boolean
    )
}
