package ai.arena.webapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import ai.arena.webapp.vpn.ArenaVpnService
import ai.arena.webapp.vpn.Socks5Client
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/**
 * Движок подключения к arena.ai.
 *
 * Режимы:
 *  - AUTO    — напрямую; при недоступности сайта автоматически включается
 *              зеркало (если настроено) или встроенный пул прокси;
 *  - DIRECT  — всегда напрямую;
 *  - MIRROR  — всегда через собственное зеркало (Cloudflare Worker);
 *  - PROXY   — всегда через встроенный пул бесплатных прокси.
 *
 * Прокси применяется через официальный механизм WebView
 * (android.webkit.ProxyController, вызывается так же, как это делает
 * библиотека androidx.webkit) — это проксирует весь WebView, включая
 * WebSocket-стриминг ответов моделей. На устройствах без поддержки
 * используется системный android.net.ProxyController через рефлексию.
 *
 * Список прокси скачивается из публичных бесплатных источников и проверяется
 * на устройстве (CONNECT + TLS-хендшейк к arena.ai), чтобы оставались только
 * живые серверы.
 */
class ProxyManager private constructor(context: Context) {

    enum class Mode { AUTO, DIRECT, MIRROR, PROXY, VPN }

    enum class Status {
        IDLE, CHECKING, CONNECTED_DIRECT, CONNECTED_MIRROR,
        CONNECTED_PROXY, CONNECTED_VPN, FAILED
    }

    data class State(
        val mode: Mode,
        val status: Status,
        val detail: String = ""
    )

    interface Listener {
        fun onStateChanged(state: State)
    }

