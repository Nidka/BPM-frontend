package pe.bpm.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import pe.bpm.features.workspace.domain.Organization
import pe.bpm.features.workspace.presentation.WorkspaceState

/*
 * Pantallas de la super administración: Organizaciones (indicadores y lista de todas las organizaciones) e Indicadores
 * (los de la organización abierta). Muestran solo lo que la API entrega; las cifras que no existen dicen «Sin datos».
 */

/** Acciones de Organizaciones; la raíz las conecta con el estado del espacio de trabajo. */
internal class PlatformOrganizationsActions(
    val onOpen: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onRetry: () -> Unit,
    val onUserActivity: () -> Unit,
)

/**
 * Organizaciones: cuatro indicadores (cuántas hay y los de la organización abierta) y, debajo, todas las organizaciones
 * como filas `BpmDataRow` con búsqueda y paginación. Tocar una organización la abre; la abierta lleva su cumplimiento a
 * 30 días si el servidor lo tiene. «Nueva organización» está en la cabecera de la pantalla.
 */
@Composable
internal fun PlatformOrganizationsContent(
    state: WorkspaceState, dashboard: DashboardState, actions: PlatformOrganizationsActions, modifier: Modifier = Modifier,
) {
    val page = state.organizationPage
    val kpis = dashboard.kpis?.takeIf { dashboard.organizationId == state.selected }
    val current = state.workspace?.organization?.name ?: state.organizations.firstOrNull { it.id == state.selected }?.name
    val tiles = platformKpis(state.organizations.size, page.nextToken.isNotEmpty() || page.previous.isNotEmpty(), page.request.query.isNotBlank(), current, kpis)
    val rows = platformOrganizationRows(state.organizations, state.selected, kpis)
    val busy = state.loading || page.loading
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        FirstStepsCard()
        KpiRow(tiles)
        BpmPlayCard(Modifier.fillMaxWidth()) {
            Text("Todas las organizaciones", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            BpmSearchField(page.request.query, { actions.onUserActivity(); actions.onSearch(it.take(160)) }, "Buscar organización",
                enabled = !state.loading, loading = page.loading, fullWidth = true)
            (state.errors + page.errors).distinctBy { it.message }.forEach {
                Text(it.message, style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (page.errors.isNotEmpty()) BpmChunkyButton("Volver a intentar", actions.onRetry, tone = BpmChunkyTone.White)
            if (busy && rows.isEmpty()) BpmSkeletonList(rows = 3, card = false)
            if (rows.isEmpty() && !busy && state.errors.isEmpty() && page.errors.isEmpty())
                Text(if (page.request.query.isBlank()) "Todavía no hay organizaciones. Crea la primera con «Nueva organización»." else "No encontramos coincidencias. Prueba con otro nombre.",
                    style = BpmPlayType.sub, color = BpmPlayColors.muted)
            Column(Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, row ->
                    BpmDataRow(row.name, subtitle = row.subtitle.ifBlank { null }, progress = row.progress, value = row.value, tone = row.tone,
                        status = row.statusText?.let { BpmRecordStatus(it, row.statusTone) }, selected = row.current,
                        onClick = { actions.onOpen(row.id) }, onClickLabel = "Abrir ${row.name}", divider = index < rows.lastIndex)
                }
            }
            PageNavigation(page.number, page.previous.isNotEmpty(), page.nextToken.isNotEmpty(), busy || page.errors.isNotEmpty(), actions.onPrevious, actions.onNext)
        }
    }
}

/** Acciones de Indicadores. */
internal class PlatformIndicatorsActions(val onReports: () -> Unit, val onOrganizations: () -> Unit, val onNormative: () -> Unit)

/**
 * Indicadores de la organización abierta: la misma lectura que el Inicio del administrador (cuatro indicadores y sedes con
 * barra de cumplimiento a 30 días) más los accesos a los reportes. Sin organización abierta, lleva a Organizaciones.
 */
@Composable
internal fun PlatformIndicatorsContent(
    userId: String, organization: Organization?, locations: List<Location>, dashboard: DashboardState,
    onRefreshDashboard: () -> Unit, reports: ReportsViewModel, actions: PlatformIndicatorsActions, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (organization == null) {
            BpmPlayCard(Modifier.fillMaxWidth()) {
                Text("Elige una organización", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
                Text("Los indicadores se leen por organización. Ábrela desde la lista de Organizaciones.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
                BpmChunkyButton("Ver organizaciones", actions.onOrganizations)
            }
            return@Column
        }
        LaunchedEffect(userId, organization.id) { reports.open(userId, organization.id, "SUPER_ADMIN") }
        val report by reports.state.collectAsStateWithLifecycle()
        val kpis = dashboard.kpis?.takeIf { dashboard.organizationId == organization.id }
        val compliance = report.compliance?.takeIf { report.organizationId == organization.id }
        val rows = locations.map { site -> adminSiteRow(site.id, site.name, site.address, compliance?.locations?.firstOrNull { it.locationId == site.id }) }
        BpmPlayCard(Modifier.fillMaxWidth()) {
            Text("ORGANIZACIÓN ABIERTA", style = BpmPlayType.label, color = BpmPlayColors.muted)
            Text(organization.name, style = BpmPlayType.h1, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            Text(organizationSubtitle(organization), style = BpmPlayType.sub, color = BpmPlayColors.muted)
            BpmChunkyButton("Cambiar de organización", actions.onOrganizations, tone = BpmChunkyTone.White)
        }
        if (dashboard.loading && kpis == null) BpmSkeletonKpis() else KpiRow(adminKpis(kpis))
        dashboard.failure?.takeIf { kpis == null }?.let {
            Text(reportFailureMessage(it), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (it.retryable) BpmChunkyButton("Volver a intentar", onRefreshDashboard, tone = BpmChunkyTone.White)
        }
        BpmPlayCard(Modifier.fillMaxWidth()) {
            Text("Sedes", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            Text("Cumplimiento de los últimos 30 días", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            if (report.loading && compliance == null) BpmSkeletonList(rows = 3, card = false)
            if (rows.isEmpty()) Text("Esta organización todavía no tiene sedes.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            Column(Modifier.fillMaxWidth()) {
                rows.forEachIndexed { index, row ->
                    BpmDataRow(row.name, subtitle = row.subtitle, progress = row.progress, value = row.value, tone = row.tone,
                        status = BpmRecordStatus(row.statusText, row.statusTone), divider = index < rows.lastIndex)
                }
            }
            if (rows.isNotEmpty() && compliance == null && !report.loading)
                Text("El detalle por sede aparece cuando hay controles finalizados.", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
        }
        BpmPlayCard(Modifier.fillMaxWidth()) {
            Text("Reportes", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            BpmChunkyButton("Ver reportes y constancias", actions.onReports, Modifier.fillMaxWidth())
            BpmChunkyButton("Buscar en la normativa", actions.onNormative, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
    }
}
