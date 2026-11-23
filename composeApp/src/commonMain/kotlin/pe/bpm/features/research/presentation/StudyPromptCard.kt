package pe.bpm.features.research.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.research.domain.StudyPrompt
import pe.bpm.features.research.domain.StudyStep

/**
 * La invitación a la encuesta del estudio que toca ahora (si toca) y cómo abrirla. La raíz la calcula con
 * `studyPrompt(participation, ahora)` y la ofrece a Hoy, Turno, Inicio y Aprender; [prompt] nulo = nada que pedir.
 * [onOpen] siempre existe cuando el estudio está disponible, para enlazarlo desde el Perfil.
 */
class StudyPromptState(val prompt: StudyPrompt?, val onOpen: () -> Unit)

val LocalStudyPrompt = compositionLocalOf<StudyPromptState?> { null }

/**
 * Tarjeta de invitación: «Antes de empezar» (consentimiento), «Encuesta inicial» o «Encuesta final», con un botón y «Ahora no»
 * (la deja para después durante esta sesión; la participación es voluntaria y nunca se bloquea nada). Sin invitación no ocupa espacio.
 */
@Composable
fun StudyPromptCard(modifier: Modifier = Modifier) {
    val state = LocalStudyPrompt.current ?: return
    val prompt = state.prompt ?: return
    var snoozed by rememberSaveable(prompt.step) { mutableStateOf(false) }
    if (snoozed) return
    BpmPlayCard(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BpmPlayChip(when (prompt.step) { StudyStep.CONSENT -> "Estudio"; StudyStep.PRETEST -> "Al empezar"; StudyStep.POSTTEST -> "Al terminar" }, BpmPlayTone.Gold)
            BpmPlayChip("Voluntaria · anónima", BpmPlayTone.Action)
        }
        Text(prompt.title, style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        Text(prompt.body, style = BpmPlayType.sub, color = BpmPlayColors.muted)
        BpmChunkyButton(prompt.action, state.onOpen, Modifier.fillMaxWidth())
        BpmChunkyButton("Ahora no", { snoozed = true }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
}

/** Enlace permanente a la encuesta (Perfil/Ajustes) para quien la dejó para después o quiere ver su estado. */
@Composable
fun StudyLinkRow(modifier: Modifier = Modifier) {
    val state = LocalStudyPrompt.current ?: return
    BpmSettingLink("Encuesta del estudio", "Voluntaria y anónima. Mira tu participación o retírala.", state.onOpen, modifier, symbol = BpmSymbol.Learn)
}
