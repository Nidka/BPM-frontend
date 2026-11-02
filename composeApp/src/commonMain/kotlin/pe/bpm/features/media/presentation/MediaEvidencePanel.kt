package pe.bpm.features.media.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.decodeToImageBitmap
import pe.bpm.core.designsystem.*
import pe.bpm.features.media.domain.*

/** Panel abierto por una pregunta; no inicia consultas por cada fila del formulario. */
@Composable
fun MediaEvidencePanel(viewModel: MediaEvidenceViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    key(state.userId, state.organizationId, state.runId, state.itemId) {
        MediaEvidenceContent(state, viewModel, modifier)
    }
}

@Composable
private fun MediaEvidenceContent(state: MediaEvidenceState, viewModel: MediaEvidenceViewModel, modifier: Modifier) {
    val picker = rememberEvidencePhotoPicker(
        maxBytes = state.policy?.maxBytes ?: 0,
        allowedMimeTypes = state.policy?.allowedMimeTypes ?: emptySet(),
        onResult = { result -> viewModel.select(result, state.context, state.userId) },
    )
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Fotos de esta pregunta", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(if (state.confirmedCount == 1) "1 foto guardada" else "${state.confirmedCount} fotos guardadas", style = MaterialTheme.typography.bodyMedium)
        if (state.loading || state.busy) {
            if (state.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            Text(if (state.pendingId != null && state.busy) "Enviando y comprobando la foto…" else "Consultando fotos…")
        }
        state.failure?.let { Text(mediaFailureMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.selectionFailure?.let { Text(selectionFailureMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (!state.loading && state.files.none { it.status != EvidenceStatus.DELETED }) {
            Text("Todavía no hay fotos guardadas para esta pregunta.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.files.filterNot { it.status == EvidenceStatus.DELETED }.forEach { file ->
            BpmCard(Modifier.fillMaxWidth()) {
                Text(file.originalName, style = MaterialTheme.typography.titleMedium)
                Text(when (file.status) {
                    EvidenceStatus.AVAILABLE -> "Foto guardada"
                    EvidenceStatus.PENDING_UPLOAD -> "Foto pendiente de envío"
                    EvidenceStatus.FAILED -> "No pudimos validar esta foto. Retírala y elige otra."
                    EvidenceStatus.DELETED -> "Foto retirada"
                })
                if (file.status == EvidenceStatus.AVAILABLE) BpmSecondaryButton(onClick = { viewModel.view(file.id) }, enabled = !state.busy && !state.loading) { Text("Ver foto") }
                if (state.canEdit) BpmTextButton(onClick = { viewModel.requestRemove(file.id) }, enabled = !state.busy && !state.loading) { Text("Retirar foto") }
            }
        }
        if (state.pendingName != null) BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.secondaryContainer) {
            Text(state.pendingName, fontWeight = FontWeight.Bold)
            Text("Esta foto todavía no se ha guardado. Reintenta el envío antes de cerrar este panel. Si lo cierras, tendrás que volver a seleccionarla.")
            if (state.pendingNeedsConfirmation) Text("Al reintentar comprobaremos si la foto ya se guardó.")
            if (!state.busy) {
                RaisedButton("Reintentar envío", viewModel::retry, enabled = state.canEdit && !state.loading)
                BpmSecondaryButton(onClick = viewModel::discardPending, enabled = !state.loading) { Text("Quitar selección") }
                Text("Si una foto pendiente sigue en la lista, puedes retirarla.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.canEdit) {
            Text("Para guardar fotos necesitas conexión. Reintenta cualquier envío pendiente antes de cerrar este panel.", style = MaterialTheme.typography.bodySmall)
            RaisedButton("Elegir foto", picker, enabled = state.canSelect)
            state.policy?.let { policy ->
                Text("Máximo ${formatPhotoLimit(policy.maxBytes)} por foto y hasta ${policy.maxFiles} fotos por pregunta.", style = MaterialTheme.typography.bodySmall)
                if (!policy.uploadAvailable) Text("El envío de fotos no está disponible en este momento. Puedes consultar las fotos existentes.")
            }
        } else Text("Puedes consultar las fotos de esta revisión.")
        if (state.failure != null) BpmSecondaryButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy) { Text("Reintentar consulta") }
        state.removalCandidate?.let { file ->
            BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.errorContainer) {
                Text("¿Retirar ${file.originalName}?", fontWeight = FontWeight.Bold)
                Text("Dejará de contar como evidencia de esta pregunta.")
                RaisedButton("Confirmar retirada", viewModel::confirmRemove, enabled = !state.busy)
                BpmSecondaryButton(onClick = viewModel::cancelRemove, enabled = !state.busy) { Text("Conservar foto") }
            }
        }
        state.preview?.let { preview ->
            EvidencePreviewImage(preview)
            BpmSecondaryButton(onClick = viewModel::hidePreview) { Text("Cerrar imagen") }
        }
    }
}

/** Solo recibe bytes descargados después de confirmar AVAILABLE en el servidor. */
@Composable
private fun EvidencePreviewImage(preview: EvidencePreview) {
    val decoded by produceState<Pair<Boolean, ImageBitmap?>>(false to null, preview) {
        value = true to withContext(Dispatchers.Default) {
            try { preview.bytes.decodeToImageBitmap() } catch (_: Exception) { null }
        }
    }
    BpmCard(Modifier.fillMaxWidth()) {
        val bitmap = decoded.second
        if (!decoded.first) Text("Preparando imagen…")
        else if (bitmap == null) Text("No pudimos mostrar esta foto. Cierra la imagen y vuelve a intentarlo.")
        else Image(bitmap, contentDescription = "Foto de evidencia: ${preview.name}", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), contentScale = ContentScale.Fit)
    }
}

private fun formatPhotoLimit(bytes: Int): String = if (bytes % (1024 * 1024) == 0) "${bytes / (1024 * 1024)} MiB" else "${bytes / 1024} KiB"
private fun selectionFailureMessage(failure: PhotoSelectionFailure): String = when (failure) {
    PhotoSelectionFailure.TOO_LARGE -> "La foto supera el tamaño permitido. Elige una más pequeña."
    PhotoSelectionFailure.UNSUPPORTED -> "El formato de esa foto no está admitido. Elige otra imagen."
    PhotoSelectionFailure.READ_FAILED -> "No pudimos leer la foto. Vuelve a elegirla."
    PhotoSelectionFailure.UNAVAILABLE -> "No pudimos abrir el selector de fotos en este dispositivo."
}
private fun mediaFailureMessage(failure: MediaFailure): String = when (failure) {
    MediaFailure.SESSION -> "Tu sesión terminó. Vuelve a ingresar para consultar las fotos."
    MediaFailure.DENIED -> "No tienes permiso para realizar esta acción en la revisión."
    MediaFailure.NOT_FOUND -> "La foto o la pregunta ya no está disponible. Actualiza la lista."
    MediaFailure.INVALID -> "No pudimos enviar esa foto. Revisa la selección y vuelve a intentarlo."
    MediaFailure.TOO_LARGE -> "La foto supera el tamaño permitido. Elige una más pequeña."
    MediaFailure.UNSUPPORTED -> "El formato de esa foto no está admitido. Elige otra imagen."
    MediaFailure.CONTENT_INVALID -> "No pudimos validar esta foto. Retírala y elige otra."
    MediaFailure.CONFLICT -> "La revisión cambió o ya no admite esta acción. Actualiza la lista."
    MediaFailure.UPLOAD_NOT_READY -> "El envío todavía no está confirmado. Reintenta para comprobarlo."
    MediaFailure.RATE_LIMITED -> "Hay demasiadas solicitudes. Espera un momento antes de reintentar."
    MediaFailure.CONNECTION -> "No pudimos conectar. La foto aún no está confirmada; revisa tu conexión y reintenta."
    MediaFailure.UNAVAILABLE -> "El servicio de fotos no está disponible. Inténtalo de nuevo más tarde."
    MediaFailure.INVALID_RESPONSE -> "No pudimos comprobar la respuesta del servicio. Actualiza o reintenta."
}
