package pe.bpm.features.webhooks.domain

/** Webhook de la empresa (RF-48). [secret] solo llega al crearlo o rotarlo y nunca se guarda en el dispositivo. */
data class Webhook(
    val id: String, val url: String, val eventTypes: List<String>, val active: Boolean,
    val lastStatus: Int?, val lastError: String, val lastDeliveryAt: String?, val secret: String? = null,
)

data class WebhookList(val items: List<Webhook>, val eventTypes: List<String>, val maxEndpoints: Int)
data class WebhookInput(val url: String, val eventTypes: Set<String>, val active: Boolean = true)
data class WebhookTest(val delivered: Boolean, val status: Int)

enum class WebhookFailure { SESSION, DENIED, NOT_FOUND, NOT_DEPLOYED, LIMIT, INVALID, CONNECTION, UNAVAILABLE, INVALID_RESPONSE }

sealed interface WebhookResult<out T> {
    data class Success<T>(val value: T) : WebhookResult<T>
    data class Failure(val reason: WebhookFailure, val fields: Map<String, String> = emptyMap()) : WebhookResult<Nothing>
}

interface WebhookRepository {
    suspend fun list(org: String): WebhookResult<WebhookList>
    suspend fun create(org: String, input: WebhookInput): WebhookResult<Webhook>
    suspend fun update(org: String, id: String, input: WebhookInput): WebhookResult<Webhook>
    suspend fun delete(org: String, id: String): WebhookResult<Unit>
    suspend fun rotate(org: String, id: String): WebhookResult<Webhook>
    suspend fun test(org: String, id: String): WebhookResult<WebhookTest>
}

object WebhookRules {
    /** Nombre visible de cada tipo de aviso. */
    fun eventLabel(type: String) = when (type) {
        "CHECKLIST_MISSING" -> "Control del día sin completar"
        "TEMPERATURE_DANGER" -> "Temperatura en zona de peligro"
        "ORGANIZATION_INACTIVE" -> "Empresa sin registros"
        "TRAINING_ASSIGNED" -> "Nueva capacitación asignada"
        else -> type
    }

    /** Igual que el servidor: https, sin credenciales ni nombres internos (el servidor además bloquea IP privadas). */
    fun urlIssue(raw: String): String? {
        val url = raw.trim()
        val host = Regex("^https://([^/?#@:]+)(:\\d{1,5})?(?:[/?].*)?$").find(url)?.groupValues?.get(1)?.lowercase()
        return when {
            url.isEmpty() || url.length > 500 -> "Escribe la dirección del webhook (hasta 500 caracteres)."
            host == null || '@' in url.substringBefore('?') -> "Usa una dirección https:// válida, sin usuario ni contraseña."
            host == "localhost" || '.' !in host || host.endsWith(".local") || host.endsWith(".internal") || Regex("^(127|10|192\\.168|169\\.254)\\.").containsMatchIn(host) ->
                "La dirección debe ser pública (no una red interna)."
            else -> null
        }
    }

    fun issues(input: WebhookInput): Map<String, String> = buildMap {
        urlIssue(input.url)?.let { put("url", it) }
        if (input.eventTypes.isEmpty()) put("eventTypes", "Elige al menos un tipo de aviso.")
    }

    fun failureMessage(reason: WebhookFailure, max: Int = 5) = when (reason) {
        WebhookFailure.SESSION -> "Tu sesión venció. Inicia sesión nuevamente."
        WebhookFailure.DENIED -> "Solo el administrador de la empresa gestiona sus automatizaciones."
        WebhookFailure.NOT_FOUND -> "El webhook ya no existe."
        WebhookFailure.NOT_DEPLOYED -> "Las automatizaciones estarán disponibles cuando se actualice el servidor."
        WebhookFailure.LIMIT -> "Puedes registrar hasta $max webhooks."
        WebhookFailure.INVALID -> "Revisa los datos indicados."
        WebhookFailure.CONNECTION -> "No pudimos conectar. Revisa tu conexión e inténtalo otra vez."
        WebhookFailure.UNAVAILABLE -> "El servicio no está disponible. Inténtalo otra vez."
        WebhookFailure.INVALID_RESPONSE -> "No pudimos verificar la respuesta del servidor."
    }
}

class WebhookUseCases(private val repository: WebhookRepository) {
    suspend fun list(org: String) = repository.list(org)
    suspend fun save(org: String, id: String?, input: WebhookInput): WebhookResult<Webhook> {
        val issues = WebhookRules.issues(input)
        if (issues.isNotEmpty()) return WebhookResult.Failure(WebhookFailure.INVALID, issues)
        return if (id == null) repository.create(org, input.copy(url = input.url.trim())) else repository.update(org, id, input.copy(url = input.url.trim()))
    }
    suspend fun delete(org: String, id: String) = repository.delete(org, id)
    suspend fun rotate(org: String, id: String) = repository.rotate(org, id)
    suspend fun test(org: String, id: String) = repository.test(org, id)
}
