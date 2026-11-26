package pe.bpm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.supportsNativeModalWindows
import pe.bpm.features.media.presentation.*

internal data class EvidenceQuestion(val runId: String, val itemId: String, val label: String)

@Composable
internal fun EvidenceQuestionAction(
    label: String,
    count: Int,
    canEdit: Boolean,
    onOpen: (Boolean) -> Unit,
) {
    BpmChunkyButton(if (count == 0) {
                if (canEdit) "Adjuntar foto" else "Consultar fotos"
            } else "${if (canEdit) "Gestionar" else "Ver"} fotos · $count", { onOpen(canEdit) }, Modifier.fillMaxWidth().semantics { contentDescription = "Fotos: $label" }, tone = BpmChunkyTone.White)
}

/** Hoja en móvil y diálogo acotado en escritorio; no desmonta el formulario de respuestas. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChecklistEvidenceHost(
    question: EvidenceQuestion,
    viewModel: MediaEvidenceViewModel,
    state: MediaEvidenceState,
    onClose: () -> Unit,
) {
    PrivateEvidenceHost(
        question.label,
        viewModel,
        state,
        "Las respuestas del control se conservan. La foto pendiente tendrá que seleccionarse de nuevo.",
        onClose = onClose,
    )
}

/** Comparte selección, confirmación y vista previa entre controles e incidencias. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PrivateEvidenceHost(
    label: String,
    viewModel: MediaEvidenceViewModel,
    state: MediaEvidenceState,
    unsavedText: String =
        "El registro se conserva. La foto pendiente tendrá que seleccionarse de nuevo.",
    autoPick: Boolean = false,
    onClose: () -> Unit,
) {
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    var confirmClose by remember(label) { mutableStateOf(false) }
    val dismiss = {
        if (!state.busy) {
            if (state.pendingId == null) onClose() else confirmClose = true
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) { // medida: ventana modal sobre toda la pantalla
        val overlayHeight = (maxHeight - 32.dp).coerceIn(0.dp, 760.dp)
        if (!supportsNativeModalWindows()) {
            val focus = remember { FocusRequester() }
            LaunchedEffect(label) { focus.requestFocus() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .pointerInput(dismiss) { detectTapGestures { dismiss() } }
                )
                Surface(
                    Modifier.padding(16.dp)
                        .widthIn(max = 640.dp) // medida: ventana modal de evidencias
                        .fillMaxWidth()
                        .heightIn(max = overlayHeight)
                        .focusRequester(focus)
                        .focusable()
                        .semantics { paneTitle = label }
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                                dismiss()
                                true
                            } else false
                        },
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column {
                        EvidenceHeading(label, !state.busy, dismiss)
                        if (confirmClose)
                            EvidenceCloseConfirmation(unsavedText, onClose, { confirmClose = false }, confirmationScroll())
                        MediaEvidencePanel(viewModel, Modifier.weight(1f, fill = false), autoPick)
                    }
                }
            }
        } else if (maxWidth < 600.dp) {
            val sheet =
                rememberModalBottomSheetState(
                    skipPartiallyExpanded = true,
                    confirmValueChange = {
                        it != SheetValue.Hidden || !state.busy && state.pendingId == null
                    },
                )
            ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheet) {
                Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).imePadding()) {
                    EvidenceHeading(label, !state.busy, dismiss)
                    if (confirmClose)
                        EvidenceCloseConfirmation(unsavedText, onClose, { confirmClose = false }, confirmationScroll())
                    MediaEvidencePanel(viewModel, Modifier.weight(1f, fill = false), autoPick)
                }
            }
        } else
            Dialog(onDismissRequest = dismiss) {
                Surface(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = 760.dp), // medida: ventana modal de evidencias
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column {
                        EvidenceHeading(label, !state.busy, dismiss)
                        if (confirmClose)
                            EvidenceCloseConfirmation(unsavedText, onClose, { confirmClose = false }, confirmationScroll())
                        MediaEvidencePanel(viewModel, Modifier.weight(1f, fill = false), autoPick)
                    }
                }
            }
    }
}

/**
 * Con altura baja (móvil horizontal) la confirmación no cabe junto a la cabecera: se desplaza para que
 * «Seguir con la foto» y «Cerrar sin guardar foto» siempre sean alcanzables. Solo se usa dentro de una `Column`.
 */
@Composable
private fun ColumnScope.confirmationScroll(): Modifier =
    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())

@Composable
private fun EvidenceHeading(label: String, enabled: Boolean, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        BpmTextButton(onClick = onClose, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Listo") }
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/** Confirmación en la misma superficie: evita diálogos anidados y conserva el foco accesible. */
@Composable
private fun EvidenceCloseConfirmation(message: String, onClose: () -> Unit, onKeep: () -> Unit, modifier: Modifier = Modifier) {
    BpmCard(
        modifier.fillMaxWidth().padding(16.dp),
        tint = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text("¿Cerrar sin guardar esta foto?", style = MaterialTheme.typography.titleMedium)
        Text(message)
        BpmTextButton(onClick = onKeep) { Text("Seguir con la foto") }
        BpmTextButton(onClick = onClose) { Text("Cerrar sin guardar foto") }
    }
}
