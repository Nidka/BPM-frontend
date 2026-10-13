package pe.bpm.core.config

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => window.location.origin")
private external fun browserOrigin(): String

actual fun platformAppConfig(): AppConfig = AppConfig(browserOrigin() + "/backend")
