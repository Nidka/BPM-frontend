package pe.bpm.core.config

@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
@JsFun("() => window.location.origin")
private external fun browserOrigin(): String

actual fun platformAppConfig(): AppConfig = AppConfig(
    apiBaseUrl = browserOrigin() + "/backend",
    publicConfigUrl = browserOrigin() + "/config.json",
)
