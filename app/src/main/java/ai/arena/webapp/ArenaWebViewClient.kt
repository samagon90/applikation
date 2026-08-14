package ai.arena.webapp

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Навигация: внутри arena.ai / lmarena.ai, всё остальное
 * (другие домены, mailto:, tel:, intent:) — системе.
 */
class ArenaWebViewClient(
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onMainFrameError: () -> Unit,
    private val onMainFrameHttpError: (Int) -> Unit,
    private val openExternal: (Uri) -> Unit
) : WebViewClient() {

    private fun isInternalHost(host: String): Boolean {
        if (host.isEmpty()) return false
        return host == "arena.ai" || host.endsWith(".arena.ai") ||
            host == "lmarena.ai" || host.endsWith(".lmarena.ai")
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            if (isInternalHost(uri.host?.lowercase().orEmpty())) {
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
