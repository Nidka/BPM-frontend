package pe.bpm.app

// patrón: modo enfoque de juego (BpmPlayStage), como la lección y el reporte; el shell no pone cabecera.

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import pe.bpm.core.designsystem.*
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_class
import pe.bpm.resources.mascot_done
import pe.bpm.resources.mascot_fix
import pe.bpm.resources.mascot_learning
import pe.bpm.resources.mascot_ready
import pe.bpm.resources.mascot_welcome

private fun mascot(name: String): DrawableResource = when (name) {
    "ready" -> Res.drawable.mascot_ready
    "learning" -> Res.drawable.mascot_learning
    "fix" -> Res.drawable.mascot_fix
    "done" -> Res.drawable.mascot_done
    "class" -> Res.drawable.mascot_class
    else -> Res.drawable.mascot_welcome
}

/**
 * «Primeros pasos»: introducción corta de la primera vez (4 o 5 tarjetas con el tomate, en español simple). Se puede saltar en
 * cualquier momento («Saltar» o la X), cada tarjeta se puede escuchar y los puntos dicen en qué paso va. [onFinish] se llama al
 * terminar o saltar (la raíz recuerda que ya se vio; se repite desde «Ayuda»).
 */
@Composable
internal fun OnboardingIntro(role: GuideRole, onFinish: () -> Unit, modifier: Modifier = Modifier) {
    val cards = introCards(role)
    var index by rememberSaveable { mutableIntStateOf(0) }
    val card = cards[index.coerceIn(0, cards.lastIndex)]
    val last = index >= cards.lastIndex
    BpmPlayStage(
        modifier.bpmPlayKeys(0, {}, { if (last) onFinish() else index += 1 }, focusKey = index),
        onClose = onFinish, closeDescription = "Saltar la introducción", steps = cards.size, step = index + 1, centered = true, scrollKey = index,
        footer = {
            BpmChunkyButton(if (last) "Empezar" else "Siguiente", { if (last) onFinish() else index += 1 }, Modifier.fillMaxWidth())
            if (!last) BpmChunkyButton("Saltar", onFinish, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            else if (index > 0) BpmChunkyButton("Anterior", { index -= 1 }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        },
    ) {
        Image(painterResource(mascot(card.image)), contentDescription = null, modifier = Modifier.width(150.dp).align(Alignment.CenterHorizontally))
        Text(card.title, style = BpmPlayType.h1, color = BpmPlayColors.ink, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
        Text(card.body, style = BpmPlayType.text, color = BpmPlayColors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        BpmListenButton(card.title + ". " + card.body, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}

/** Lista «Primeros pasos» vigente y qué hacer al tocar un paso u ocultarla; la raíz la provee a Hoy, Turno e Inicio. */
class FirstStepsState(val steps: List<FirstStep>, val onGo: (FirstStep) -> Unit, val onHide: () -> Unit)

val LocalFirstSteps = compositionLocalOf<FirstStepsState?> { null }

/**
 * Tarjeta «Primeros pasos»: «2 de 4», una barra y los pasos del rol; cada paso lleva a donde se hace y se marca solo al
 * cumplirse (o al abrirlo). Se puede ocultar y vuelve desde «Ayuda › Ver primeros pasos otra vez». Sin pasos no ocupa espacio.
 */
@Composable
internal fun FirstStepsCard(modifier: Modifier = Modifier) {
    val state = LocalFirstSteps.current ?: return
    if (state.steps.isEmpty()) return
    val done = state.steps.doneCount()
    val total = state.steps.size
    BpmPlayCard(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Primeros pasos", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.weight(1f).semantics { heading() })
            BpmPlayChip("$done de $total", if (done == total) BpmPlayTone.Correct else BpmPlayTone.Gold)
        }
        BpmChunkyProgress(done.toFloat() / total, Modifier.fillMaxWidth(), BpmPlayTone.Correct, contentDescription = "Primeros pasos: $done de $total")
        if (done == total) Text("¡Listo! Ya hiciste tus primeros pasos.", style = BpmPlayType.sub, color = BpmPlayColors.correct.shadow)
        // En teléfono la lista ocupaba media pantalla: solo se muestran los pasos pendientes (los hechos cuentan en «2 de 4»).
        val compact = currentWindowClass == BpmWindowClass.Compact
        state.steps.filter { !compact || !it.done || done == total }.forEach { step -> StepRow(step) { state.onGo(step) } }
        BpmChunkyButton(if (done == total) "Listo, ocultar" else "Ocultar por ahora", state.onHide, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
}

@Composable
private fun StepRow(step: FirstStep, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = BpmSpacing.touch).clip(RoundedCornerShape(12.dp))
            .semantics(mergeDescendants = true) { stateDescription = if (step.done) "Hecho" else "Pendiente"; contentDescription = "${step.title}. ${step.detail}" }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BpmIcon(if (step.done) BpmSymbol.Check else BpmSymbol.Target, tint = if (step.done) BpmPlayColors.correct.face else BpmPlayColors.action.face, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(step.title, style = BpmPlayType.rowTitle, color = if (step.done) BpmPlayColors.correct.shadow else BpmPlayColors.ink)
            Text(step.detail, style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
        }
        // En teléfono la ficha «Pendiente» quita ancho al texto: lo pendiente lo dice el icono de meta y el lector de pantalla («Pendiente»).
        if (step.done || currentWindowClass != BpmWindowClass.Compact) BpmPlayChip(if (step.done) "Hecho" else "Pendiente", if (step.done) BpmPlayTone.Correct else BpmPlayTone.Gold)
    }
}
