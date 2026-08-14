package ai.arena.webapp

import android.net.Uri
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * Загрузка файлов (upload), запросы разрешений (камера/микрофон)
 * и полноэкранное видео.
 */
class ArenaWebChromeClient(private val host: Host) : WebChromeClient() {

    interface Host {
        fun onShowFileChooser(
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean

        fun onPermissionRequested(request: PermissionRequest)
        fun onEnterFullscreen(view: View, callback: CustomViewCallback)
        fun onExitFullscreen()
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = host.onShowFileChooser(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) {
        host.onPermissionRequested(request)
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        host.onEnterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        host.onExitFullscreen()
    }
}
