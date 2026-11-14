package pe.bpm.core.offline

import kotlinx.serialization.Serializable

/**
 * Ámbito de los datos locales: origen de la API y cuenta. La empresa viaja en la clave de cada
 * registro (la ruta ya la incluye) y en cada operación, de modo que nunca se mezclan cuentas.
 */
data class OfflineScope(val origin: String, val userId: String) {
    init { require(origin.isNotBlank() && userId.isNotBlank()) }
    /** Longitudes explícitas evitan colisiones entre origen y usuario. */
    val id: String get() = "${origin.length}:$origin${userId.length}:$userId"
}

/** Registro de la caché local; [json] nunca contiene tokens, URL firmadas ni secretos. */
data class StoredRecord(val key: String, val json: String, val updatedAt: Long)

/**
 * Estados de una operación pendiente. SENDING solo existe mientras hay un envío en curso; tras un
 * cierre inesperado se vuelve a PENDING (la clave de idempotencia evita duplicados).
 */
enum class OutboxStatus { PENDING, SENDING, SENT, CONFLICT, REJECTED }

/**
 * Operación de negocio registrada localmente antes de salir a la red.
 * [id] es el UUID de cliente estable; [idempotencyKey] es el encabezado/clientOperationId que el
 * backend usa para responder igual ante repeticiones.
 */
data class OutboxOperation(
    val id: String,
    val kind: String,
    val organizationId: String,
    val entityId: String,
    val idempotencyKey: String,
    val clientCreatedAt: Long,
    val payload: String,
    val dependsOn: List<String> = emptyList(),
    val status: OutboxStatus = OutboxStatus.PENDING,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String = "",
    val lastHttpStatus: Int = 0,
    val updatedAt: Long = 0,
    /** Orden de inserción asignado por la base; 0 mientras no se ha guardado. */
    val seq: Long = 0,
) {
    val resolved get() = status == OutboxStatus.SENT
    /** Requiere una decisión del usuario (conflicto, rechazo o reintentos agotados). */
    val needsAttention get() = status == OutboxStatus.CONFLICT || status == OutboxStatus.REJECTED || exhausted
    val exhausted get() = status == OutboxStatus.PENDING && attempts >= OutboxPolicy.MAX_ATTEMPTS
}

/** Datos con los que una feature (o el transporte HTTP) registra una operación nueva. */
data class OutboxDraft(
    val id: String,
    val kind: String,
    val organizationId: String,
    val entityId: String,
    val idempotencyKey: String,
    val payload: String,
    val clientCreatedAt: Long,
)

/** Política única de reintentos: backoff exponencial con tope. */
object OutboxPolicy {
    const val MAX_ATTEMPTS = 12
    const val BASE_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 15 * 60_000L
    const val MAX_PAYLOAD_BYTES = 600_000
    /** Operaciones ENVIADAS que se conservan para resolver dependencias y mostrar historial. */
    const val KEEP_SENT = 30

    /** Espera tras el intento número [attempts] (1 = primer fallo). */
    fun backoff(attempts: Int): Long {
        val exponent = (attempts - 1).coerceIn(0, 20)
        return minOf(MAX_DELAY_MS, BASE_DELAY_MS shl exponent)
    }
}

/** Resultado de un intento de envío, ya clasificado por quien conoce el transporte. */
sealed interface SendOutcome {
    /** 2xx o repetición idempotente: la operación queda ENVIADA. */
    data class Sent(val httpStatus: Int = 200) : SendOutcome
    /** 409 de versión o de negocio: el usuario debe recargar o descartar. */
    data class Conflict(val message: String, val httpStatus: Int = 409) : SendOutcome
    /** 4xx de validación o permisos: reintentar no ayuda. */
    data class Rejected(val message: String, val httpStatus: Int) : SendOutcome
    /** Red, 5xx, 408 o 429: se reintenta con backoff. [network] detiene la tanda actual. */
    data class Retry(
        val message: String, val httpStatus: Int = 0, val network: Boolean = false, val retryAfterMs: Long? = null,
        /** false cuando la causa es una sesión por renovar: no consume intentos. */
        val countAttempt: Boolean = true,
    ) : SendOutcome
}

@Serializable
internal data class HttpOperationPayload(
    val method: String,
    /** Ruta relativa al origen de la API (sin esquema ni host), con su consulta. */
    val path: String,
    val contentType: String = "",
    val body: String = "",
    /** true si la solicitud original enviaba Idempotency-Key como cabecera. */
    val keyHeader: Boolean = true,
    /**
     * Ruta GET (relativa a la API) de la que se lee data.rowVersion justo antes de enviar. Sirve para
     * encadenar «finalizar» tras «guardar» sin conocer de antemano la versión que asignará el servidor.
     */
    val refreshVersionFrom: String = "",
)
