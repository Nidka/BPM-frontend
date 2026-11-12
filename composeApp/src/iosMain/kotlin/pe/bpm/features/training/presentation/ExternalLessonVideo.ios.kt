@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package pe.bpm.features.training.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.readValue
import platform.CoreGraphics.CGRectZero
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

/** Restringe la navegación al dominio de inserción; lo demás se cancela. */
private class YouTubeOnlyDelegate : NSObject(), WKNavigationDelegateProtocol {
    override fun webView(webView: WKWebView, decidePolicyForNavigationAction: WKNavigationAction, decisionHandler: (WKNavigationActionPolicy) -> Unit) {
        val url = decidePolicyForNavigationAction.request.URL
        val allowed = url?.scheme == "https" && url.host == "www.youtube-nocookie.com"
        decisionHandler(if (allowed) WKNavigationActionPolicy.WKNavigationActionPolicyAllow else WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }
}

/** iOS: WKWebView embebida y limitada a youtube-nocookie.com. */
@Composable
actual fun ExternalLessonVideo(embedUrl: String, watchUrl: String, modifier: Modifier) {
    val delegate = remember { YouTubeOnlyDelegate() }
    val view = remember(embedUrl) {
        val configuration = WKWebViewConfiguration().apply { allowsInlineMediaPlayback = true }
        WKWebView(frame = CGRectZero.readValue(), configuration = configuration).apply {
            navigationDelegate = delegate
            NSURL.URLWithString(embedUrl)?.let { loadRequest(NSURLRequest.requestWithURL(it)) }
        }
    }
    UIKitView(factory = { view }, modifier = modifier, onRelease = { it.stopLoading(); it.navigationDelegate = null })
}
