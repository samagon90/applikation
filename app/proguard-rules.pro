# WebView with JS (нет JS-интерфейсов, правила на будущее)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
