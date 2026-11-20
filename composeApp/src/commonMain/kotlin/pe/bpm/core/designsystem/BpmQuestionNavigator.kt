package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Estado visual de una pregunta en [BpmQuestionNavigator]. */
enum class BpmQuestionMark { Answered, Current, Unanswered }

/**
 * Estado de cada pregunta: la [current] (índice desde 0) se marca como actual aunque ya tenga respuesta; las
 * demás son respondidas o sin responder según [answered].
 */
fun questionMarks(answered: List<Boolean>, current: Int): List<BpmQuestionMark> = answered.mapIndexed { index, done ->
    when {
        index == current -> BpmQuestionMark.Current
        done -> BpmQuestionMark.Answered
        else -> BpmQuestionMark.Unanswered
    }
}

/**
 * Resumen de una línea: «7 de 10 respondidas · faltan 8, 9 y 10». Con más de tres faltantes se listan las tres
 * primeras y «y N más» («faltan 1, 2, 3 y 7 más»); si no falta ninguna, «Todas respondidas». [answered] tiene
 * un elemento por pregunta (la actual cuenta como respondida solo si ya tiene respuesta).
 */
fun questionNavigatorSummary(answered: List<Boolean>): String {
    val total = answered.size
    if (total == 0) return "Sin preguntas"
    val missing = answered.indices.filter { !answered[it] }.map { it + 1 }
    if (missing.isEmpty()) return "Todas respondidas"
    val done = total - missing.size
    val count = if (total == 1) "$done de $total respondida" else "$done de $total respondidas"
    val list = when {
        missing.size == 1 -> "falta ${missing[0]}"
        missing.size == 2 -> "faltan ${missing[0]} y ${missing[1]}"
        missing.size == 3 -> "faltan ${missing[0]}, ${missing[1]} y ${missing[2]}"
        else -> "faltan ${missing[0]}, ${missing[1]}, ${missing[2]} y ${missing.size - 3} más"
    }
    return "$count · $list"
}

private val ChipSize = 44.dp

/**
 * Navegador de preguntas de una evaluación: una línea de resumen, una fila de fichas numeradas de 44 dp con tres
 * estados distintos (respondida = fondo suave con visto, actual = primario relleno, sin responder = solo contorno)
 * y una leyenda de una línea. [answered] tiene un elemento por pregunta y [current] es el índice (desde 0) de la
 * pregunta abierta. Con [wrap] = false la fila se desplaza en horizontal y sigue a la pregunta actual (clase
 * compacta); con [wrap] = true las fichas saltan de línea (panel lateral). Cada ficha mide 48 dp de área táctil.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmQuestionNavigator(
    answered: List<Boolean>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    wrap: Boolean = false,
) {
    val marks = questionMarks(answered, current)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        Text(questionNavigatorSummary(answered), style = BpmType.bodySmallStrong)
        if (wrap) {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                marks.forEachIndexed { index, mark -> QuestionChip(index, mark, onSelect) }
            }
        } else {
            val state = rememberLazyListState()
            // Mantiene a la vista la pregunta actual cuando cambia con «Anterior» o «Siguiente».
            LaunchedEffect(current) {
                if (state.layoutInfo.visibleItemsInfo.none { it.index == current }) state.animateScrollToItem((current - 1).coerceAtLeast(0))
            }
            LazyRow(Modifier.fillMaxWidth(), state = state, horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                itemsIndexed(marks) { index, mark -> QuestionChip(index, mark, onSelect) }
            }
        }
        Legend()
    }
}

@Composable
private fun QuestionChip(index: Int, mark: BpmQuestionMark, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val isCurrent = mark == BpmQuestionMark.Current
    val answered = mark == BpmQuestionMark.Answered
    val description = "Pregunta ${index + 1}, " + when (mark) {
        BpmQuestionMark.Answered -> "respondida"
        BpmQuestionMark.Current -> "actual"
        BpmQuestionMark.Unanswered -> "sin responder"
    }
    BpmFeedback(scheme.primary) {
        // El área táctil mide 48 dp; la ficha visible, 44 dp, centrada.
        Box(
            Modifier.size(BpmSpacing.touch).clip(CircleShape)
                .clickable(role = Role.Button) { onSelect(index) }
                .semantics { contentDescription = description; selected = isCurrent },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(ChipSize).clip(CircleShape)
                    .background(when { isCurrent -> scheme.primary; answered -> BpmTokens.soft; else -> scheme.surface })
                    .border(2.dp, when { isCurrent -> scheme.primary; answered -> scheme.primary; else -> scheme.outline }, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text((index + 1).toString(), modifier = Modifier.clearAndSetSemantics { },
                    color = if (isCurrent) scheme.onPrimary else if (answered) scheme.primary else scheme.onSurface,
                    style = BpmType.bodyMediumStrong.copy(fontSize = 15.sp, fontFeatureSettings = "tnum"))
            }
        }
    }
}

/** Leyenda de una línea con los tres estados; solo se complementa con color, cada estado lleva su palabra. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    val scheme = MaterialTheme.colorScheme
    FlowRow(Modifier.fillMaxWidth().clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md),
        verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
        LegendItem("Respondida", BpmTokens.soft, scheme.primary)
        LegendItem("Actual", scheme.primary, scheme.primary)
        LegendItem("Sin responder", scheme.surface, scheme.outline)
    }
}

@Composable
private fun LegendItem(label: String, fill: androidx.compose.ui.graphics.Color, border: androidx.compose.ui.graphics.Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(fill).border(2.dp, border, CircleShape))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
