package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Estado de un paso de [BpmStepList]. */
enum class BpmStepState { Done, Current, Pending }

/**
 * Paso de una lista numerada. [badge] es un texto breve a la derecha (por ejemplo «Incluye video») y
 * [highlight] marca un paso especial (la evaluación final) con el círculo en tono de advertencia.
 */
data class BpmStep(
    val id: String,
    val title: String,
    val detail: String? = null,
    val state: BpmStepState = BpmStepState.Pending,
    val badge: String? = null,
    val highlight: Boolean = false,
)

/**
 * Lista de pasos con círculo numerado y línea conectora. Hecho = círculo con visto verde; actual = círculo
 * primario relleno y fila con fondo suave; pendiente = círculo con contorno. Cada fila mide al menos 48 dp y es
 * clicable solo si se pasa [onStep]. [dense] reduce círculo y márgenes (temario lateral).
 */
@Composable
fun BpmStepList(
    steps: List<BpmStep>,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    onStep: ((BpmStep) -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            StepRow(step, number = index + 1, first = index == 0, last = index == steps.lastIndex, dense = dense, onStep = onStep)
        }
    }
}

@Composable
private fun StepRow(step: BpmStep, number: Int, first: Boolean, last: Boolean, dense: Boolean, onStep: ((BpmStep) -> Unit)?) {
    val circle = if (dense) 28.dp else 32.dp
    val gap = if (dense) BpmSpacing.xs else BpmSpacing.sm
    val line = MaterialTheme.colorScheme.outlineVariant
    val current = step.state == BpmStepState.Current
    val stateText = when (step.state) {
        BpmStepState.Done -> "Completado"
        BpmStepState.Current -> "Actual"
        BpmStepState.Pending -> "Pendiente"
    }
    val shape = RoundedCornerShape(12.dp)
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = BpmSpacing.touch).clip(shape)
                .background(if (current) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .then(if (onStep != null) Modifier.clickable(role = Role.Button) { onStep(step) } else Modifier)
                .semantics(mergeDescendants = true) { stateDescription = stateText }
                .padding(horizontal = BpmSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md),
        ) {
            // Riel: tramo de línea, círculo y tramo hasta el final de la fila; así la línea es continua entre filas.
            Column(Modifier.width(circle), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.width(2.dp).height(gap).background(if (first) Color.Transparent else line))
                StepCircle(step, number, circle)
                Box(Modifier.width(2.dp).weight(1f).background(if (last) Color.Transparent else line))
            }
            Column(Modifier.weight(1f).padding(vertical = gap), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Text(step.title, style = if (dense) BpmType.bodyMediumStrong else BpmType.bodyStrong)
                step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            step.badge?.let { Box(Modifier.align(Alignment.CenterVertically)) { BpmStatusChip(it, BpmTone.Neutral) } }
        }
    }
}

@Composable
private fun StepCircle(step: BpmStep, number: Int, size: androidx.compose.ui.unit.Dp) {
    val scheme = MaterialTheme.colorScheme
    val highlighted = step.highlight && step.state != BpmStepState.Done
    val fill: Color
    val border: Color
    val content: Color
    when {
        step.state == BpmStepState.Done -> { fill = BpmTokens.successSurface; border = BpmTokens.successText; content = BpmTokens.successText }
        highlighted -> { fill = BpmTokens.warningSurface; border = BpmTokens.warningText; content = BpmTokens.warningText }
        step.state == BpmStepState.Current -> { fill = scheme.primary; border = scheme.primary; content = scheme.onPrimary }
        else -> { fill = scheme.surface; border = scheme.outline; content = scheme.onSurfaceVariant }
    }
    Box(Modifier.size(size).clip(CircleShape).background(fill).border(2.dp, border, CircleShape), contentAlignment = Alignment.Center) {
        when {
            step.state == BpmStepState.Done -> BpmIcon(BpmSymbol.Check, tint = content, modifier = Modifier.size(16.dp))
            highlighted -> BpmIcon(BpmSymbol.Award, tint = content, modifier = Modifier.size(16.dp))
            else -> Text(number.toString(), color = content,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"))
        }
    }
}
