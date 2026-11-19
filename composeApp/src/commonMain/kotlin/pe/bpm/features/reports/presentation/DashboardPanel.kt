package pe.bpm.features.reports.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.reports.domain.Kpis

/**
 * Panel de indicadores de la pantalla de inicio (RF-30). El bloque de capacitación solo aparece si el
 * servidor lo entrega (nulo para jefaturas de sede).
 */
@Composable
fun DashboardPanel(state: DashboardState, onRetry: () -> Unit, onOpenReports: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
        Text("Indicadores", style = MaterialTheme.typography.titleLarge)
        if (state.loading && state.kpis == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure ->
            BpmCard(Modifier.fillMaxWidth()) {
                Text(reportFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (failure.retryable) BpmSecondaryButton(onClick = onRetry) { Text("Reintentar") }
            }
        }
        state.kpis?.let { KpiGrid(it) }
        BpmSecondaryButton(onClick = onOpenReports) { Text("Ver reportes y constancias") }
    }
}

@Composable
private fun KpiGrid(kpis: Kpis) {
    val cards = buildList<Pair<String, Pair<String, String>>> {
        add("Cumplimiento de hoy" to (formatPercent(kpis.today.average) to runsText(kpis.today.runs)))
        add("Últimos 7 días" to (formatPercent(kpis.last7Days.average) to runsText(kpis.last7Days.runs)))
        add("Últimos 30 días" to (formatPercent(kpis.last30Days.average) to runsText(kpis.last30Days.runs)))
        add("Controles pendientes" to (kpis.pendingChecks.pending.toString() to "${kpis.pendingChecks.overdue} atrasados de ${kpis.pendingChecks.assigned} programados"))
        add("Alertas abiertas" to (kpis.openAlerts.toString() to "Sin atender"))
        add("Incidencias abiertas" to (kpis.openIncidents.toString() to "Sin cerrar"))
        kpis.training?.let { add("Avance de capacitación" to (formatPercent(it.completionPercent) to "${it.completedEnrollments} completados, ${it.inProgressEnrollments} en curso")) }
    }
    BpmPanelLayout(indicators = cards.map { (title, value) -> @Composable { m: Modifier -> BpmStatTile(value.first, title, m, support = value.second) } }) {}
}

private fun runsText(runs: Int) = if (runs == 1) "1 control finalizado" else "$runs controles finalizados"
