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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
 * Resumen de una línea del navegador: «5 por responder» o, con todas contestadas, «Todas respondidas · lista para
 * enviar». No lista números de pregunta (las fichas ya lo muestran). [answered] tiene un elemento por pregunta.
 */
fun questionNavigatorSummary(answered: List<Boolean>): String {
    if (answered.isEmpty()) return "Sin preguntas"
    val missing = answered.count { !it }
    return if (missing == 0) "Todas respondidas · lista para enviar" else "$missing por responder"
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

/**
 * Hoja inferior con el navegador de preguntas (clase compacta y media): título, [BpmQuestionNavigator] con las fichas
 * en varias líneas y la ranura [reviewAction] para «Revisar y enviar». Al elegir una pregunta se abre y se cierra la hoja.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BpmQuestionNavigatorSheet(
    answered: List<Boolean>,
    current: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    reviewAction: @Composable () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = BpmSpacing.gutter(currentWindowClass)).padding(bottom = BpmSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
            Text("Tus preguntas", style = MaterialTheme.typography.titleLarge)
            BpmQuestionNavigator(answered, current, { onSelect(it); onDismiss() }, wrap = true)
            reviewAction()
        }
    }
}

/** Botón-chip «Pregunta 3 de 8 ▾» que abre [BpmQuestionNavigatorSheet]; el área táctil mide 48 dp. */
@Composable
fun BpmQuestionPickerChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    BpmFeedback(scheme.primary) {
        Row(
            modifier.heightIn(min = BpmSpacing.touch).clip(CircleShape).border(2.dp, scheme.outline, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = BpmSpacing.md),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs),
        ) {
            Text(label, style = BpmType.bodySmallStrong, color = scheme.onSurface)
            BpmIcon(BpmSymbol.ChevronDown, tint = scheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}
