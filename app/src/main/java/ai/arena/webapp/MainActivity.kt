package ai.arena.webapp

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity(), ArenaWebChromeClient.Host, ProxyManager.Listener {

    companion object {
        private const val REQUEST_FILE_CHOOSER = 1001
        private const val REQUEST_WEB_PERMISSIONS = 1002
        private const val KEY_WEBVIEW_STATE = "webview_state"
        private const val MAX_AUTO_ATTEMPTS = 2
        private const val LOAD_TIMEOUT_MS = 20_000L
    }

    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var offlineView: View
    private lateinit var fullscreenContainer: FrameLayout

    private lateinit var progressBar: View
    private lateinit var chip: LinearLayout
    private lateinit var chipIcon: ImageView
    private lateinit var chipText: TextView

    private lateinit var bottomBar: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton

    private lateinit var offlineHint: TextView

    private lateinit var sheetRoot: FrameLayout
    private lateinit var sheetScrim: View
    private lateinit var sheetPanel: ScrollView
    private lateinit var sheetStatus: TextView
    private lateinit var proxyStatusText: TextView
    private lateinit var btnTestProxies: Button
    private lateinit var mirrorInput: EditText

    private lateinit var proxyManager: ProxyManager

    private var filePathCallback: ValueCallback? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var progressAnimator: ObjectAnimator? = null
    private var sheetOpen = false
    private var barVisible = true
    private var autoAttempts = 0
    private var lastBlockedAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadTimeoutRunnable = Runnable { handleMainFrameBlocked() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpEdgeToEdge()
        setContentView(R.layout.activity_main)

        proxyManager = ProxyManager.get(this)

        bindViews()
        applyEdgeToEdgeInsets()
        setUpWebView()
        setUpBottomBar()
        setUpChip()
        setUpSheet()
        setUpOffline()

        proxyManager.addListener(this)
        proxyManager.setup()

        val webViewState = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE)
        if (webViewState != null) {
            webView.restoreState(webViewState)
            handlePageFinished()
        } else if (proxyManager.mode == ProxyManager.Mode.PROXY) {
            // В режиме «Прокси» ждём подключения пула перед первой загрузкой.
            proxyManager.connectViaPool { loadOrigin(ProxyManager.START_URL) }
        } else {
            loadOrigin(proxyManager.effectiveOrigin())
        }
    }

    private fun setUpEdgeToEdge() {
        // Контент под системными панелями; отступы навешивает insets-слушатель.
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.root) as FrameLayout
        webView = findViewById(R.id.web_view) as WebView
        offlineView = findViewById(R.id.offline_view) as View
        fullscreenContainer = findViewById(R.id.fullscreen_container) as FrameLayout

        progressBar = findViewById(R.id.progress_bar) as View
        chip = findViewById(R.id.connection_chip) as LinearLayout
        chipIcon = findViewById(R.id.chip_icon) as ImageView
        chipText = findViewById(R.id.chip_text) as TextView

        bottomBar = findViewById(R.id.bottom_bar) as LinearLayout
        btnBack = findViewById(R.id.btn_back) as ImageButton
        btnForward = findViewById(R.id.btn_forward) as ImageButton

        offlineHint = findViewById(R.id.offline_hint) as TextView

        sheetRoot = findViewById(R.id.sheet_root) as FrameLayout
        sheetScrim = findViewById(R.id.sheet_scrim) as View
        sheetPanel = findViewById(R.id.sheet_panel) as ScrollView
        sheetStatus = findViewById(R.id.sheet_status) as TextView
        proxyStatusText = findViewById(R.id.proxy_status_text) as TextView
        btnTestProxies = findViewById(R.id.btn_test_proxies) as Button
        mirrorInput = findViewById(R.id.mirror_url_input) as EditText
    }

    @SuppressWarnings("deprecation")
    private fun applyEdgeToEdgeInsets() {
        rootLayout.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
            insets
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setUpWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            mediaPlaybackRequiresUserGesture = false
        }
        applyDarkMode()

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = ArenaWebViewClient(
            onPageStarted = { startProgress() },
            onPageFinished = { handlePageFinished() },
            onMainFrameError = { handleMainFrameBlocked() },
            onMainFrameHttpError = { code ->
                if (code == 403 || code == 451 || code == 503) handleMainFrameBlocked()
            },
            isMirrorActive = {
                proxyManager.currentState().status == ProxyManager.Status.CONNECTED_MIRROR
            },
            mirrorHost = {
                proxyManager.normalizedMirrorUrl()?.let {
                    runCatching { Uri.parse(it).host }.getOrNull()
                }
            },
            openExternal = { uri -> openExternal(uri) }
        )
        webView.webChromeClient = ArenaWebChromeClient(this)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
        }
        webView.setOnScrollChangeListener { _, _, _, newY, oldY ->
            val delta = newY - oldY
            if (delta > 14) hideBottomBar()
            else if (delta < -14) showBottomBar()
        }
    }

    private fun applyDarkMode() {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNight = nightMode == Configuration.UI_MODE_NIGHT_YES
        if (Build.VERSION.SDK_INT >= 29) {
            @Suppress("DEPRECATION")
            webView.settings.forceDark =
                if (isNight) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_AUTO
        }
    }

    // --------------------------------------------------------- Loading / Auto

    private fun loadOrigin(origin: String) {
        hideOffline()
        startProgress()
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        mainHandler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
        if (webView.url.isNullOrEmpty()) {
            webView.loadUrl(origin)
        } else if (webView.url != origin) {
            webView.loadUrl(origin)
        } else {
            webView.reload()
        }
    }

    private fun handlePageFinished() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        stopProgress()
        autoAttempts = 0
        hideOffline()
        refreshNavButtons()
    }

    private fun handleMainFrameBlocked() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        stopProgress()

        val now = System.currentTimeMillis()
        if (now - lastBlockedAt < 2_000L) return
        lastBlockedAt = now

        if (!isOnline()) {
            showOffline()
            return
        }
        if (proxyManager.mode == ProxyManager.Mode.DIRECT) {
            showOffline()
            return
        }
        if (autoAttempts >= MAX_AUTO_ATTEMPTS) {
            showOffline()
            return
        }
        autoAttempts++
        proxyManager.onMainFrameBlocked { origin -> loadOrigin(origin) }
    }

    private fun retry() {
        if (!isOnline()) {
            Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
            return
        }
        hideOffline()
        autoAttempts = 0
        when (proxyManager.mode) {
            ProxyManager.Mode.DIRECT -> loadOrigin(ProxyManager.START_URL)
            ProxyManager.Mode.MIRROR -> {
                val mirror = proxyManager.normalizedMirrorUrl()
                if (mirror != null) {
                    loadOrigin(mirror)
                } else {
                    openSheet()
                    showOffline()
                }
            }
            ProxyManager.Mode.PROXY -> proxyManager.tryNext {
                loadOrigin(ProxyManager.START_URL)
            }
            ProxyManager.Mode.AUTO -> proxyManager.onMainFrameBlocked { origin ->
                loadOrigin(origin)
            }
        }
    }

    // ------------------------------------------------------------- Progress bar

    private fun startProgress() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        mainHandler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
        progressBar.alpha = 1f
        progressAnimator?.cancel()
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val barWidth = progressBar.width.takeIf { it > 0 }?.toFloat()
            ?: (120 * resources.displayMetrics.density)
        progressAnimator = ObjectAnimator.ofFloat(
            progressBar, View.TRANSLATION_X, -barWidth, screenWidth
        ).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopProgress() {
        progressAnimator?.cancel()
        progressAnimator = null
        progressBar.animate().alpha(0f).setDuration(200).start()
    }

    // ---------------------------------------------------------------- Bottom bar

    private fun setUpBottomBar() {
        (findViewById(R.id.btn_back) as ImageButton).setOnClickListener {
            haptic()
            if (webView.canGoBack()) webView.goBack()
        }
        (findViewById(R.id.btn_refresh) as ImageButton).setOnClickListener {
            haptic()
            loadOrigin(proxyManager.effectiveOrigin())
        }
        btnForward.setOnClickListener {
            haptic()
            if (webView.canGoForward()) webView.goForward()
        }
        (findViewById(R.id.btn_external) as ImageButton).setOnClickListener {
            haptic()
            val url = webView.url ?: proxyManager.effectiveOrigin()
            openExternal(Uri.parse(url))
        }
        refreshNavButtons()
    }

    private fun refreshNavButtons() {
        btnBack.isEnabled = webView.canGoBack()
        btnForward.isEnabled = webView.canGoForward()
        btnBack.alpha = if (btnBack.isEnabled) 1f else 0.35f
        btnForward.alpha = if (btnForward.isEnabled) 1f else 0.35f
    }

    private fun refreshBottomBar() {
        val offset = (if (bottomBar.height > 0) bottomBar.height.toFloat()
        else 96f * resources.displayMetrics.density) + 40f
        bottomBar.animate()
            .translationY(if (barVisible) 0f else offset)
            .alpha(if (barVisible) 1f else 0f)
            .setDuration(180)
            .start()
    }

    private fun showBottomBar() {
        if (barVisible) return
        barVisible = true
        refreshBottomBar()
    }

    private fun hideBottomBar() {
        if (!barVisible) return
        barVisible = false
        refreshBottomBar()
    }

    private fun haptic() {
        rootLayout.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    // -------------------------------------------------------------------- Chip

    private fun setUpChip() {
        chip.setOnClickListener {
            haptic()
            openSheet()
        }
    }

    // ------------------------------------------------------------------- Sheet

    private fun setUpSheet() {
        sheetScrim.setOnClickListener { closeSheet() }

        (findViewById(R.id.row_mode_auto) as View).setOnClickListener {
            haptic(); selectMode(ProxyManager.Mode.AUTO)
        }
        (findViewById(R.id.row_mode_direct) as View).setOnClickListener {
            haptic(); selectMode(ProxyManager.Mode.DIRECT)
        }
        (findViewById(R.id.row_mode_mirror) as View).setOnClickListener {
            haptic(); selectMode(ProxyManager.Mode.MIRROR)
        }
        (findViewById(R.id.row_mode_proxy) as View).setOnClickListener {
            haptic(); selectMode(ProxyManager.Mode.PROXY)
        }

        (findViewById(R.id.btn_save_mirror) as Button).setOnClickListener {
            saveMirror()
        }

        btnTestProxies.setOnClickListener {
            haptic()
            btnTestProxies.isEnabled = false
            proxyStatusText.text = getString(R.string.proxy_testing)
            proxyManager.refreshPool { count ->
                btnTestProxies.isEnabled = true
                proxyStatusText.text = if (count > 0) {
                    getString(R.string.proxy_test_result, count)
                } else {
                    getString(R.string.proxy_test_none)
                }
            }
        }
    }

    private fun selectMode(newMode: ProxyManager.Mode) {
        if (newMode == ProxyManager.Mode.MIRROR && !proxyManager.isMirrorConfigured) {
            Toast.makeText(this, R.string.mirror_empty, Toast.LENGTH_SHORT).show()
            mirrorInput.requestFocus()
            return
        }
        if (proxyManager.mode == newMode && newMode != ProxyManager.Mode.PROXY) {
            updateSheetChecks()
            return
        }
        proxyManager.changeMode(newMode)
        updateSheetChecks()
        // Перезагружаем сайт с новым способом подключения
        when (newMode) {
            ProxyManager.Mode.MIRROR ->
                proxyManager.normalizedMirrorUrl()?.let { loadOrigin(it) }
            ProxyManager.Mode.PROXY ->
                proxyManager.connectViaPool { loadOrigin(ProxyManager.START_URL) }
            else -> loadOrigin(ProxyManager.START_URL)
        }
    }

    private fun saveMirror() {
        val raw = mirrorInput.text.toString().trim()
        if (raw.isEmpty()) {
            Toast.makeText(this, R.string.mirror_empty, Toast.LENGTH_SHORT).show()
            return
        }
        proxyManager.mirrorUrl = raw
        val normalized = proxyManager.normalizedMirrorUrl()
        if (normalized == null) {
            Toast.makeText(this, R.string.mirror_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.mirror_saved, Toast.LENGTH_SHORT).show()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(mirrorInput.windowToken, 0)
        if (proxyManager.mode == ProxyManager.Mode.MIRROR) {
            proxyManager.changeMode(ProxyManager.Mode.MIRROR)
            loadOrigin(normalized)
        }
        updateSheetUi()
    }

    private fun openSheet() {
        if (sheetOpen) return
        sheetOpen = true
        updateSheetUi()
        sheetRoot.visibility = View.VISIBLE
        sheetScrim.animate().alpha(1f).setDuration(200).start()
        sheetPanel.post {
            sheetPanel.translationY = sheetPanel.height.toFloat()
            sheetPanel.animate()
                .translationY(0f)
                .setDuration(280)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        sheetScrim.animate().alpha(0f).setDuration(200).start()
        sheetPanel.animate()
            .translationY(sheetPanel.height.toFloat())
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                sheetRoot.visibility = View.GONE
                sheetPanel.translationY = 0f
            }
            .start()
    }

    private fun updateSheetChecks() {
        val mode = proxyManager.mode
        (findViewById(R.id.check_auto) as View).visibility =
            if (mode == ProxyManager.Mode.AUTO) View.VISIBLE else View.INVISIBLE
        (findViewById(R.id.check_direct) as View).visibility =
            if (mode == ProxyManager.Mode.DIRECT) View.VISIBLE else View.INVISIBLE
        (findViewById(R.id.check_mirror) as View).visibility =
            if (mode == ProxyManager.Mode.MIRROR) View.VISIBLE else View.INVISIBLE
        (findViewById(R.id.check_proxy) as View).visibility =
            if (mode == ProxyManager.Mode.PROXY) View.VISIBLE else View.INVISIBLE
    }

    private fun updateSheetUi() {
        updateSheetChecks()
        val state = proxyManager.currentState()
        sheetStatus.text = statusText(state)
        mirrorInput.setText(proxyManager.mirrorUrl)
        proxyStatusText.text = getString(R.string.proxy_test)
        val cached = proxyManager.cachedCandidateCount()
        if (cached > 0) {
            proxyStatusText.text = getString(R.string.proxy_test_result, cached)
        }
    }

    // ------------------------------------------------------------ ProxyManager.Listener

    override fun onStateChanged(state: ProxyManager.State) {
        chipText.text = chipLabel(state)
        val colorRes = when (state.status) {
            ProxyManager.Status.CONNECTED_DIRECT -> R.color.brand
            ProxyManager.Status.CONNECTED_MIRROR -> R.color.brand_cyan
            ProxyManager.Status.CONNECTED_PROXY -> R.color.status_ok
            ProxyManager.Status.CHECKING -> R.color.status_warn
            ProxyManager.Status.FAILED -> R.color.status_err
            ProxyManager.Status.IDLE -> R.color.brand
        }
        chipIcon.setColorFilter(getColor(colorRes))
        if (sheetOpen) {
            sheetStatus.text = statusText(state)
        }
        val visible = state.status != ProxyManager.Status.IDLE
        chip.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun chipLabel(state: ProxyManager.State): String = when (state.status) {
        ProxyManager.Status.CONNECTED_DIRECT -> getString(R.string.mode_direct)
        ProxyManager.Status.CONNECTED_MIRROR -> getString(R.string.mode_mirror)
        ProxyManager.Status.CONNECTED_PROXY ->
            getString(R.string.status_connected_proxy, state.detail)
        ProxyManager.Status.CHECKING -> getString(R.string.status_connecting)
        ProxyManager.Status.FAILED -> getString(R.string.status_failed)
        ProxyManager.Status.IDLE -> ""
    }

    private fun statusText(state: ProxyManager.State): String = when (state.status) {
        ProxyManager.Status.CONNECTED_DIRECT -> getString(R.string.status_connected_direct)
        ProxyManager.Status.CONNECTED_MIRROR -> getString(R.string.status_connected_mirror)
        ProxyManager.Status.CONNECTED_PROXY ->
            getString(R.string.status_connected_proxy, state.detail)
        ProxyManager.Status.CHECKING -> getString(R.string.status_checking_proxy)
        ProxyManager.Status.FAILED -> getString(R.string.status_failed)
        ProxyManager.Status.IDLE -> getString(R.string.status_idle)
    }

    // ------------------------------------------------------------------ Offline

    private fun setUpOffline() {
        (findViewById(R.id.retry_button) as Button).setOnClickListener {
            haptic()
            retry()
        }
        (findViewById(R.id.open_connection_button) as Button).setOnClickListener {
            haptic()
            openSheet()
        }
    }

    private fun showOffline() {
        offlineHint.visibility = if (isOnline()) View.VISIBLE else View.GONE
        offlineView.visibility = View.VISIBLE
    }

    private fun hideOffline() {
        offlineView.visibility = View.GONE
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    // ----------------------------------------------------------------- Downloads

    private fun startDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                if (userAgent != null) addRequestHeader("User-Agent", userAgent)
                setTitle(fileName)
                setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(
                this, getString(R.string.download_started, fileName), Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            openExternal(Uri.parse(url))
        }
    }

    // ------------------------------------------------- ArenaWebChromeClient.Host

    override fun onShowFileChooser(
        callback: ValueCallback,
        params: WebChromeClient.FileChooserParams
    ): Boolean {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = callback
        return try {
            val intent = params.createIntent()
            startActivityForResult(
                Intent.createChooser(intent, getString(R.string.choose_file)),
                REQUEST_FILE_CHOOSER
            )
            true
        } catch (e: ActivityNotFoundException) {
            filePathCallback = null
            Toast.makeText(this, R.string.no_file_picker, Toast.LENGTH_SHORT).show()
            false
        }
    }

    override fun onPermissionRequested(request: PermissionRequest) {
        val needed = mutableSetOf<String>()
        request.resources.forEach { resource ->
            when (resource) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                    needed += Manifest.permission.RECORD_AUDIO
                PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                    needed += Manifest.permission.CAMERA
            }
        }
        if (needed.isEmpty()) {
            request.deny()
            return
        }
        val missing = needed.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            request.grant(request.resources)
        } else {
            pendingPermissionRequest = request
            requestPermissions(missing.toTypedArray(), REQUEST_WEB_PERMISSIONS)
        }
    }

    override fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        fullscreenContainer.visibility = View.VISIBLE
        bottomBar.visibility = View.GONE
        chip.visibility = View.GONE
    }

    override fun onExitFullscreen() {
        if (customView == null) return
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        bottomBar.visibility = View.VISIBLE
        chip.visibility = View.VISIBLE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    // ------------------------------------------------------------ System callbacks

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE_CHOOSER) {
            filePathCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data)
            )
            filePathCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_WEB_PERMISSIONS) {
            val request = pendingPermissionRequest ?: return
            pendingPermissionRequest = null
            val granted = request.resources.filter { resource ->
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        checkSelfPermission(Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED
                    else -> false
                }
            }
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            sheetOpen -> closeSheet()
            customView != null -> onExitFullscreen()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = Bundle()
        webView.saveState(state)
        outState.putBundle(KEY_WEBVIEW_STATE, state)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        proxyManager.removeListener(this)
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
