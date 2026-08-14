package ai.arena.webapp.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Встроенный VPN: TUN (VpnService) + собственная реализация WireGuard
 * к бесплатному Cloudflare WARP + мини-TCP/IP-стек.
 *
 * Весь IPv4-трафик устройства идёт через туннель:
 *   устройство → TUN → TcpStack → WireGuard → WARP → интернет
 *
 * DNS: устройство запрашивает у нас (10.66.66.1); резолвим через туннель
 * (1.1.1.1) и отвечаем виртуальными IP (10.66.66.x), которые при
 * соединении подменяются реальными адресами.
 */
class ArenaVpnService : VpnService() {

    interface StatusListener {
        fun onVpnState(connected: Boolean, detail: String)
    }

    companion object {
        private const val TAG = "ArenaVpnService"
        private const val TUN_ADDRESS = "10.66.66.1"
        private const val VIRTUAL_DNS = "10.66.66.1"
        private const val MTU = 1280

        private const val NOTIFICATION_ID = 42
        private const val CHANNEL_ID = "arena_vpn"

        const val ACTION_START = "ai.arena.webapp.vpn.START"
        const val ACTION_STOP = "ai.arena.webapp.vpn.STOP"

        private val listeners = CopyOnWriteArrayList<StatusListener>()
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        private var instance: ArenaVpnService? = null

        @JvmStatic
        fun addListener(l: StatusListener) {
            listeners.add(l)
            val s = instance
            if (s != null) {
                mainHandler.post { l.onVpnState(s.isConnected(), s.statusDetail()) }
            }
        }

        @JvmStatic
        fun removeListener(l: StatusListener) {
            listeners.remove(l)
        }

        @JvmStatic
        fun isRunning(): Boolean = instance?.isConnected() == true

        fun notifyState(connected: Boolean, detail: String) {
            mainHandler.post {
                for (l in listeners) l.onVpnState(connected, detail)
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null

    private var udpSocket: DatagramSocket? = null
    private var endpointHost: String? = null
    private var endpointPort = 2408

    private var wg: WireGuardSession? = null
    private var handshakeState: WireGuardSession.HandshakeState? = null
    private val handshakeLock = Object()

    private var tcpStack: TcpStack? = null
    private var dnsResolver: DnsResolver? = null
    private var udpNat: UdpNat? = null

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    @Volatile
    private var detailText = ""

    private var tunnelThread: Thread? = null
    private var timerThread: Thread? = null
    private var lastHandshakeAt = 0L
    private var pendingQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()

    private val warpClient by lazy { WarpClient(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.get()) {
            startTunnel()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    // ------------------------------------------------------------- lifecycle

    private fun startTunnel() {
        if (running.get()) return
        running.set(true)
        instance = this
        startForegroundNotification()
        detailText = "Запуск…"

        val wgThread = Thread({ setupTunnel() }, "arena-vpn-setup")
        wgThread.start()
    }

    private fun setupTunnel() {
        try {
            // 1. Конфиг WARP (кэш или новая регистрация)
            var config = warpClient.loadCached()
            if (config == null) {
                config = warpClient.register()
            }
            if (config == null) {
                detailText = "Ошибка WARP: не удалось получить конфиг"
                notifyState(false, detailText)
                stopSelf()
                return
            }

            // 2. Проверяем доступность endpoint; перебираем запасные
            val ep = pickEndpoint(config)
            if (ep == null) {
                detailText = "Ошибка WARP: endpoint недоступен"
                notifyState(false, detailText)
                stopSelf()
                return
            }
            endpointHost = ep.first
            endpointPort = ep.second

            // 3. TUN
            @Suppress("DEPRECATION")
            tun = Builder(this@ArenaVpnService)
                .setMtu(MTU)
                .addAddress(TUN_ADDRESS, 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(VIRTUAL_DNS)
                .setBlocking(true)
                .setSession("Arena AI VPN")
                .establish()
            tunIn = FileInputStream(tun!!.fileDescriptor)
            tunOut = FileOutputStream(tun!!.fileDescriptor)

            // 4. WireGuard-сокет
            val sk = DatagramSocket()
            protect(sk)
            sk.connect(InetSocketAddress(ep.first, ep.second))
            udpSocket = sk

            val privateKey = Base64.decode(config.clientPrivateKey, Base64.DEFAULT)
            val serverPub = Base64.decode(config.serverPublicKey, Base64.DEFAULT)
            wg = WireGuardSession(privateKey, serverPub)

            val warpAddr = config.address.split('.')
                .map { it.toInt().toByte() }.toByteArray()

            val sendTunnel: (ByteArray) -> Unit = { pkt ->
                val enc = synchronized(handshakeLock) { wg?.encryptPacket(pkt) }
                if (enc != null) {
                    runCatching { udpSocket?.send(DatagramPacket(enc, enc.size)) }
                } else {
                    pendingQueue.add(pkt)
                }
            }
            val sendDevice: (ByteArray) -> Unit = { pkt ->
                runCatching { tunOut?.write(pkt) }
            }

            tcpStack = TcpStack(warpAddr, sendTunnel, sendDevice)
            dnsResolver = DnsResolver(sendTunnel)
            udpNat = UdpNat(warpAddr, sendTunnel, sendDevice)

            // 5. Поток чтения из TUN (пакеты устройства)
            startDeviceLoop()

            // 6. Поток чтения UDP (пакеты WireGuard/WARP)
            tunnelThread = Thread({ receiveLoop() }, "arena-vpn-recv")
            tunnelThread!!.start()

            // 7. Первый handshake
            performHandshake()

            // 8. Таймеры: keepalive + rekey + TCP retransmit
            timerThread = Thread({ timerLoop() }, "arena-vpn-timer")
            timerThread!!.start()

            detailText = "Подключено · WARP"
            notifyState(true, detailText)
        } catch (t: Throwable) {
            Log.e(TAG, "setupTunnel failed", t)
            detailText = "Ошибка VPN: ${t.message ?: t.javaClass.simpleName}"
            notifyState(false, detailText)
            stopSelf()
        }
    }

    private fun pickEndpoint(config: WarpClient.WarpConfig): Pair<String, Int>? {
        val candidates = linkedSetOf<String>()
        candidates.add(config.endpointHost)
        candidates.addAll(WarpClient.FALLBACK_ENDPOINTS)
        for (host in candidates) {
            if (warpClient.isReachable(host, config.endpointPort)) {
                return Pair(host, config.endpointPort)
            }
        }
        return null
    }

    private fun stopTunnel() {
        running.set(false)
        connected.set(false)
        instance = null
        runCatching { udpSocket?.close() }
        runCatching { tun?.close() }
        udpSocket = null
        tun = null
        tcpStack?.closeAll()
        tcpStack = null
        dnsResolver = null
        udpNat = null
        wg = null
        notifyState(false, "Выключен")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ----------------------------------------------------------- handshake

    private fun performHandshake() {
        val session = wg ?: return
        synchronized(handshakeLock) {
            val (msg, state) = session.buildInitMessage()
            handshakeState = state
            lastHandshakeAt = System.currentTimeMillis()
            runCatching { udpSocket?.send(DatagramPacket(msg, msg.size)) }
        }
    }

    private fun onHandshakeResponse(packet: ByteArray) {
        val session = wg ?: return
        synchronized(handshakeLock) {
            val state = handshakeState ?: return
            if (session.consumeResponse(packet, state)) {
                handshakeState = null
                connected.set(true)
                // слить очередь ожидающих пакетов
                while (true) {
                    val pkt = pendingQueue.poll() ?: break
                    val enc = session.encryptPacket(pkt) ?: break
                    runCatching { udpSocket?.send(DatagramPacket(enc, enc.size)) }
                }
            } else {
                Log.w(TAG, "bad handshake response")
            }
        }
    }

    // -------------------------------------------------------------- loops

    private fun receiveLoop() {
        val buf = ByteArray(65536)
        while (running.get()) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                udpSocket?.receive(pkt) ?: break
                val data = pkt.data.copyOfRange(0, pkt.length)
                when (data[0].toInt()) {
                    WireGuardSession.MESSAGE_RESPONSE -> onHandshakeResponse(data)
                    WireGuardSession.MESSAGE_TRANSPORT_DATA -> {
                        val payload = synchronized(handshakeLock) {
                            wg?.decryptPacket(data)
                        } ?: continue
                        if (payload.isEmpty()) continue // keepalive от сервера
                        routeTunnelPacket(payload)
                    }
                    else -> { /* cookie и т.п. — пропускаем */ }
                }
            } catch (t: Throwable) {
                if (!running.get()) break
            }
        }
    }

    private fun timerLoop() {
        while (running.get()) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                break
            }
            val now = System.currentTimeMillis()
            // keepalive каждые 20 секунд
            if (now - lastHandshakeAt > 20_000 && connected.get() && now % 20_000 < 1000) {
                val k = synchronized(handshakeLock) { wg?.buildKeepalive() }
                if (k != null) runCatching { udpSocket?.send(DatagramPacket(k, k.size)) }
            }
            // rekey каждые 100 секунд
            if (now - lastHandshakeAt > 100_000) {
                connected.set(false)
                performHandshake()
            }
            tcpStack?.tick()
            udpNat?.tick()
        }
    }

    // ----------------------------------------------------------- маршрутизация

    /** Пакет из туннеля (IP-пакет от сервера). */
    private fun routeTunnelPacket(ipPacket: ByteArray) {
        val parsed = tcpStack?.parseIp(ipPacket) ?: return
        when (parsed.protocol) {
            6 -> tcpStack?.onTunnelPacket(ipPacket)
            17 -> {
                if (parsed.srcPort == 53) {
                    dnsResolver?.onTunnelPacket(ipPacket)
                } else {
                    udpNat?.onTunnelPacket(ipPacket)
                }
            }
        }
    }

    /** Пакет от устройства (из TUN). */
    private fun onDevicePacket(ipPacket: ByteArray) {
        val parsed = tcpStack?.parseIp(ipPacket) ?: return
        when (parsed.protocol) {
            6 -> {
                val seg = tcpStack?.parseTcp(parsed.payload) ?: return
                val isSyn = seg.flags and TcpStack.TCP_SYN != 0 && seg.flags and TcpStack.TCP_ACK == 0
                val dstIsVirtual = dnsResolver?.isVirtualIp(parsed.dst) == true
                if (isSyn && dstIsVirtual) {
                    // виртуальный IP → реальный
                    val real = dnsResolver?.realIpFor(parsed.dst)
                    if (real != null) {
                        tcpStack?.onDeviceSynWithMap(
                            parsed.src, parsed.srcPort, parsed.dst, real, parsed.dstPort,
                            parsed, seg
                        )
                        return
                    }
                }
                tcpStack?.onDevicePacket(ipPacket)
            }
            17 -> {
                val isDns = parsed.dstPort == 53 && dnsResolver?.isVirtualIp(parsed.dst) == true
                if (isDns) {
                    // DNS-запрос от устройства → ответ
                    val query = parsed.payload.copyOfRange(8, parsed.payload.size)
                    val resp = dnsResolver?.onDeviceDnsQuery(query)
                    if (resp != null) {
                        val ip = tcpStack?.buildUdpIp(
                            dnsResolver!!.ipv4(VIRTUAL_DNS), parsed.src,
                            53, parsed.srcPort, resp
                        )
                        if (ip != null) {
                            runCatching { tunOut?.write(ip) }
                        }
                    }
                } else {
                    udpNat?.onDevicePacket(ipPacket)
                }
            }
        }
    }

    // -------------------------------------------------------------- status

    fun isConnected(): Boolean = connected.get()

    fun statusDetail(): String = detailText

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n: Notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Arena AI")
                .setContentText("VPN · Cloudflare WARP")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Arena AI")
                .setContentText("VPN · Cloudflare WARP")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
        startForeground(NOTIFICATION_ID, n)
    }

    // ---------------------------------------------------------------- UDP NAT

    /** UDP-проброс (QUIC/HTTP3) через туннель с NAT-таблицей. */
    inner class UdpNat(
        private val warpAddr: ByteArray,
        private val sendTunnel: (ByteArray) -> Unit,
        private val sendDevice: (ByteArray) -> Unit
    ) {

        private val map = ConcurrentHashMap<String, NatEntry>()
        private var nextPort = 40000

        fun onDevicePacket(ipPacket: ByteArray) {
            val parsed = tcpStack?.parseIp(ipPacket) ?: return
            if (parsed.protocol != 17) return
            val payload = parsed.payload
            if (payload.size < 8) return
            val key = "${ipStr(parsed.src)}:${parsed.srcPort}"
            var entry = map[key]
            if (entry == null) {
                val realDst = if (dnsResolver?.isVirtualIp(parsed.dst) == true) {
                    dnsResolver?.realIpFor(parsed.dst) ?: return
                } else parsed.dst
                entry = NatEntry(
                    parsed.src, parsed.srcPort, parsed.dst, realDst, parsed.dstPort,
                    synchronized(this) { nextPort++ }, System.currentTimeMillis()
                )
                map[key] = entry
            }
            entry.lastSeen = System.currentTimeMillis()
            val udpPayload = payload.copyOfRange(8, payload.size)
            val out = tcpStack?.buildUdpIp(warpAddr, entry.realDst, entry.ourPort, entry.dstPort, udpPayload)
            if (out != null) sendTunnel(out)
        }

        fun onTunnelPacket(ipPacket: ByteArray) {
            val parsed = tcpStack?.parseIp(ipPacket) ?: return
            if (parsed.protocol != 17) return
            for ((key, entry) in map) {
                if (entry.ourPort == parsed.dstPort) {
                    entry.lastSeen = System.currentTimeMillis()
                    val payload = parsed.payload
                    if (payload.size < 8) return
                    val udpPayload = payload.copyOfRange(8, payload.size)
                    val out = tcpStack?.buildUdpIp(entry.deviceDst, entry.deviceIp, entry.dstPort, entry.devicePort, udpPayload)
                    if (out != null) sendDevice(out)
                    return
                }
            }
        }

        fun tick() {
            val now = System.currentTimeMillis()
            map.entries.removeIf { now - it.value.lastSeen > 120_000 }
        }

        private fun ipStr(ip: ByteArray): String =
            "${ip[0].toInt() and 0xff}.${ip[1].toInt() and 0xff}.${ip[2].toInt() and 0xff}.${ip[3].toInt() and 0xff}"
    }

    // ------------------------------------------------------------ device loop

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    data class NatEntry(
        val deviceIp: ByteArray,
        val devicePort: Int,
        val deviceDst: ByteArray,
        val realDst: ByteArray,
        val dstPort: Int,
        val ourPort: Int,
        var lastSeen: Long
    )

    // Чтение из TUN запускаем после establish
    private fun startDeviceLoop() {
        Thread({
            val buf = ByteArray(65536)
            while (running.get()) {
                try {
                    val n = tunIn?.read(buf) ?: -1
                    if (n <= 0) continue
                    onDevicePacket(buf.copyOfRange(0, n))
                } catch (t: Throwable) {
                    if (!running.get()) break
                }
            }
        }, "arena-vpn-tun").start()
    }

}
