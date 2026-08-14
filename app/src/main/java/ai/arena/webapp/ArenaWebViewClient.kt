package ai.arena.webapp

import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Держит навигацию внутри arena.ai / *.arena.ai, всё остальное
 * (другие домены, mailto:, tel:, intent: и т.д.) отдаёт системе.
 */
class ArenaWebViewClient(
    private val onPageFinished: () -> Unit,
    private val onMainFrameError: () -> Unit,
    private val openExternal: (Uri) -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme == "http" || scheme == "https") {
            val host = uri.host?.lowercase().orEmpty()
            val isInternal = host == "arena.ai" || host.endsWith(".arena.ai")
            if (isInternal) return false
        }
        openExternal(uri)
        return true
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
}
