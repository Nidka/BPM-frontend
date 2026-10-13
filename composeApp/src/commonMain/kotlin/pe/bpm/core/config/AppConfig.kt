package pe.bpm.core.config

data class AppConfig(
    val apiBaseUrl: String,
    val firebaseApiKey: String = "",
    val publicConfigUrl: String? = null,
) {
    init {
        require(apiBaseUrl.startsWith("https://") ||
            apiBaseUrl.startsWith("http://localhost:") ||
            apiBaseUrl.startsWith("http://127.0.0.1:")) {
            "La API debe usar HTTPS; HTTP solo se permite en loopback para desarrollo."
        }
    }
}

expect fun platformAppConfig(): AppConfig
