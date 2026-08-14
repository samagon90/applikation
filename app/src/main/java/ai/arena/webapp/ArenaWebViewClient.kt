package ai.arena.webapp

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Навигация внутри arena.ai / lmarena.ai (+ зеркало, если активно),
 * всё остальное (другие домены, mailto:, tel:, intent:) — системе.
 * В режиме зеркала абсолютные ссылки на arena.ai переписываются
 * на домен зеркала.
 */
class ArenaWebViewClient(
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onMainFrameError: () -> Unit,
    private val onMainFrameHttpError: (Int) -> Unit,
    private val isMirrorActive: () -> Boolean,
    private val mirrorHost: () -> String?,
    private val openExternal: (Uri) -> Unit
) : WebViewClient() {

    companion object {
        private val INTERNAL_HOSTS = listOf("arena.ai", "lmarena.ai")
    }

    private fun isInternalHost(host: String): Boolean {
        if (host.isEmpty()) return false
        val mirror = mirrorHost()
        if (mirror != null && host == mirror) return true
        return INTERNAL_HOSTS.any { h -> host == h || host.endsWith(".$h") }
    }

    private fun rewrittenUri(uri: Uri): Uri {
        val mirror = if (isMirrorActive()) mirrorHost() else null
        if (mirror == null) return uri
        val host = uri.host?.lowercase().orEmpty()
        val isArena = INTERNAL_HOSTS.any { h -> host == h || host.endsWith(".$h") }
        return if (isArena) uri.buildUpon().scheme("https").authority(mirror).build() else uri
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (isInternalHost(uri.host?.lowercase().orEmpty())) {
                val rewritten = rewrittenUri(uri)
                if (rewritten != uri) {
                    view.loadUrl(rewritten.toString())
                    return true
                }
                return false
            }
        }
        openExternal(uri)
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted.invoke()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished.invoke()
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)
        if (request.isForMainFrame) {
            onMainFrameError.invoke()
        }
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request.isForMainFrame) {
            onMainFrameHttpError.invoke(errorResponse.statusCode)
        }
    }
}
