package pe.bpm.core.offline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Cómo se presenta una operación en «Cambios pendientes». */
enum class PendingState { WAITING, SENDING, SENT, NEEDS_REVIEW, FAILED, PAUSED, BLOCKED }

data class PendingItem(
    val id: String,
    val title: String,
    val state: PendingState,
    val createdAt: Long,
    /** Mensaje legible; nunca contiene datos del registro ni texto interno del servidor. */
    val message: String,
    val attempts: Int,
    val canRetry: Boolean,
    /** Cuántas operaciones más se descartarían junto con esta (las que dependen de ella). */
    val dependents: Int,
)

data class OfflineUiState(
    val status: OfflineStatus = OfflineStatus(),
    val items: List<PendingItem> = emptyList(),
    val syncing: Boolean = false,
    val notice: String? = null,
    val now: Long = 0,
)

/** Título comprensible de una operación; los tipos nuevos agregan el suyo aquí. */
fun OutboxOperation.title(): String = when (kind) {
    HttpOutboxHandler.KIND -> runCatching {
        val payload = Json.decodeFromString(HttpOperationPayload.serializer(), payload)
        OfflineHttpPolicy.describe(payload.method, payload.path)
    }.getOrDefault("Cambio pendiente")
    PHOTO_KIND -> "Subir foto de evidencia"
    else -> "Cambio pendiente"
}

const val PHOTO_KIND = "MEDIA_PHOTO"

/** Texto «hace X» para la última sincronización de los datos mostrados. */
fun relativeAge(nowMillis: Long, thenMillis: Long): String {
    val seconds = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "hace unos segundos"
        seconds < 3_600 -> "hace ${seconds / 60} min"
        seconds < 86_400 -> "hace ${seconds / 3_600} h"
        seconds < 172_800 -> "hace 1 día"
        else -> "hace ${seconds / 86_400} días"
    }
}

class OfflineViewModel(private val runtime: OfflineRuntime) : ViewModel() {
    private val notice = MutableStateFlow<String?>(null)

    val state: StateFlow<OfflineUiState> = combine(runtime.status, runtime.outbox.operations, notice) { status, operations, message ->
        OfflineUiState(status, operations.reversed().map { item(it, operations) }, status.syncing, message, runtime.clock())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, OfflineUiState())

    init { runtime.start(viewModelScope) }

    private fun item(op: OutboxOperation, all: List<OutboxOperation>): PendingItem {
        val blocked = op.status == OutboxStatus.PENDING && op.dependsOn.any { dependency ->
            all.firstOrNull { it.id == dependency }?.let { it.status == OutboxStatus.CONFLICT || it.status == OutboxStatus.REJECTED } == true
        }
        val state = when {
            op.status == OutboxStatus.SENT -> PendingState.SENT
            op.status == OutboxStatus.SENDING -> PendingState.SENDING
            op.status == OutboxStatus.CONFLICT -> PendingState.NEEDS_REVIEW
            op.status == OutboxStatus.REJECTED -> PendingState.FAILED
            op.exhausted -> PendingState.PAUSED
            blocked -> PendingState.BLOCKED
            else -> PendingState.WAITING
        }
        val message = when (state) {
            PendingState.NEEDS_REVIEW, PendingState.FAILED -> op.lastError
            PendingState.PAUSED -> "No se pudo enviar después de varios intentos. Pulsa Reintentar cuando tengas buena conexión."
            PendingState.BLOCKED -> "Espera a que se resuelva un cambio anterior del mismo registro."
            PendingState.WAITING -> if (op.attempts > 0) "Se reintentará automáticamente." else "Se enviará cuando haya conexión."
            else -> ""
        }
        val dependents = all.count { other -> op.id in other.dependsOn }
        return PendingItem(op.id, op.title(), state, op.clientCreatedAt, message, op.attempts,
            canRetry = state in setOf(PendingState.NEEDS_REVIEW, PendingState.FAILED, PendingState.PAUSED), dependents = dependents)
    }

    fun syncNow() {
        viewModelScope.launch {
            val report = runtime.syncNow()
            notice.value = when {
                report.skippedOffline -> "Sin conexión: los cambios se enviarán al recuperarla."
                report.sent > 0 && report.failed == 0 -> "Se enviaron ${report.sent} cambios."
                report.failed > 0 -> "Algunos cambios no se pudieron enviar todavía."
                else -> "No hay cambios por enviar."
            }
        }
    }

    /** Se llama al volver al primer plano. */
    fun onForeground() { viewModelScope.launch { runtime.syncNow(ignoreBackoff = true) } }

    fun retry(id: String) { viewModelScope.launch { runtime.outbox.retry(id); runtime.syncNow() } }
    fun discard(id: String) { viewModelScope.launch { runtime.outbox.discard(id) } }
    fun dismissNotice() { notice.value = null }
}
