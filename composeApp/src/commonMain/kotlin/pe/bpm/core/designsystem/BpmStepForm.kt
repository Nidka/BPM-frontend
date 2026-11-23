package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

/*
 * Formulario por pasos (specs/LAYOUT.md §4.7): divide una creación con varias decisiones dependientes en pasos
 * cortos de una sola pregunta, con avance visible, «Atrás» y «Siguiente», y la acción final en el último paso.
 * El estado del paso lo conserva quien llama (rememberSaveable); el componente no conoce el dominio.
 */

/**
 * Un paso: [title] es la pregunta que responde («¿A quién invitas?»), [description] la ayuda breve y
 * [canContinue] indica si ya puede avanzar; si no, [blockedHint] explica qué falta.
 */
class BpmFormStep(
    val title: String,
    val description: String? = null,
    val canContinue: Boolean = true,
    val blockedHint: String? = null,
    val content: @Composable ColumnScope.() -> Unit,
)

/** Reglas puras del formulario por pasos, separadas para probarlas sin interfaz. */
object BpmStepFormRules {
    /** Paso válido dentro de [total] (un paso que desaparece, por ejemplo «Sedes», no deja el índice fuera). */
    fun clamp(current: Int, total: Int): Int = current.coerceIn(0, (total - 1).coerceAtLeast(0))

    fun isLast(current: Int, total: Int): Boolean = clamp(current, total) == total - 1

    /** «Siguiente» en los pasos intermedios; la acción del formulario («Enviar invitación») en el último. */
    fun primaryLabel(current: Int, total: Int, submitLabel: String): String = if (isLast(current, total)) submitLabel else "Siguiente"

    /** Texto del avance que se ve y que anuncia el lector de pantalla. */
    fun progressLabel(current: Int, total: Int, title: String): String = "Paso ${clamp(current, total) + 1} de $total · $title"

    /** Primer paso que tiene un error del servidor, para volver a él al enviar; `null` si ninguno. */
    fun firstStepWithError(errorSteps: List<Boolean>): Int? = errorSteps.indexOfFirst { it }.takeIf { it >= 0 }
}

/**
 * Formulario por pasos en la columna de 720 dp: barra gruesa de avance arriba, la pregunta del paso con sus campos en una
 * tarjeta y botones gruesos abajo. En el primer paso la secundaria es «Cancelar» ([onCancel]); en los demás, «Atrás». En el último
 * paso la principal es [submitLabel] y llama a [onSubmit].
 */
@Composable
fun BpmStepForm(
    steps: List<BpmFormStep>,
    current: Int,
    onStepChange: (Int) -> Unit,
    submitLabel: String,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    busyLabel: String = "Guardando…",
    submitEnabled: Boolean = true,
    onCancel: (() -> Unit)? = null,
    header: (@Composable ColumnScope.() -> Unit)? = null,
) {
    if (steps.isEmpty()) return
    val index = BpmStepFormRules.clamp(current, steps.size)
    val step = steps[index]
    val last = BpmStepFormRules.isLast(index, steps.size)
    BpmFormLayout(modifier, actions = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            if (!step.canContinue && step.blockedHint != null)
                Text(step.blockedHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BpmFormActions(
                BpmStepFormRules.primaryLabel(index, steps.size, submitLabel),
                onPrimary = { if (last) onSubmit() else onStepChange(index + 1) },
                enabled = step.canContinue && (!last || submitEnabled),
                busy = busy, busyLabel = busyLabel,
                onCancel = if (index > 0) ({ onStepChange(index - 1) }) else onCancel,
                cancelLabel = if (index > 0) "Atrás" else "Cancelar",
            )
        }
    }) {
        header?.invoke(this)
        // Barra gruesa de avance arriba (como Personal): «PASO N DE M» y la barra del color de acción.
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            // El lector de pantalla anuncia el cambio de paso sin mover el foco.
            Text(BpmStepFormRules.progressLabel(index, steps.size, step.title).uppercase(), style = BpmPlayType.label,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmChunkyProgress((index + 1).toFloat() / steps.size, Modifier.fillMaxWidth(), BpmPlayTone.Action,
                contentDescription = "Paso ${index + 1} de ${steps.size}")
        }
        // Una pregunta por paso: el título grande y, debajo, los campos en una tarjeta.
        key(index) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
                Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                    Text(step.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    step.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                BpmCard(Modifier.fillMaxWidth(), content = step.content)
            }
        }
    }
}
