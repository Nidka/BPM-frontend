@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.core.presentation

internal actual fun platformLabel(): String = "Web"

internal actual fun offersAndroidDownload(): Boolean = !isAppleMobile()

// iPadOS se presenta como Mac con pantalla táctil; ambos casos ocultan la descarga del APK.
@JsFun("""() => {
    const ua = navigator.userAgent || '';
    return /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && (navigator.maxTouchPoints || 0) > 1);
}""")
private external fun isAppleMobile(): Boolean
