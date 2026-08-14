package ai.arena.webapp

import ai.arena.webapp.assistant.AiBackend
import ai.arena.webapp.assistant.AssistantEngine
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
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), ArenaWebChromeClient.Host {

    companion object {
        private const val START_URL = "https://arena.ai"
        private const val REQUEST_FILE_CHOOSER = 1001
        private const val REQUEST_WEB_PERMISSIONS = 1002
        private const val KEY_WEBVIEW_STATE = "webview_state"
        private const val LOAD_TIMEOUT_MS = 20_000L
    }

    // Панели и навигация
    private lateinit var rootLayout: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var btnNavBack: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var progressBar: View

    // Контент
    private lateinit var webView: WebView
    private lateinit var offlineView: ScrollView
    private lateinit var fullscreenContainer: FrameLayout

    // AI-помощник
    private lateinit var sheetRoot: FrameLayout
    private lateinit var sheetScrim: View
    private lateinit var sheetPanel: LinearLayout
    private lateinit var sheetHeader: LinearLayout
    private lateinit var chipsContainer: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var assistantInput: EditText

    private lateinit var engine: AssistantEngine
    private var typingView: LinearLayout? = null

    private var filePathCallback: ValueCallback? = null
    private var pendingPermissionRequest: PermissionRequest? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private var progressAnimator: ObjectAnimator? = null
    private var sheetOpen = false
    private var greetingShown = false
    private var pageLoadedOnce = false

    // Drag-to-dismiss шторки
    private var dragStartY = 0f
    private var dragging = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadTimeoutRunnable = Runnable { handleMainFrameBlocked() }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setUpEdgeToEdge()
        setContentView(R.layout.activity_main)

        engine = AssistantEngine(Locale.getDefault())

        bindViews()
        applyEdgeToEdgeInsets()
        setUpWebView()
        setUpBars()
        setUpSheet()
        setUpOffline()

        val webViewState = savedInstanceState?.getBundle(KEY_WEBVIEW_STATE)
        if (webViewState != null) {
            webView.restoreState(webViewState)
            handlePageFinished()
        } else {
            loadOrigin(START_URL)
        }
    }

    private fun setUpEdgeToEdge() {
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
    }

    private fun bindViews() {
        rootLayout = findViewById(R.id.root) as FrameLayout
        topBar = findViewById(R.id.top_bar) as LinearLayout
        bottomBar = findViewById(R.id.bottom_bar) as LinearLayout
        btnNavBack = findViewById(R.id.btn_nav_back) as ImageButton
        btnBack = findViewById(R.id.btn_back) as ImageButton
        btnForward = findViewById(R.id.btn_forward) as ImageButton
        progressBar = findViewById(R.id.progress_bar) as View

        webView = findViewById(R.id.web_view) as WebView
        offlineView = findViewById(R.id.offline_view) as ScrollView
        fullscreenContainer = findViewById(R.id.fullscreen_container) as FrameLayout

        sheetRoot = findViewById(R.id.sheet_root) as FrameLayout
        sheetScrim = findViewById(R.id.sheet_scrim) as View
        sheetPanel = findViewById(R.id.sheet_panel) as LinearLayout
        sheetHeader = findViewById(R.id.sheet_header) as LinearLayout
        chipsContainer = findViewById(R.id.chips_container) as LinearLayout
        chatScroll = findViewById(R.id.chat_scroll) as ScrollView
        chatContainer = findViewById(R.id.chat_container) as LinearLayout
        assistantInput = findViewById(R.id.assistant_input) as EditText
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
            openExternal = { uri -> openExternal(uri) }
        )
        webView.webChromeClient = ArenaWebChromeClient(this)
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            startDownload(url, userAgent, contentDisposition, mimeType)
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
        pageLoadedOnce = true
        hideOffline()
        refreshNavButtons()
    }

    private fun handleMainFrameBlocked() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        stopProgress()
        // Экран «нет соединения» показываем, если сайт ещё ни разу
        // не загрузился или реально пропала сеть.
        if (!pageLoadedOnce || !isOnline()) {
            showOffline()
        }
    }

    private fun retry() {
        if (!isOnline()) {
            Toast.makeText(this, R.string.still_offline, Toast.LENGTH_SHORT).show()
            return
        }
        hideOffline()
        loadOrigin(START_URL)
    }

    // ------------------------------------------------------------ Progress bar

    private fun startProgress() {
        mainHandler.removeCallbacks(loadTimeoutRunnable)
        mainHandler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
        progressBar.alpha = 1f
        progressAnimator?.cancel()
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val barWidth = progressBar.width.takeIf { it > 0 }?.toFloat()
            ?: (84 * resources.displayMetrics.density)
        progressBar.translationX = -barWidth
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

    // ------------------------------------------------------------------ Bars

    private fun setUpBars() {
        btnNavBack.setOnClickListener {
            haptic()
            if (webView.canGoBack()) webView.goBack()
        }
        btnBack.setOnClickListener {
            haptic()
            if (webView.canGoBack()) webView.goBack()
        }
        btnForward.setOnClickListener {
            haptic()
            if (webView.canGoForward()) webView.goForward()
        }
        (findViewById(R.id.btn_refresh) as ImageButton)?.setOnClickListener {
            haptic()
            loadOrigin(if (webView.url.isNullOrEmpty()) START_URL else webView.url!!)
        }
        (findViewById(R.id.btn_assistant) as ImageButton)?.setOnClickListener {
            haptic()
            openSheet()
        }
        (findViewById(R.id.btn_share) as ImageButton)?.setOnClickListener {
            haptic()
            val url = webView.url ?: START_URL
            openExternal(Uri.parse(url))
        }
        refreshNavButtons()
    }

    private fun refreshNavButtons() {
        val canBack = webView.canGoBack()
        val canForward = webView.canGoForward()

        btnNavBack.visibility = if (canBack) View.VISIBLE else View.INVISIBLE
        btnNavBack.alpha = if (canBack) 1f else 0.3f

        btnBack.isEnabled = canBack
        btnForward.isEnabled = canForward
        btnBack.alpha = if (canBack) 1f else 0.3f
        btnForward.alpha = if (canForward) 1f else 0.3f
    }

    private fun haptic() {
        rootLayout.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    // ---------------------------------------------------------- AI assistant

    private fun setUpSheet() {
        sheetScrim.setOnClickListener { closeSheet() }
        setUpSheetDrag()

        // Быстрые подсказки
        for (chip in engine.chips()) {
            chipsContainer.addView(createChip(chip))
        }

        // Отправка
        (findViewById(R.id.btn_send) as ImageButton)?.setOnClickListener {
            haptic()
            sendUserMessage()
        }
    }

    private fun createChip(label: String): Button {
        val density = resources.displayMetrics.density
        return Button(this).apply {
            text = label
            textSize = 13f
            setTextColor(getColor(R.color.ios_blue))
            isAllCaps = false
            setPadding((14 * density).toInt(), 0, (14 * density).toInt(), 0)
            background = getDrawable(R.drawable.bg_ios_field)
            stateListAnimator = null
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (34 * density).toInt()
            )
            lp.marginEnd = (8 * density).toInt()
            layoutParams = lp
            setOnClickListener {
                haptic()
                val reply = engine.onChip(label)
                addAssistantReply(reply)
            }
        }
    }

    private fun openSheet() {
        if (sheetOpen) return
        sheetOpen = true
        sheetRoot.visibility = View.VISIBLE
        updateSheetHeight()
        sheetScrim.animate().alpha(1f).setDuration(200).start()
        sheetPanel.post {
            sheetPanel.translationY = sheetPanel.height.toFloat()
            sheetPanel.animate()
                .translationY(0f)
                .setDuration(340)
                .setInterpolator(OvershootInterpolator(0.45f))
                .start()
        }
        if (!greetingShown) {
            greetingShown = true
            addAssistantReply(engine.greeting())
        }
        assistantInput.requestFocus()
    }

    private fun closeSheet() {
        if (!sheetOpen) return
        sheetOpen = false
        sheetScrim.animate().alpha(0f).setDuration(200).start()
        sheetPanel.animate()
            .translationY(sheetPanel.height.toFloat())
            .setDuration(240)
            .setInterpolator(AccelerateInterpolator(0.8f))
            .withEndAction {
                sheetRoot.visibility = View.GONE
                sheetPanel.translationY = 0f
            }
            .start()
    }

    private fun updateSheetHeight() {
        sheetPanel.post {
            val maxH = (resources.displayMetrics.heightPixels * 0.85f).toInt()
            val lp = sheetPanel.layoutParams
            if (lp.height != maxH) {
                lp.height = maxH
                sheetPanel.layoutParams = lp
            }
        }
    }

    private fun setUpSheetDrag() {
        sheetHeader.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartY = e.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - dragStartY
                    if (dy > 24f || dragging) {
                        dragging = true
                        sheetPanel.translationY = dy.coerceAtLeast(0f)
                        val f = 1f - dy / (sheetPanel.height + 1).toFloat()
                        sheetScrim.alpha = f.coerceIn(0f, 1f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging && sheetPanel.translationY > sheetPanel.height * 0.25f) {
                        closeSheet()
                    } else {
                        sheetPanel.animate()
                            .translationY(0f)
                            .setDuration(200)
                            .setInterpolator(DecelerateInterpolator())
                            .start()
                        sheetScrim.animate().alpha(1f).setDuration(150).start()
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun sendUserMessage() {
        val text = assistantInput.text.toString().trim()
        if (text.isEmpty()) return
        assistantInput.setText("")
        addBubble(text, user = true)

        val local = engine.respond(text)
        if (local != null) {
            addAssistantReply(local)
            return
        }

        // Свободный вопрос → бесплатный LLM (с локальным фолбэком)
        showTyping()
        AiBackend.ask(text, engineRu()) { reply ->
            mainHandler.post {
                hideTyping()
                if (reply != null && reply.isNotBlank() && reply.length > 3) {
                    addAssistantReply(AssistantEngine.Reply(reply))
                } else {
                    addAssistantReply(engine.fallbackReply())
                }
            }
        }
    }

    private fun engineRu(): Boolean =
        Locale.getDefault().language.equals("ru", ignoreCase = true)

    private fun addAssistantReply(reply: AssistantEngine.Reply) {
        addBubble(reply.text, user = false)
        for (chip in reply.chips) {
            chipsContainer.addView(createChip(chip))
        }
    }

    private fun showTyping() {
        val density = resources.displayMetrics.density
        val tv = TextView(this).apply {
            text = getString(R.string.assistant_typing)
            textSize = 14f
            setTextColor(getColor(R.color.ios_text_secondary))
            setPadding((14 * density).toInt(), (10 * density).toInt(), (14 * density).toInt(), (10 * density).toInt())
            background = bubbleBg(user = false)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }
        row.addView(tv)
        chatContainer.addView(row)
        typingView = row
        scrollChatToBottom()
    }

    private fun hideTyping() {
        typingView?.let { chatContainer.removeView(it) }
        typingView = null
    }

    private fun bubbleBg(user: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        val bg = GradientDrawable()
        if (user) {
            bg.setColor(getColor(R.color.ios_blue))
            bg.cornerRadii = floatArrayOf(
                16f * density, 16f * density, 4f * density, 16f * density
            )
        } else {
            bg.setColor(getColor(R.color.ios_card))
            bg.cornerRadii = floatArrayOf(
                16f * density, 16f * density, 16f * density, 4f * density
            )
        }
        return bg
    }

    private fun addBubble(text: String, user: Boolean) {
        val density = resources.displayMetrics.density
        val tv = TextView(this).apply {
            this.text = text
            textSize = 15f
            setLineSpacing(4f, 1f)
            setPadding(
                (14 * density).toInt(), (10 * density).toInt(),
                (14 * density).toInt(), (10 * density).toInt()
            )
            maxWidth = (resources.displayMetrics.widthPixels * 0.8f).toInt()
            background = bubbleBg(user)
            if (user) {
                setTextColor(Color.WHITE)
            } else {
                setTextColor(getColor(R.color.ios_text))
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (user) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (6 * density).toInt() }
        }
        row.addView(tv)
        chatContainer.addView(row)
        scrollChatToBottom()
    }

    private fun scrollChatToBottom() {
        chatScroll.post {
            chatScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ------------------------------------------------------------------ Offline

    private fun setUpOffline() {
        (findViewById(R.id.retry_button) as Button)?.setOnClickListener {
            haptic()
            retry()
        }
    }

    private fun showOffline() {
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
        topBar.visibility = View.GONE
        bottomBar.visibility = View.GONE
    }

    override fun onExitFullscreen() {
        if (customView == null) return
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        topBar.visibility = View.VISIBLE
        bottomBar.visibility = View.VISIBLE
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
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        pendingPermissionRequest?.deny()
        pendingPermissionRequest = null
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
