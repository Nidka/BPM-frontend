package pe.bpm.app

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*

/**
 * Avisos que van arriba de la ejecución de un control por pasos: la salida con cambios sin guardar (conservar el
 * borrador, descartarlo o seguir) y el avance de la preparación sin conexión. Mismas decisiones que la pantalla de siempre.
 */
@Composable
internal fun ColumnScope.ChefRunNotices(
    exitPending: Boolean,
    exitBusy: Boolean,
    exitOpen: Boolean,
    offlineBusy: Boolean,
    offlineFailure: String?,
    offlineDraftFailed: String?,
    onKeepDraft: () -> Unit,
    onDiscard: () -> Unit,
    onContinue: () -> Unit,
) {
    if (exitOpen) BpmPlayCard {
        Text(if (exitPending || exitBusy) "Hay una operación sin confirmar" else "Hay respuestas sin guardar", style = BpmPlayType.h2, color = BpmPlayColors.ink)
        Text("Puedes conservar el borrador en este dispositivo y retomarlo después con tu misma cuenta.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        BpmChunkyButton("Conservar borrador y salir", onKeepDraft, Modifier.fillMaxWidth())
        if (!exitPending && !exitBusy) BpmChunkyButton("Descartar cambios y salir", onDiscard, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        BpmChunkyButton("Continuar con el control", onContinue, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
    if (offlineBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    offlineFailure?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow) }
    offlineDraftFailed?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow) }
}
