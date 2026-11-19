package pe.bpm.features.reports.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.FileSaveResult
import pe.bpm.core.presentation.rememberTextFileSaver
import pe.bpm.features.reports.domain.*

/** Sede elegible en los filtros; la raíz adapta las sedes del workspace sin que reportes dependa de ellas. */
data class ReportLocationOption(val id: String, val name: String)

/** Cumplimiento por día y sede, resumen por período y exportación CSV (RF-16/17/28). */
@Composable
fun ReportsScreen(
    viewModel: ReportsViewModel,
    state: ReportsState,
    locations: List<ReportLocationOption>,
    modifier: Modifier = Modifier,
    extra: @Composable () -> Unit = {},
) {
    val saver = rememberTextFileSaver()
    LaunchedEffect(state.exportReady) {
        state.exportReady?.let { file -> saver.save(file.fileName, "text/csv", file.text) { result -> viewModel.exportHandled(result == FileSaveResult.SAVED) } }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportTab.entries.forEach { tab ->
                BpmFilterChip(selected = state.tab == tab, onClick = { viewModel.selectTab(tab) }, label = { Text(tab.label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        PeriodFilters(state, locations, viewModel)
        ExportCard(state, viewModel)
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let { failure ->
            BpmCard(Modifier.fillMaxWidth()) {
                Text(reportFailureMessage(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (failure.retryable) BpmSecondaryButton(onClick = viewModel::load) { Text("Reintentar") }
            }
        }
        if (state.tab == ReportTab.COMPLIANCE) state.compliance?.let { ComplianceContent(it) }
        else state.summary?.let { SummaryContent(it) }
        extra()
    }
}

@Composable
private fun PeriodFilters(state: ReportsState, locations: List<ReportLocationOption>, vm: ReportsViewModel) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Período", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { days ->
                BpmFilterChip(selected = state.presetDays == days, onClick = { vm.selectPreset(days) }, label = { Text("Últimos $days días") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            BpmFilterChip(selected = false, onClick = vm::selectCurrentMonth, label = { Text("Este mes") }, modifier = Modifier.heightIn(min = 48.dp))
        }
        BpmFieldPair(
            { m -> BpmTextField(state.from, vm::editFrom, "Desde (AAAA-MM-DD)", m, error = state.periodError?.let { ReportPeriodRules.message(it, state.maxDays) }) },
            { m -> BpmTextField(state.to, vm::editTo, "Hasta (AAAA-MM-DD)", m) },
        )
        Text("Máximo ${state.maxDays} días en esta vista.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (locations.size > 1) {
            Text("Sede", style = MaterialTheme.typography.labelMedium)
            BpmChoiceRow("Todas las sedes", state.locationId == null) { vm.selectLocation(null) }
            locations.forEach { BpmChoiceRow(it.name, state.locationId == it.id) { vm.selectLocation(it.id) } }
        }
        RaisedButton("Consultar", vm::load, Modifier.widthIn(min = 160.dp), enabled = !state.loading)
    }
}

@Composable
private fun ExportCard(state: ReportsState, vm: ReportsViewModel) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Exportar historial", style = MaterialTheme.typography.titleMedium)
        Text("Descarga un archivo CSV con los controles finalizados del período (hasta 366 días). No incluye nombres de personas.")
        BpmSecondaryButton(onClick = vm::export, enabled = !state.exporting) { Text(if (state.exporting) "Preparando archivo…" else "Exportar CSV") }
        if (state.exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.exportFailure?.let { Text(reportFailureMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.exportNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

@Composable
private fun ComplianceContent(report: ComplianceReport) {
    var line by remember { mutableStateOf(false) }
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Cumplimiento del período", style = MaterialTheme.typography.titleMedium)
        Text(formatPercent(report.average), style = MaterialTheme.typography.headlineMedium)
        Text("${report.runs} controles finalizados entre ${longDate(report.from)} y ${longDate(report.to)}", style = MaterialTheme.typography.bodySmall)
        // Estado vacío dentro de la tarjeta: dato secundario en gris y con el siguiente paso (sin anidar otra tarjeta).
        if (report.days.isEmpty()) Text("No hay controles finalizados en este período. Prueba con un rango de fechas más amplio.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BpmFilterChip(selected = !line, onClick = { line = false }, label = { Text("Barras") }, modifier = Modifier.heightIn(min = 48.dp))
                BpmFilterChip(selected = line, onClick = { line = true }, label = { Text("Línea") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            ComplianceChart(report.days, line)
            DayTable(report.days)
        }
    }
    if (report.locations.isNotEmpty()) {
        Text("Por sede", style = MaterialTheme.typography.titleLarge)
        BpmGridLayout(report.locations) { location, cardModifier ->
            BpmCard(cardModifier) {
                Text(location.locationName, style = MaterialTheme.typography.titleMedium)
                Text("${formatPercent(location.average)} · ${location.runs} controles")
                PercentBar(location.average)
                if (location.days.isNotEmpty()) {
                    ComplianceChart(location.days, line = true)
                }
            }
        }
    }
}

/** Tabla equivalente al gráfico: fecha, controles y promedio. */
@Composable
private fun DayTable(days: List<DayCompliance>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            // Pesos desiguales: la fecha y «Cumplimiento» necesitan más ancho que el conteo de controles.
            Text("Fecha", Modifier.weight(1.2f), style = BpmType.labelStrong)
            Text("Controles", Modifier.weight(0.8f), style = BpmType.labelStrong)
            Text("Cumplimiento", Modifier.weight(1.1f), style = BpmType.labelStrong)
        }
        HorizontalDivider()
        days.sortedBy { it.date }.forEach { day ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(longDate(day.date), Modifier.weight(1.2f))
                Text(day.runs.toString(), Modifier.weight(0.8f))
                Text(formatPercent(day.average), Modifier.weight(1.1f))
            }
        }
    }
}

@Composable
private fun SummaryContent(summary: SummaryReport) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Resumen de la empresa", style = MaterialTheme.typography.titleMedium)
        Text(formatPercent(summary.average), style = MaterialTheme.typography.headlineMedium)
        Text("${summary.runs} controles finalizados entre ${longDate(summary.from)} y ${longDate(summary.to)}", style = MaterialTheme.typography.bodySmall)
    }
    BpmGridLayout(listOf(
        "Incidencias" to "${summary.incidents.reported} reportadas · ${summary.incidents.closed} cerradas · ${summary.incidents.openNow} abiertas hoy",
        "Temperaturas" to "${summary.temperatures.measurements} mediciones · ${summary.temperatures.outOfRange} fuera de rango",
    )) { (title, text), cardModifier ->
        BpmCard(cardModifier) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text)
        }
    }
    if (summary.locations.isNotEmpty()) {
        Text("Por sede", style = MaterialTheme.typography.titleLarge)
        BpmGridLayout(summary.locations) { location, cardModifier ->
            BpmCard(cardModifier) {
                Text(location.locationName, style = MaterialTheme.typography.titleMedium)
                Text("${formatPercent(location.average)} · ${location.runs} controles")
                PercentBar(location.average)
                Text("Incidencias: ${location.incidentsReported} reportadas, ${location.incidentsClosed} cerradas, ${location.incidentsOpenNow} abiertas", style = MaterialTheme.typography.bodySmall)
                Text("Temperaturas: ${location.temperatureMeasurements} mediciones, ${location.temperatureOutOfRange} fuera de rango", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (summary.templates.isNotEmpty()) {
        Text("Por plantilla", style = MaterialTheme.typography.titleLarge)
        BpmGridLayout(summary.templates) { template, cardModifier ->
            BpmCard(cardModifier) {
                Text(template.name, style = MaterialTheme.typography.titleMedium)
                Text("${template.area} · ${formatPercent(template.average)} · ${template.runs} controles", style = MaterialTheme.typography.bodySmall)
                PercentBar(template.average)
            }
        }
    }
    if (summary.areas.isNotEmpty()) {
        Text("Por área", style = MaterialTheme.typography.titleLarge)
        BpmGridLayout(summary.areas) { area, cardModifier ->
            BpmCard(cardModifier) {
                Text(area.area, style = BpmType.bodyStrong)
                Text("${formatPercent(area.average)} · ${area.runs} controles", style = MaterialTheme.typography.bodySmall)
                PercentBar(area.average)
            }
        }
    }
}
