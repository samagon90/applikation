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
 *
 * Примечание: типы используются без generics (ValueCallback без <Array<Uri>>),
 * чтобы код компилировался и против оффлайн-варианта android.jar (см. README,
 * раздел «Сборка без Android SDK»). Сигнатуры полностью совпадают с erasure
 * реального API.
 */
class ArenaWebChromeClient(private val host: Host) : WebChromeClient() {

    interface Host {
        fun onShowFileChooser(
            callback: ValueCallback,
            params: WebChromeClient.FileChooserParams
        ): Boolean

        fun onPermissionRequested(request: PermissionRequest)
        fun onEnterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
        fun onExitFullscreen()
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback,
        fileChooserParams: WebChromeClient.FileChooserParams
    ): Boolean = host.onShowFileChooser(filePathCallback, fileChooserParams)

    override fun onPermissionRequest(request: PermissionRequest) {
        host.onPermissionRequested(request)
    }

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        host.onEnterFullscreen(view, callback)
    }

    override fun onHideCustomView() {
        host.onExitFullscreen()
    }
}
