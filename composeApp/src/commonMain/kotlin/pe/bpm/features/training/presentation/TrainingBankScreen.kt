package pe.bpm.features.training.presentation

// patrón: vista alojada por TrainingAdminScreen (lista de solo lectura dentro de la columna de administración)

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.*

/** Unidades del banco en el orden de aparición de sus ítems: cada una con los ítems que se practican en ella. */
internal fun bankUnits(bank: QuestionBank): List<Pair<String, List<BankItem>>> {
    val order = bank.items.map { it.lessonTitle }.distinct()
    return order.map { title -> title to bank.items.filter { it.lessonTitle == title } }
}

/** Letra A, B, C… de una alternativa. */
internal fun optionLetter(index: Int): String = "ABCDEF".getOrElse(index) { '·' }.toString()

/**
 * Banco oficial de un módulo en solo lectura (ADMIN y SUPER_ADMIN): ítems agrupados por unidad con su código, nivel de
 * Bloom (visible solo aquí), clave, justificación, fuentes y la marca de la evaluación recomendada del primer intento.
 */
@Composable
internal fun QuestionBankView(view: BankViewState, viewModel: TrainingAuthoringViewModel) {
    Eyebrow("BANCO DE PREGUNTAS · SOLO LECTURA")
    Text(view.title, style = MaterialTheme.typography.headlineMedium)
    if (view.loading) BpmSkeletonList(rows = 4, label = "Cargando el banco")
    view.failure?.let { failure ->
        Text(trainingFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        BpmChunkyButton("Volver a intentar", { viewModel.openBank(view.courseId, view.title) }, tone = BpmChunkyTone.White)
    }
    view.bank?.let { bank ->
        Text("Módulo ${bank.module} · versión ${bank.bankVersion} del banco · ${bank.items.size} ítems · aprueba con ${bank.passingScore} %",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Cada evaluación sortea 5 ítems con las alternativas en orden aleatorio. La primera usa la selección recomendada; las siguientes rotan por nivel de Bloom.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        bankUnits(bank).forEachIndexed { number, (unit, items) ->
            Text("Unidad ${number + 1} · ${unit.ifBlank { "Sin unidad" }}", style = MaterialTheme.typography.titleLarge)
            items.forEach { BankItemCard(it) }
        }
    }
    BpmTextButton(onClick = viewModel::closeBank) { Text("Volver a la lista de cursos") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BankItemCard(item: BankItem) {
    BpmCard(Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
            BpmStatusChip(item.code, BpmTone.Neutral)
            BpmStatusChip(item.bloom, BpmTone.Info)
            BpmStatusChip("Clave ${item.key}", BpmTone.Success, symbol = BpmSymbol.Check)
            if (item.recommended) BpmStatusChip("Recomendada 1.er intento", BpmTone.Warning, symbol = BpmSymbol.Star)
        }
        Text(item.questionText, style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
            item.options.forEachIndexed { index, option ->
                val correct = index == item.correctOption
                Text("${optionLetter(index)}. $option" + if (correct) " (correcta)" else "",
                    style = if (correct) BpmType.bodyStrong else MaterialTheme.typography.bodyMedium,
                    color = if (correct) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (item.justification.isNotBlank()) Text("Justificación: ${item.justification}", style = MaterialTheme.typography.bodySmall)
        sourceLine(item.sources).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
