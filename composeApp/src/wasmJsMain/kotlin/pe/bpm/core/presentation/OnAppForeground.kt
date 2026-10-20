@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

@Composable actual fun OnAppForeground(onForeground: () -> Unit) {
    val callback = rememberUpdatedState(onForeground)
    DisposableEffect(Unit) {
        val listener = listenForForeground { callback.value() }
        onDispose { removeForegroundListener(listener) }
    }
}

@JsFun("""(callback) => {
    const listener = () => { if (document.visibilityState === 'visible') callback(); };
    document.addEventListener('visibilitychange', listener);
    window.addEventListener('pageshow', listener);
    window.addEventListener('focus', listener);
    listener();
    return listener;
}""")
private external fun listenForForeground(callback: () -> Unit): JsAny

@JsFun("""(listener) => {
    document.removeEventListener('visibilitychange', listener);
    window.removeEventListener('pageshow', listener);
    window.removeEventListener('focus', listener);
}""")
private external fun removeForegroundListener(listener: JsAny)
