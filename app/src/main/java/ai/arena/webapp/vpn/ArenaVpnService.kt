package ai.arena.webapp.vpn

import ai.arena.webapp.ProxyManager
import ai.arena.webapp.R
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
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Встроенный VPN: TUN (VpnService) + SOCKS5-транспорт (tun2socks).
 *
 * При запуске сервис забирает у ProxyManager пул рабочих SOCKS5-прокси
 * (загружается из интернета и проверяется на устройстве), поднимает TUN
 * и гоняет весь трафик устройства через прокси. Если прокси падает —
 * автоматический переход на следующий из пула; пул обновляется в фоне.
 *
 * DNS: устройству отвечаем виртуальными IP (10.66.66.x), а SOCKS5-прокси
 * получает доменные имена (ATYP=3) и резолвит их сам.
 */
class ArenaVpnService : VpnService() {

    interface StatusListener {
        fun onVpnState(connected: Boolean, detail: String)
    }

    companion object {
        private const val TAG = "ArenaVpnService"
        private const val TUN_ADDRESS = "10.66.66.1"
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

        private fun notifyState(connected: Boolean, detail: String) {
            mainHandler.post {
                for (l in listeners) l.onVpnState(connected, detail)
            }
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var tunIn: FileInputStream? = null
    private var tunOut: FileOutputStream? = null
    private var stack: SocksTcpStack? = null
    private var dns: DnsResolver? = null

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)

    @Volatile
    private var detailText = ""

    private var pool: List<ProxyManager.Candidate> = emptyList()
    private var poolIndex = 0
    private var failCount = 0
    private var poolFetchedAt = 0L

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        if (!running.get()) startTunnel()
        return START_STICKY
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    private fun startTunnel() {
        if (running.get()) return
        running.set(true)
        instance = this
        startForegroundNotification()
        detailText = getString(R.string.status_vpn_searching)
        notifyState(false, detailText)
        Thread({ setup() }, "arena-vpn-setup").start()
    }

    private fun setup() {
        try {
            // 1. Пул рабочих SOCKS5 (кэш или загрузка+проверка из интернета)
            pool = ProxyManager.get(this).getSocksPool()
            if (pool.isEmpty()) {
                detailText = getString(R.string.status_vpn_no_proxies)
                notifyState(false, detailText)
                stopSelf()
                return
            }
            poolFetchedAt = System.currentTimeMillis()
            poolIndex = 0

            // 2. TUN
            @Suppress("DEPRECATION")
            tun = Builder(this@ArenaVpnService)
                .setMtu(MTU)
                .addAddress(TUN_ADDRESS, 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(TUN_ADDRESS)
                .setBlocking(true)
                .setSession("Arena AI VPN")
                .establish()
            tunIn = FileInputStream(tun!!.fileDescriptor)
            tunOut = FileOutputStream(tun!!.fileDescriptor)

            // 3. SOCKS-стек
            dns = DnsResolver()
            stack = SocksTcpStack(
                proxyProvider = { proxyCandidates() },
                dns = dns!!,
                sendToDevice = { pkt -> runCatching { tunOut?.write(pkt) } },
                protectSocket = { s -> runCatching { protect(s) } },
                onProxyFail = { onProxyFail() }
            )

            // 4. Чтение из TUN + тикер
            Thread({ deviceLoop() }, "arena-vpn-tun").start()
            Thread({ tickLoop() }, "arena-vpn-tick").start()

            connected.set(true)
            detailText = getString(R.string.status_connected_vpn, pool[poolIndex].address)
            notifyState(true, detailText)
        } catch (t: Throwable) {
            Log.e(TAG, "setup failed", t)
            detailText = getString(R.string.status_vpn_error, t.message ?: t.javaClass.simpleName)
            notifyState(false, detailText)
            stopSelf()
        }
    }

    /** Текущий пул, начиная с активного прокси. */
    private fun proxyCandidates(): List<Socks5Client> = synchronized(this) {
        val n = pool.size
        if (n == 0) return emptyList()
        (0 until n).map { i ->
            val c = pool[(poolIndex + i) % n]
            Socks5Client(c.host, c.port)
        }
    }

    /** Прокси падает — переключаемся на следующий, при необходимости обновляем пул. */
    private fun onProxyFail() {
        synchronized(this) {
            failCount++
            if (pool.size > 1) {
                poolIndex = (poolIndex + 1) % pool.size
                detailText = getString(R.string.status_connected_vpn, pool[poolIndex].address)
                notifyState(true, detailText)
                return
            }
            if (System.currentTimeMillis() - poolFetchedAt > 30_000) {
                poolFetchedAt = System.currentTimeMillis()
                ProxyManager.get(this@ArenaVpnService).refreshSocksPool { fresh ->
                    if (fresh.isNotEmpty()) {
                        synchronized(this@ArenaVpnService) {
                            pool = fresh
                            poolIndex = 0
                            failCount = 0
                            detailText = getString(R.string.status_connected_vpn, fresh[0].address)
                        }
                        notifyState(true, detailText)
                    }
                }
            }
        }
    }

    private fun deviceLoop() {
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
    }

    private fun onDevicePacket(packet: ByteArray) {
        val st = stack ?: return
        val dn = dns ?: return
        val parsed = st.parseIp(packet) ?: return
        when (parsed.protocol) {
            6 -> st.onDevicePacket(packet)
            17 -> {
                // DNS-запрос к нам → отвечаем виртуальным IP
                if (parsed.dstPort == 53 && dn.isVirtualIp(parsed.dst) && parsed.payload.size >= 8) {
                    val resp = dn.onDeviceDnsQuery(parsed.payload.copyOfRange(8, parsed.payload.size))
                    if (resp != null) {
                        val out = st.buildUdpIp(
                            DnsResolver.ipv4(TUN_ADDRESS), parsed.src, 53, parsed.srcPort, resp
                        )
                        runCatching { tunOut?.write(out) }
                    }
                }
                // Остальной UDP отбрасываем: QUIC (HTTP/3) упадёт на TCP —
                // HTTP/2 и WebSocket сайта работают по TCP.
            }
        }
    }

    private fun tickLoop() {
        while (running.get()) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                break
            }
            stack?.tick()
        }
    }

    private fun stopTunnel() {
        running.set(false)
        connected.set(false)
        instance = null
        runCatching { stack?.closeAll() }
        runCatching { tun?.close() }
        tun = null
        tunIn = null
        tunOut = null
        stack = null
        dns = null
        notifyState(false, getString(R.string.status_idle))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

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
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_vpn_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_vpn_text))
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .build()
        }
        startForeground(NOTIFICATION_ID, n)
    }
}
