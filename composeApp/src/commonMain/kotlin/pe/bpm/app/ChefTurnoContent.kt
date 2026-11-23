package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.checklists.domain.ChecklistAssignment
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsState
import pe.bpm.features.operations.presentation.OperationsViewModel
import pe.bpm.features.operations.presentation.clockOf
import pe.bpm.features.operations.presentation.shiftProgress
import pe.bpm.features.operations.presentation.shiftTitle
import pe.bpm.features.reports.presentation.DashboardState
import pe.bpm.features.training.domain.AssignmentProgress
import pe.bpm.features.training.presentation.TrainingLearnerViewModel
import pe.bpm.features.training.presentation.nextAssignment
import pe.bpm.features.workspace.domain.Location
import pe.bpm.features.workspace.domain.Member
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done

/** Cuántas personas muestra Turno antes de remitir a «Equipo». */
private const val TEAM_LIMIT = 4

/**
 * Pantalla «Turno» del jefe de cocina (CHEF): responde «¿cómo va mi turno y qué falta controlar?». De arriba abajo: el
 * nombre del turno con la sede, la franja «Requiere atención» (controles vencidos e incidencias abiertas del panel de
 * indicadores del servidor y cursos vencidos propios; sin motivos pasa a «Todo al día»), tres indicadores (tareas del
 * turno, controles e incidencias), «Controles de hoy» con «Empezar» y «Tu equipo». En el panel derecho (desde 1280): su
 * aprendizaje y las incidencias. Solo datos reales: lo que el servidor no entrega (avance por persona, cursos vencidos del
 * equipo) no se muestra ni se inventa.
 */
