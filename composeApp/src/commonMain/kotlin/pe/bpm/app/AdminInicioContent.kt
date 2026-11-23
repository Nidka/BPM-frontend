package pe.bpm.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.reports.presentation.DashboardState
import pe.bpm.features.reports.presentation.ReportsViewModel
import pe.bpm.features.reports.presentation.reportFailureMessage
import pe.bpm.features.workspace.domain.Location

/** Destinos a los que lleva el Inicio del administrador; la raíz los conecta con la navegación. */
internal class AdminInicioActions(
    val onControls: () -> Unit,
    val onIncidents: () -> Unit,
    val onAlerts: () -> Unit,
    val onReports: () -> Unit,
    val onTeamReviews: () -> Unit,
    val onSchedule: () -> Unit,
    val onAssignCourse: () -> Unit,
    val onInvite: () -> Unit,
    val onNormative: () -> Unit,
    val onStudySurvey: (() -> Unit)?,
    val onSites: () -> Unit,
)

/**
 * Inicio del administrador de la organización: responde «¿qué sedes o personas requieren atención?». De arriba abajo: la
 * franja «Requiere atención» (controles vencidos, incidencias y avisos abiertos del panel de indicadores del servidor),
 * cuatro indicadores (cumplimiento, controles a tiempo, incidencias y capacitación; sin dato, «Sin datos»), las sedes como
 * filas con barra de cumplimiento (solo si el servidor tiene controles finalizados) y, en el panel derecho desde 1280, las
 * acciones rápidas y los reportes. No inventa cifras: lo que la API no entrega no se muestra.
 */
@Composable
internal fun AdminInicioContent(
    userId: String,
    organizationId: String,
    role: String,
    locations: List<Location>,
    dashboard: DashboardState,
    onRefreshDashboard: () -> Unit,
    reports: ReportsViewModel,
    actions: AdminInicioActions,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(userId, organizationId, role) { reports.open(userId, organizationId, role) }
    val report by reports.state.collectAsStateWithLifecycle()
    val kpis = dashboard.kpis?.takeIf { dashboard.organizationId == organizationId }
        val tiles = adminKpis(kpis)
    val compliance = report.compliance?.takeIf { report.organizationId == organizationId }
    val rows = locations.map { site -> adminSiteRow(site.id, site.name, site.address, compliance?.locations?.firstOrNull { it.locationId == site.id }) }

    val attention = adminAttention(kpis?.pendingChecks?.overdue, kpis?.openIncidents, kpis?.openAlerts, rows.count { it.tone == BpmPlayTone.Error })

    BpmPlayColumns(modifier, rail = { AdminRail(actions) }, alignStart = true) { railVisible ->
        if (kpis != null || attention.isNotEmpty()) BpmAttentionBar(
            attention.map { entry ->
                BpmAttentionItem(entry.text, when (entry.kind) {
                    AdminAttentionKind.OverdueChecks -> actions.onControls
                    AdminAttentionKind.OpenIncidents -> actions.onIncidents
                    AdminAttentionKind.OpenAlerts -> actions.onAlerts
                    AdminAttentionKind.LowSites -> actions.onSites
                })
            },
            calmText = "Sin controles vencidos, incidencias ni avisos abiertos, y las sedes al día.",
        )
        if (dashboard.loading && kpis == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        KpiRow(tiles)
        dashboard.failure?.takeIf { kpis == null }?.let {
            Text(reportFailureMessage(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (it.retryable) BpmChunkyButton("Volver a intentar", onRefreshDashboard, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }

        BpmPlayCard(Modifier.fillMaxWidth()) {
            Text("Sedes", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            Text("Cumplimiento de los últimos 30 días", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            if (report.loading && compliance == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (rows.isEmpty()) Text("Todavía no hay sedes. Agrega la primera en Empresa.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            Column(Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, row ->
                    BpmDataRow(row.name, subtitle = row.subtitle, progress = row.progress, value = row.value, tone = row.tone,
                        status = BpmRecordStatus(row.statusText, row.statusTone), onClick = actions.onSites, onClickLabel = "Ver sedes en Empresa",
                        divider = index < rows.lastIndex)
                }
            }
            if (rows.isNotEmpty() && compliance == null && !report.loading)
                Text("El detalle por sede aparece cuando hay controles finalizados.", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
        }
        if (!railVisible) AdminRail(actions)
    }
}

/** Cuatro indicadores: en una fila desde la clase expandida (600 dp de columna) y de dos en dos en las demás. */
@Composable
private fun KpiRow(tiles: List<AdminKpi>) {
    val tile: @Composable (AdminKpi, Modifier) -> Unit = { kpi, m ->
        BpmKpiTile(kpi.label, kpi.value, m, kpi.tone, progress = kpi.progress, support = kpi.support, featured = kpi === tiles.first())
    }
    val wide = currentWindowClass == BpmWindowClass.Expanded || currentWindowClass == BpmWindowClass.Large
    if (wide) Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
    } else tiles.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/** Panel derecho: acciones rápidas (programar control, asignar curso, invitar persona) y accesos a reportes. */
@Composable
private fun AdminRail(actions: AdminInicioActions) {
    BpmPlayCard(Modifier.fillMaxWidth()) {
        Text("Acciones rápidas", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        BpmChunkyButton("Programar control", actions.onSchedule, Modifier.fillMaxWidth())
        BpmChunkyButton("Asignar curso", actions.onAssignCourse, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        BpmChunkyButton("Invitar persona", actions.onInvite, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
    BpmPlayCard(Modifier.fillMaxWidth()) {
        Text("Reportes", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        ReportLink(BpmSymbol.Book, "Cumplimiento y resumen", actions.onReports)
        ReportLink(BpmSymbol.Award, "Equipo y constancias", actions.onReports)
        ReportLink(BpmSymbol.Control, "Revisiones del equipo", actions.onTeamReviews)
        ReportLink(BpmSymbol.Learn, "Buscar en la normativa", actions.onNormative)
        actions.onStudySurvey?.let { ReportLink(BpmSymbol.Sprout, "Encuesta del estudio", it) }
    }
}

/** Fila de acceso a un reporte (`.arow`): icono, nombre y flecha; toda la fila es el botón (48 dp). */
@Composable
private fun ReportLink(symbol: BpmSymbol, title: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = BpmSpacing.touch).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BpmIcon(symbol, tint = BpmPlayColors.action.face, modifier = Modifier.size(22.dp))
        Text(title, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink, modifier = Modifier.weight(1f))
        BpmIcon(BpmSymbol.ChevronRight, tint = BpmPlayColors.action.face, modifier = Modifier.size(20.dp))
    }
}
