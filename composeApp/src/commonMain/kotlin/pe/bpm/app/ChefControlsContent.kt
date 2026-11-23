package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.checklists.domain.ChecklistAssignment
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsState
import pe.bpm.features.checklists.presentation.assignmentFailureMessage
import pe.bpm.features.operations.presentation.clockOf
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done

/** Un acceso de la sección Controles (Insumos y limpieza, Incidencias, En este dispositivo…). */
internal class ChefControlLink(val symbol: BpmSymbol, val title: String, val description: String, val onClick: () -> Unit)

/**
 * Pantalla «Controles» del jefe de cocina: los controles programados del periodo (vencidos, en curso y por hacer primero;
 * los hechos al final) con «Empezar» / «Continuar» / «Ver», y los accesos de la sección: Insumos y limpieza, **Incidencias**,
 * revisiones guardadas en el dispositivo, historial y otras plantillas. Desde 1280 los accesos van en el panel derecho.
 */
@Composable
internal fun ChefControlsContent(
    userId: String,
    assignments: ChecklistAssignmentsState,
    links: List<ChefControlLink>,
    onRefresh: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onOpenControl: (ChecklistAssignment) -> Unit,
    modifier: Modifier = Modifier,
    /** Qué decir cuando no hay controles programados: el jefe espera a su administrador; el administrador los programa. */
    emptyMessage: String = "Tu administrador puede programar los controles de tus sedes.",
    title: String = "Controles de hoy",
    moreTitle: String = "Más en Controles",
    /** Columna alineada a la izquierda con su cabecera (administrador) y bloque inicial opcional, p. ej. los botones de programación. */
    alignStart: Boolean = false,
    top: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val scoped = assignments.takeIf { it.userId == userId }
    BpmPlayColumns(modifier, rail = { links.forEach { LinkTile(it) } }, alignStart = alignStart) { railVisible ->
        top?.invoke(this)
        Text(title, style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        when {
            scoped?.failure != null -> {
                Text(assignmentFailureMessage(scoped.failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow)
                BpmChunkyButton("Reintentar", onRefresh, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
            scoped == null || scoped.loading && scoped.items.isEmpty() -> LinearProgressIndicator(Modifier.fillMaxWidth())
            scoped.items.none { it.active } -> BpmPlayEmpty(Res.drawable.mascot_done, "Sin controles programados", emptyMessage)
            else -> {
                if (scoped.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    controlOrder(scoped.items, userId).forEach { item ->
                        ChefControlRow(item, controlState(item, userId), controlDetail(item, userId) { clockOf(runCatching { displayDateTime(it) }.getOrDefault("")) },
                            onOpen = { onOpenControl(item) })
                    }
                }
                PageNavigation(scoped.previous.size + 1, scoped.previous.isNotEmpty(), scoped.nextToken.isNotEmpty(), scoped.loading, onPrevious, onNext)
            }
        }
        if (!railVisible) {
            Text(moreTitle, style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            links.forEach { LinkTile(it) }
        }
    }
}

@Composable
private fun LinkTile(link: ChefControlLink) = BpmActionTile(link.symbol, link.title, link.description, link.onClick, Modifier.fillMaxWidth())