    companion object {
        private const val TAG = "ProxyManager"

        const val ARENA_HOST = "arena.ai"
        const val START_URL = "https://$ARENA_HOST"

        private const val PREFS = "arena_connection"
        private const val KEY_MODE = "mode"
        private const val KEY_MIRROR_URL = "mirror_url"
        private const val KEY_MIRROR_ENABLED = "mirror_enabled"
        private const val KEY_PROXY_CACHE = "proxy_cache"
        private const val KEY_PROXY_CACHE_TIME = "proxy_cache_time"
        private const val KEY_SOCKS_CACHE = "socks_cache"
        private const val KEY_SOCKS_CACHE_TIME = "socks_cache_time"
        private const val SOCKS_CACHE_TTL_MS = 15 * 60 * 1000L
        private const val CACHE_TTL_MS = 30 * 60 * 1000L
        private const val MAX_CANDIDATES = 400
        private const val TEST_BATCH = 80
        private const val HEALTH_THREADS = 10

        /** Публичные бесплатные списки HTTP-прокси. */
        private val PROXY_SOURCES = listOf(
            "https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/http.txt",
            "https://api.proxyscrape.com/v2/?request=getproxies&protocol=http&timeout=10000&country=all&ssl=all&anonymity=elite",
            "https://proxylist.geonode.com/api/proxy-list?limit=500&page=1&sort_by=lastChecked&sort_type=desc&protocols=http%2Chttps"
        )

        /** Публичные бесплатные списки SOCKS5-прокси (для встроенного VPN). */
        private val SOCKS_SOURCES = listOf(
            "https://cdn.jsdelivr.net/gh/TheSpeedX/PROXY-List@master/socks5.txt",
            "https://api.proxyscrape.com/v2/?request=getproxies&protocol=socks5&timeout=10000&country=all",
            "https://proxylist.geonode.com/api/proxy-list?limit=500&page=1&sort_by=lastChecked&sort_type=desc&protocols=socks5"
        )

        @Volatile
        private var instance: ProxyManager? = null

        @JvmStatic
        fun get(context: Context): ProxyManager =
            instance ?: synchronized(this) {
                instance ?: ProxyManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private val listeners = CopyOnWriteArrayList<Listener>()

    private val busy = AtomicBoolean(false)

    @Volatile
    private var status: Status = Status.IDLE
    private var statusDetail: String = ""

    @Volatile
    private var candidates: List<Candidate> = emptyList()
    private var candidateIndex = 0

    private var proxyApplied = false
    private var appliedProxy: Candidate? = null

    init {
        // Состояние встроенного VPN (Cloudflare WARP)
        ArenaVpnService.addListener(object : ArenaVpnService.StatusListener {
            override fun onVpnState(connected: Boolean, detail: String) {
                if (mode != Mode.VPN) return
                setStatus(
                    if (connected) Status.CONNECTED_VPN else Status.FAILED,
                    if (connected) "WARP" else detail
                )
            }
        })
    }

    // ------------------------------------------------------------------ Mode

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, Mode.AUTO.name)!!) }
            .getOrDefault(Mode.AUTO)
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    var mirrorUrl: String
        get() = prefs.getString(KEY_MIRROR_URL, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_MIRROR_URL, value.trim()).apply()
        }

    /** Включено ли зеркало (управляется свитчем в шторке). */
    var mirrorEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIRROR_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_MIRROR_ENABLED, value).apply()
        }

    val isMirrorConfigured: Boolean
        get() = normalizedMirrorUrl() != null

    fun normalizedMirrorUrl(): String? {
        var url = mirrorUrl.trim()
        if (url.isEmpty()) return null
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        return url.trimEnd('/').takeIf { runCatching { URL(it).host }.getOrNull() != null }
    }

    /** URL, с которого грузим сайт в текущем режиме. */
    fun effectiveOrigin(): String = when {
        status == Status.CONNECTED_MIRROR -> normalizedMirrorUrl() ?: START_URL
        else -> START_URL
    }

    // --------------------------------------------------------------- Listen

    fun addListener(listener: Listener) { listeners.add(listener) }

    fun removeListener(listener: Listener) { listeners.remove(listener) }

    fun currentState(): State = State(mode, status, statusDetail)

    private fun setStatus(newStatus: Status, detail: String = "") {
        status = newStatus
        statusDetail = detail
        val state = State(mode, newStatus, detail)
        mainHandler.post { listeners.forEach { it.onStateChanged(state) } }
    }

    // -------------------------------------------------------------- Lifecycle

    /** Вызывается при старте приложения — применяет сохранённый режим. */
    fun setup() {
        when (mode) {
            Mode.AUTO -> {
                clearProxy()
                setStatus(Status.CONNECTED_DIRECT)
            }
            Mode.DIRECT -> {
                clearProxy()
                setStatus(Status.CONNECTED_DIRECT)
            }
            Mode.MIRROR -> {
                clearProxy()
                if (isMirrorConfigured) {
                    setStatus(Status.CONNECTED_MIRROR, normalizedMirrorUrl()!!)
                } else {
                    setStatus(Status.FAILED)
                }
            }
            Mode.PROXY -> connectViaPool()
            Mode.VPN -> {
                clearProxy()
                if (ArenaVpnService.isRunning()) {
                    setStatus(Status.CONNECTED_VPN, "WARP")
                } else {
                    setStatus(Status.CHECKING)
                }
            }
        }
    }

    /** Переключение режима пользователем. */
    fun changeMode(newMode: Mode) {
        mode = newMode
        when (newMode) {
            Mode.AUTO -> {
                clearProxy()
                setStatus(Status.CONNECTED_DIRECT)
            }
            Mode.DIRECT -> {
                clearProxy()
                setStatus(Status.CONNECTED_DIRECT)
            }
            Mode.MIRROR -> {
                clearProxy()
                val mirror = normalizedMirrorUrl()
                if (mirror != null) {
                    setStatus(Status.CONNECTED_MIRROR, mirror)
                } else {
                    setStatus(Status.FAILED)
                }
            }
            Mode.PROXY -> connectViaPool()
            Mode.VPN -> {
                clearProxy()
                if (ArenaVpnService.isRunning()) {
                    setStatus(Status.CONNECTED_VPN, "WARP")
                } else {
                    setStatus(Status.CHECKING)
                }
            }
        }
    }

    /** Авто-эскалация: stage 1 — зеркало, 2 — пул прокси, 3 — VPN. */
    fun applyAutoStage(stage: Int, load: (String) -> Unit) {
        when (stage) {
            1 -> {
                val mirror = normalizedMirrorUrl()
                if (mirrorEnabled && mirror != null) {
                    setStatus(Status.CONNECTED_MIRROR, mirror)
                    mainHandler.post { load(mirror) }
                } else {
                    setStatus(Status.FAILED)
                }
            }
            2 -> connectViaPool { load(effectiveOrigin()) }
            3 -> {
                // VPN запускает активность (нужен системный диалог согласия)
                setStatus(Status.CHECKING)
            }
        }
    }

    // ------------------------------------------------------- Automatic switch

    /**
     * Автоматическое восстановление доступа: вызывается при ошибке загрузки
     * главной страницы. [load] будет вызван на главном потоке с URL,
     * который нужно загрузить.
     */
    fun onMainFrameBlocked(load: (String) -> Unit) {
        if (busy.get()) return
        when (mode) {
            Mode.DIRECT -> {
                setStatus(Status.FAILED)
                load(START_URL) // не переключаем — пользователь выбрал «напрямую»
            }
            Mode.MIRROR -> {
                val mirror = normalizedMirrorUrl()
                if (mirror != null) {
                    setStatus(Status.CONNECTED_MIRROR, mirror)
                    mainHandler.post { load(mirror) }
                } else {
                    setStatus(Status.FAILED)
                }
            }
            Mode.AUTO -> {
                val mirror = normalizedMirrorUrl()
                if (mirrorEnabled && mirror != null) {
                    // Зеркало надёжнее пула прокси — используем его первым.
                    setStatus(Status.CONNECTED_MIRROR, mirror)
                    mainHandler.post { load(mirror) }
                } else {
                    connectViaPool { load(effectiveOrigin()) }
                }
            }
            Mode.PROXY -> {
                // Текущий прокси не работает — пробуем следующий из пула.
                connectViaPool { load(effectiveOrigin()) }
            }
            Mode.VPN -> {
                // VPN не поднялся — статус уже FAILED; перезапуск делает активность.
                setStatus(Status.FAILED)
            }
        }
    }

    /** Применить следующий прокси из пула (кнопка «Повторить»). */
    fun tryNext(onReady: (() -> Unit)? = null) {
        if (candidates.isEmpty()) {
            connectViaPool(onReady)
            return
        }
        candidateIndex = (candidateIndex + 1) % candidates.size
        val candidate = candidates[candidateIndex]
        executor.execute {
            if (applyProxy(candidate)) {
                setStatus(Status.CONNECTED_PROXY, candidate.address)
            } else {
                setStatus(Status.FAILED)
            }
            mainHandler.post { onReady?.invoke() }
        }
    }

    // ------------------------------------------------------------ Proxy pool

    /**
     * Подключение через пул: использует кэш живых прокси или
     * скачивает и проверяет свежий список.
     */
    fun connectViaPool(onReady: (() -> Unit)? = null) {
        if (!busy.compareAndSet(false, true)) {
            mainHandler.post { onReady?.invoke() }
            return
        }
        setStatus(Status.CHECKING)
        executor.execute {
            try {
                if (candidates.isEmpty()) {
                    candidates = cachedCandidates()
                }
                if (candidates.isEmpty()) {
                    mainHandler.post {
                        listeners.forEach { it.onStateChanged(State(mode, Status.CHECKING, "")) }
                    }
                    candidates = fetchAndTest()
                }
                if (candidates.isEmpty()) {
                    setStatus(Status.FAILED)
                    return@execute
                }
                candidateIndex = 0
                val applied = applyProxy(candidates[0])
                if (applied) {
                    setStatus(Status.CONNECTED_PROXY, candidates[0].address)
                } else {
                    setStatus(Status.FAILED)
                }
                mainHandler.post { onReady?.invoke() }
            } catch (t: Throwable) {
                Log.w(TAG, "connectViaPool failed", t)
                setStatus(Status.FAILED)
                mainHandler.post { onReady?.invoke() }
            } finally {
                busy.set(false)
            }
        }
    }

    // ------------------------------------------------------------ SOCKS pool

    /** Пул рабочих SOCKS5 для VPN (кэш или загрузка+проверка). */
    fun getSocksPool(): List<Candidate> {
        val cached = cachedSocks()
        if (cached.isNotEmpty()) return cached
        return fetchAndTestSocks()
    }

    /** Обновить SOCKS-пул в фоне. */
    fun refreshSocksPool(onDone: ((List<Candidate>) -> Unit)? = null) {
        executor.execute {
            val list = try {
                fetchAndTestSocks()
            } catch (t: Throwable) {
                Log.w(TAG, "refreshSocksPool failed", t)
                emptyList()
            }
            onDone?.let { mainHandler.post { it(list) } }
        }
    }

    private fun cachedSocks(): List<Candidate> {
        val age = System.currentTimeMillis() - prefs.getLong(KEY_SOCKS_CACHE_TIME, 0L)
        if (age > SOCKS_CACHE_TTL_MS) return emptyList()
        return prefs.getString(KEY_SOCKS_CACHE, "").orEmpty()
            .split("\n")
            .mapNotNull { Candidate.parse(it) }
            .takeIf { it.isNotEmpty() } ?: emptyList()
    }

    private fun saveSocks(list: List<Candidate>) {
        if (list.isEmpty()) return
        prefs.edit()
            .putString(KEY_SOCKS_CACHE, list.take(12).joinToString("\n") { it.address })
            .putLong(KEY_SOCKS_CACHE_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun fetchAndTestSocks(): List<Candidate> {
        val fetched = fetchCandidatesFrom(SOCKS_SOURCES)
        if (fetched.isEmpty()) return emptyList()
        val alive = testSocks(fetched)
        saveSocks(alive)
        return alive
    }

    /**
     * Проверка SOCKS5: TCP до прокси + SOCKS5-хендшейк + CONNECT arena.ai:443
     * + TLS-хендшейк через туннель. Остаются только реально рабочие.
     */
    private fun testSocks(list: List<Candidate>, limit: Int = 5): List<Candidate> {
        val results = java.util.Collections.synchronizedList(mutableListOf<Candidate>())
        val tested = list.take(TEST_BATCH)
        val latch = CountDownLatch(tested.size)
        val pool = Executors.newFixedThreadPool(HEALTH_THREADS)
        try {
            for (candidate in tested) {
                pool.execute {
                    try {
                        val latency = checkSocks(candidate)
                        if (latency > 0) results += candidate.copy(latencyMs = latency)
                    } catch (_: Throwable) {
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(12, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        return results.sortedWith(Comparator { a, b -> a.latencyMs.compareTo(b.latencyMs) })
            .take(limit)
    }

    private fun checkSocks(candidate: Candidate): Long {
        val started = System.currentTimeMillis()
        val client = Socks5Client(candidate.host, candidate.port)
        val socket = client.connect(ARENA_HOST, 443, 5000) ?: return -1L
        return try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val ssl = sslContext.socketFactory
                .createSocket(socket, ARENA_HOST, 443, true) as SSLSocket
            ssl.soTimeout = 5000
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(ARENA_HOST))
            ssl.sslParameters = params
            ssl.startHandshake()
            ssl.close()
            System.currentTimeMillis() - started
        } catch (_: Throwable) {
            -1L
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Скачать свежий пул и проверить его (для UI-кнопки «Проверить прокси»). */
    fun refreshPool(onDone: ((workingCount: Int) -> Unit)? = null) {
        executor.execute {
            val count = try {
                fetchAndTest().size
            } catch (t: Throwable) {
                Log.w(TAG, "refreshPool failed", t)
                0
            }
            onDone?.let { mainHandler.post { it(count) } }
        }
    }

    /** Число живых прокси в кэше (для UI). */
    fun cachedCandidateCount(): Int = cachedCandidates().size

    private fun cachedCandidates(): List<Candidate> {
        val age = System.currentTimeMillis() - prefs.getLong(KEY_PROXY_CACHE_TIME, 0L)
        if (age > CACHE_TTL_MS) return emptyList()
        return prefs.getString(KEY_PROXY_CACHE, "").orEmpty()
            .split("\n")
            .mapNotNull { Candidate.parse(it) }
            .takeIf { it.isNotEmpty() } ?: emptyList()
    }

    private fun saveCandidates(list: List<Candidate>) {
        if (list.isEmpty()) return
        prefs.edit()
            .putString(KEY_PROXY_CACHE, list.take(10).joinToString("\n") { it.address })
            .putLong(KEY_PROXY_CACHE_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun fetchAndTest(): List<Candidate> {
        val fetched = fetchCandidates()
        if (fetched.isEmpty()) return emptyList()
        val alive = testProxies(fetched)
        saveCandidates(alive)
        return alive
    }

    private fun fetchCandidates(): List<Candidate> = fetchCandidatesFrom(PROXY_SOURCES)

    private fun fetchCandidatesFrom(sources: List<String>): List<Candidate> {
        val seen = LinkedHashSet<String>()
        for (source in sources) {
            try {
                val conn = URL(source).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
                )
                val body = conn.inputStream.bufferedReader().use { reader -> reader.readText() }
                parseProxySource(body).forEach { seen += it.address }
            } catch (e: Exception) {
                Log.w(TAG, "Proxy source failed: $source (${e.message})")
            }
            if (seen.size >= MAX_CANDIDATES) break
        }
        return seen.mapNotNull { Candidate.parse(it) }.take(MAX_CANDIDATES)
    }

    private fun parseProxySource(body: String): List<Candidate> {
        // Geonode и подобные: {"data":[{"ip":"1.2.3.4","port":"8080",...}]}
        // Парсим без org.json (его нет в оффлайн-варианте android.jar).
        if (body.contains("\"data\"")) {
            val result = mutableListOf<Candidate>()
            val pair = Pattern.compile("\"ip\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"port\"\\s*:\\s*\"(\\d+)\"")
            val m = pair.matcher(body)
            while (m.find()) {
                Candidate.parse("${m.group(1)}:${m.group(2)}")?.let { result += it }
            }
            return result
        }
        return body.lines().mapNotNull { Candidate.parse(it) }
    }

    /** Параллельная проверка: CONNECT-туннель + TLS-хендшейк к arena.ai. */
    private fun testProxies(list: List<Candidate>, limit: Int = 5): List<Candidate> {
        val results = java.util.Collections.synchronizedList(mutableListOf<Candidate>())
        val tested = list.take(TEST_BATCH)
        val latch = CountDownLatch(tested.size)
        val pool = Executors.newFixedThreadPool(HEALTH_THREADS)
        try {
            for (candidate in tested) {
                pool.execute {
                    try {
                        val latency = checkProxy(candidate)
                        if (latency > 0) results += candidate.copy(latencyMs = latency)
                    } catch (_: Throwable) {
                    } finally {
                        latch.countDown()
                    }
                }
            }
            latch.await(9, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        return results.sortedWith(Comparator { a, b -> a.latencyMs.compareTo(b.latencyMs) })
            .take(limit)
    }

    private fun checkProxy(candidate: Candidate): Long {
        val started = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(candidate.host, candidate.port), 4000)
            socket.soTimeout = 4000
            val out = socket.getOutputStream()
            out.write("CONNECT $ARENA_HOST:443 HTTP/1.1\r\nHost: $ARENA_HOST:443\r\n\r\n".toByteArray())
            out.flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val statusLine = reader.readLine() ?: return -1L
            if (!statusLine.contains("200")) return -1L
            // Читаем заголовки до пустой строки
            while (true) {
                val line = reader.readLine() ?: return -1L
                if (line.isEmpty()) break
            }
            // TLS-хендшейк через туннель — доказывает, что прокси выпускает трафик
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val ssl = sslContext.socketFactory
                .createSocket(socket, ARENA_HOST, 443, true) as SSLSocket
            ssl.soTimeout = 4000
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(ARENA_HOST))
            ssl.sslParameters = params
            ssl.startHandshake()
            ssl.close()
            return System.currentTimeMillis() - started
        } catch (_: Throwable) {
            return -1L
        } finally {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------- Proxy API

    private fun applyProxy(candidate: Candidate): Boolean {
        val applied = when {
            // Основной путь: android.webkit.ProxyController (то, что использует
            // androidx.webkit). Работает на Chromium-WebView (Android 8+).
            applyViaWebViewFactory(candidate) -> true
            // Запасной путь: системный android.net.ProxyController (Android 8–9,
            // где нет скрытого WebView API).
            applyViaSystemProxy(candidate) -> true
            else -> false
        }
        if (applied) {
            proxyApplied = true
            appliedProxy = candidate
        }
        return applied
    }

    private fun clearProxy() {
        if (!proxyApplied && appliedProxy == null) return
        try {
            if (!clearViaWebViewFactory()) {
                clearViaSystemProxy()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "clearProxy failed", t)
        }
        proxyApplied = false
        appliedProxy = null
    }

    /**
     * WebView-прокси: WebViewFactory.getProvider().getProxyController() —
     * тот же вызов, что делает androidx.webkit.ProxyController.
     */
    private fun applyViaWebViewFactory(candidate: Candidate): Boolean = try {
        val wvFactory = Class.forName("android.webkit.WebViewFactory")
        val provider = wvFactory.getMethod("getProvider").invoke(null)
        val proxyController = provider.javaClass.getMethod("getProxyController").invoke(provider)
        val proxyRules = arrayOf(arrayOf("*", "http://${candidate.address}"))
        val setMethod = proxyController.javaClass.getMethod(
            "setProxyOverride",
            Array<Array<String>>::class.java,
            Array<String>::class.java,
            Runnable::class.java,
            Executor::class.java
        )
        setMethod.invoke(proxyController, proxyRules, emptyArray<String>(), Runnable { }, executor)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "WebViewFactory proxy failed", t)
        false
    }

    private fun clearViaWebViewFactory(): Boolean = try {
        val wvFactory = Class.forName("android.webkit.WebViewFactory")
        val provider = wvFactory.getMethod("getProvider").invoke(null)
        val proxyController = provider.javaClass.getMethod("getProxyController").invoke(provider)
        val clearMethod = proxyController.javaClass.getMethod(
            "clearProxyOverride", Runnable::class.java, Executor::class.java
        )
        clearMethod.invoke(proxyController, Runnable { }, executor)
        true
    } catch (t: Throwable) {
        false
    }

    /** Системный прокси: android.net.ProxyController (работает на Android 8–9). */
    private fun applyViaSystemProxy(candidate: Candidate): Boolean = try {
        val controllerClass = Class.forName("android.net.ProxyController")
        val controller = controllerClass.getMethod("getInstance").invoke(null)
        val infoClass = Class.forName("android.net.ProxyInfo")
        val info = infoClass
            .getMethod("buildDirectProxy", String::class.java, Int::class.javaPrimitiveType)
            .invoke(null, candidate.host, candidate.port)
        controllerClass.getMethod("setProxyOverride", infoClass).invoke(controller, info)
        true
    } catch (t: Throwable) {
        Log.w(TAG, "system ProxyController failed", t)
        false
    }

    private fun clearViaSystemProxy() {
        try {
            val controllerClass = Class.forName("android.net.ProxyController")
            val controller = controllerClass.getMethod("getInstance").invoke(null)
            controllerClass.getMethod("clearProxyOverride").invoke(controller)
        } catch (t: Throwable) {
            Log.w(TAG, "legacy clearProxyOverride failed", t)
        }
    }

    // -------------------------------------------------------------- Candidate

    data class Candidate(val host: String, val port: Int, val latencyMs: Long = 0L) {
        val address: String get() = "$host:$port"

        companion object {
            fun parse(line: String): Candidate? {
                val clean = line.trim().substringBefore('#').substringBefore(',')
                if (clean.isEmpty()) return null
                val parts = clean.split(":")
                if (parts.size != 2) return null
                val host = parts[0].trim()
                val port = parts[1].trim().toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                return Candidate(host, port)
            }
        }
    }
}