@Composable
internal fun ChefTurnoContent(
    userId: String,
    organizationId: String,
    locations: List<Location>,
    team: List<Member>,
    learner: TrainingLearnerViewModel,
    operations: OperationsViewModel,
    assignments: ChecklistAssignmentsState,
    onRefreshAssignments: () -> Unit,
    dashboard: DashboardState,
    onRefreshDashboard: () -> Unit,
    onOpenControl: (ChecklistAssignment) -> Unit,
    onControls: () -> Unit,
    onTeam: () -> Unit,
    onIncidents: () -> Unit,
    onLearn: () -> Unit,
    onContinueCourse: (String) -> Unit,
    onStudySurvey: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Abre el reporte de un problema (cualquier rol puede reportar, sin categorías creadas). */
    onReport: (() -> Unit)? = null,
    /** Abre el buscador de normativa; va en la columna principal, con el mismo ancho que las demás tarjetas. */
    onNormative: (() -> Unit)? = null,
) {
    var siteId by rememberSaveable(organizationId) { mutableStateOf<String?>(null) }
    val site = locations.firstOrNull { it.id == siteId } ?: locations.firstOrNull()
    LaunchedEffect(userId, organizationId) { learner.open(userId, organizationId, member = true) }
    LaunchedEffect(userId, organizationId, site?.id) {
        if (site != null) { operations.open(userId, organizationId, "CHEF", site.id); operations.refreshToday() }
    }
    val learning by learner.state.collectAsStateWithLifecycle()
    val ops by operations.state.collectAsStateWithLifecycle()
    val ready = site != null && ops.userId == userId && ops.siteId == site.id && ops.todayLoaded
    val plans = if (ready) ops.todayPlans else emptyList()
    val shift = shiftProgress(plans)
    val scopedAssignments = assignments.takeIf { it.userId == userId && it.organizationId == organizationId }
    val siteControls = scopedAssignments?.items.orEmpty().filter { site == null || it.locationId == site.id }
    val (controlsDone, controlsTotal) = controlsDone(siteControls)
    val kpis = dashboard.kpis?.takeIf { dashboard.organizationId == organizationId }
    val overdueCourses = if (learning.assignmentsLoaded) learning.assignments.count { it.status == AssignmentProgress.OVERDUE } else null
    val attention = chefAttention(kpis?.pendingChecks?.overdue, kpis?.openIncidents, overdueCourses)
    val next = nextAssignment(learning.assignments)
    val title = shiftTitle(plans).let { if (it == "Tu turno") "Turno de hoy" else it }

    BpmPlayColumns(modifier, rail = {
        LearnCard(next, learning.assignmentsLoaded, onLearn, onContinueCourse, title = "Mi aprendizaje")
        IncidentsCard(kpis?.openIncidents, onIncidents)
    }) { railVisible ->
        FirstStepsCard()
        pe.bpm.features.research.presentation.StudyPromptCard()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(title, style = BpmPlayType.h1, color = BpmPlayColors.ink, modifier = Modifier.weight(1f, fill = false).semantics { heading() })
            if (site != null && locations.size == 1) BpmPlayChip(site.name, BpmPlayTone.Action, uppercase = false)
        }
        if (locations.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            locations.forEach { place -> BpmFilterChip(selected = place.id == site?.id, onClick = { siteId = place.id }, label = { Text(place.name) }) }
        }
        if (kpis != null || attention.isNotEmpty()) BpmAttentionBar(
            attention.map { entry ->
                BpmAttentionItem(entry.text, when (entry.kind) {
                    ChefAttentionKind.OverdueChecks -> onControls
                    ChefAttentionKind.OpenIncidents -> onIncidents
                    ChefAttentionKind.OverdueCourses -> onLearn
                })
            },
            calmText = "Sin controles vencidos ni incidencias abiertas.",
        )
        val tasksTile: @Composable (Modifier) -> Unit = { m ->
            BpmKpiTile("Tareas del turno", if (shift.total > 0) "${shift.done}/${shift.total}" else null, m, BpmPlayTone.Action, progress = shift.progress.takeIf { shift.total > 0 })
        }
        val controlsTile: @Composable (Modifier) -> Unit = { m ->
            BpmKpiTile("Controles", if (controlsTotal > 0) "$controlsDone/$controlsTotal" else null, m, BpmPlayTone.Gold,
                progress = (controlsDone.toFloat() / controlsTotal).takeIf { controlsTotal > 0 })
        }
        val incidentsTile: @Composable (Modifier) -> Unit = { m ->
            BpmKpiTile("Incidencias", kpis?.openIncidents?.toString(), m, if ((kpis?.openIncidents ?: 0) > 0) BpmPlayTone.Error else BpmPlayTone.Correct,
                support = kpis?.openIncidents?.let { if (it == 1) "abierta" else if (it == 0) "ninguna abierta" else "abiertas" })
        }
        // En teléfono tres indicadores no caben en una fila sin partir sus etiquetas: el primero va solo y los otros dos juntos.
        if (dashboard.loading && kpis == null) BpmSkeletonKpis(count = 3)
        else if (currentWindowClass == BpmWindowClass.Compact) {
            tasksTile(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                controlsTile(Modifier.weight(1f).fillMaxHeight()); incidentsTile(Modifier.weight(1f).fillMaxHeight())
            }
        } else Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tasksTile(Modifier.weight(1f).fillMaxHeight()); controlsTile(Modifier.weight(1f).fillMaxHeight()); incidentsTile(Modifier.weight(1f).fillMaxHeight())
        }
        dashboard.failure?.takeIf { kpis == null }?.let {
            Text(pe.bpm.features.reports.presentation.reportFailureMessage(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmChunkyButton("Volver a intentar", onRefreshDashboard, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }

        Text("Controles de hoy", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        when {
            scopedAssignments?.failure != null -> {
                Text(pe.bpm.features.checklists.presentation.assignmentFailureMessage(scopedAssignments.failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow)
                BpmChunkyButton("Reintentar", onRefreshAssignments, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
            scopedAssignments == null || scopedAssignments.loading && scopedAssignments.items.isEmpty() ->
                BpmSkeletonList(rows = 3, label = "Buscando los controles de hoy")
            siteControls.none { it.active } -> BpmPlayEmpty(Res.drawable.mascot_done, "Sin controles programados",
                "Tu administrador puede programar los controles de tu sede. Mientras, revisa tus tareas y tu equipo.")
            else -> {
                val ordered = controlOrder(siteControls, userId)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ordered.forEach { item ->
                        val state = controlState(item, userId)
                        ChefControlRow(item, state, controlDetail(item, userId) { clockOf(runCatching { displayDateTime(it) }.getOrDefault("")) }, onOpen = { onOpenControl(item) })
                    }
                }
                BpmChunkyButton("Ver todos los controles", onControls, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
            }
        }

        Text("Tu equipo", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        if (team.isEmpty()) Text("Todavía no hay personas para mostrar en tu equipo.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                team.take(TEAM_LIMIT).forEach { member -> BpmPersonRow(member.name, detail = memberLine(member, locations), onClick = onTeam) }
            }
            BpmChunkyButton("Ver equipo", onTeam, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        onReport?.let { report -> BpmChunkyButton("Reportar un problema", report, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, icon = BpmSymbol.Alert) }
        onNormative?.let { open -> NormativeTile(open) }
        if (!railVisible) {
            LearnCard(next, learning.assignmentsLoaded, onLearn, onContinueCourse, title = "Mi aprendizaje")
            IncidentsCard(kpis?.openIncidents, onIncidents)
        }
    }
}

/** Incidencias abiertas de las sedes del jefe: cuántas hay y el acceso a atenderlas (Incidencias vive dentro de Turno y Controles). */
@Composable
internal fun IncidentsCard(open: Int?, onIncidents: () -> Unit, modifier: Modifier = Modifier) {
    BpmPlayCard(modifier) {
        Text("Incidencias", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        Text(
            when {
                open == null -> "Consulta lo reportado y atiende las correcciones."
                open == 0 -> "No hay incidencias abiertas."
                open == 1 -> "Hay 1 incidencia abierta. Atiéndela y valida el cierre."
                else -> "Hay $open incidencias abiertas. Atiéndelas y valida el cierre."
            },
            style = BpmPlayType.sub, color = BpmPlayColors.muted,
        )
        BpmChunkyButton(if ((open ?: 0) > 0) "Atender" else "Ver incidencias", onIncidents, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
}
