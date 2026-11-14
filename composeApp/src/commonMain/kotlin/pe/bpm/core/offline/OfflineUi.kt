package pe.bpm.core.offline

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.BpmCard
import pe.bpm.core.designsystem.BpmSecondaryButton
import pe.bpm.core.designsystem.BpmTextButton
import pe.bpm.core.designsystem.EmptyState

/** true cuando hay red. Las pantallas que exigen servidor deshabilitan sus acciones y lo explican. */
val LocalOnline = compositionLocalOf { true }

/** Motivo que se muestra junto a una acción deshabilitada por falta de conexión. */
const val OFFLINE_REQUIRED_MESSAGE = "Esta acción necesita conexión a internet. Vuelve a intentarlo cuando estés en línea."

/** Aviso bajo una acción que requiere servidor; no ocupa espacio si hay conexión. */
@Composable
fun OfflineRequiredNotice(modifier: Modifier = Modifier) {
    if (!LocalOnline.current) {
        Text(OFFLINE_REQUIRED_MESSAGE, modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Franja global: «Sin conexión · N cambios pendientes», envío en curso o cambios que requieren
 * revisión. Desaparece cuando todo está sincronizado y hay red.
 */
@Composable
fun OfflineStatusBar(state: OfflineUiState, onOpen: () -> Unit, onSync: () -> Unit, modifier: Modifier = Modifier) {
    val status = state.status
    val text = when {
        !status.online -> buildString {
            append("Sin conexión")
            append(if (status.pending > 0) " · ${pendingText(status.pending)}" else " · Todo lo que registres se guardará aquí")
            status.staleSince?.let { append(" · Datos de ${relativeAge(state.now, it)}") }
        }
        status.attention > 0 -> "${if (status.attention == 1) "1 cambio necesita" else "${status.attention} cambios necesitan"} tu revisión"
        status.syncing -> "Enviando cambios…"
        status.pending > 0 -> pendingText(status.pending)
        else -> return
    }
    val problem = status.online && status.attention > 0
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = if (problem) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (problem) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (status.online && status.pending > 0 && !status.syncing) BpmTextButton(onSync) { Text("Sincronizar ahora") }
            if (status.pending > 0 || status.attention > 0) BpmTextButton(onOpen) { Text("Ver") }
        }
    }
}

private fun pendingText(count: Int) = if (count == 1) "1 cambio pendiente" else "$count cambios pendientes"

private fun PendingState.label() = when (this) {
    PendingState.WAITING -> "Pendiente de enviar"
    PendingState.SENDING -> "Enviando…"
    PendingState.SENT -> "Enviado"
    PendingState.NEEDS_REVIEW -> "Necesita tu revisión"
    PendingState.FAILED -> "No se pudo enviar"
    PendingState.PAUSED -> "Envío en pausa"
    PendingState.BLOCKED -> "En espera"
}

/** Pantalla «Cambios pendientes»: cada operación con su estado, error legible y acciones. */
@Composable
fun PendingChangesScreen(
    state: OfflineUiState, onBack: () -> Unit, onSync: () -> Unit, onRetry: (String) -> Unit, onDiscard: (String) -> Unit,
    onDismissNotice: () -> Unit, modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<PendingItem?>(null) }
    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BpmTextButton(onBack) { Text("Volver") }
        Text("Cambios pendientes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(if (state.status.online) "Conectado" else "Sin conexión: se enviarán al recuperarla.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BpmSecondaryButton(onSync, Modifier.fillMaxWidth(), enabled = state.status.online && !state.syncing) {
            Text(if (state.syncing) "Enviando…" else "Sincronizar ahora")
        }
        state.notice?.let { message ->
            Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                BpmTextButton(onDismissNotice) { Text("Entendido") }
            }
        }
        if (state.items.isEmpty()) EmptyState("Todo está enviado", "No hay cambios pendientes en este dispositivo.")
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(state.items, key = { it.id }) { item -> PendingCard(item, state.now, onRetry, { confirming = item }) }
        }
    }
    confirming?.let { item ->
        AlertDialog(onDismissRequest = { confirming = null },
            title = { Text("¿Descartar este cambio?") },
            text = {
                Text("«${item.title}» no se enviará y se perderá de este dispositivo." +
                    if (item.dependents > 0) " También se descartarán ${item.dependents} cambios que dependen de él." else "")
            },
            confirmButton = { BpmTextButton({ onDiscard(item.id); confirming = null }) { Text("Descartar") } },
            dismissButton = { BpmTextButton({ confirming = null }) { Text("Conservar") } })
    }
}

@Composable
private fun PendingCard(item: PendingItem, now: Long, onRetry: (String) -> Unit, onDiscard: () -> Unit) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(item.state.label() + " · registrado ${relativeAge(now, item.createdAt)}", style = MaterialTheme.typography.bodyMedium)
        if (item.message.isNotBlank()) Text(item.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (item.state != PendingState.SENT && item.state != PendingState.SENDING) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.canRetry) BpmSecondaryButton({ onRetry(item.id) }) { Text("Reintentar") }
                BpmTextButton(onDiscard) { Text(if (item.state == PendingState.NEEDS_REVIEW) "Descartar y recargar" else "Descartar") }
            }
        }
    }
}
