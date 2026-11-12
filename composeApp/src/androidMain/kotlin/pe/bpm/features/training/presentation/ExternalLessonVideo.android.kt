package pe.bpm.features.training.presentation

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Android: WebView embebida. JavaScript se habilita porque el reproductor de YouTube lo exige, pero
 * la vista solo puede cargar `youtube-nocookie.com`: cualquier otra navegación se bloquea. Sin
 * acceso a archivos ni a contenido local.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun ExternalLessonVideo(embedUrl: String, watchUrl: String, modifier: Modifier) {
    val context = LocalContext.current
    val webView = remember(embedUrl) {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    !(request.url.scheme == "https" && request.url.host == "www.youtube-nocookie.com")
            }
            loadUrl(embedUrl)
        }
    }
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }
    AndroidView(factory = { webView }, modifier = modifier)
}
