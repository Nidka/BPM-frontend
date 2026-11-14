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
        state.exportReady?.let { file -> saver.save(file.fileName, "text/csv", file.text, viewModel::exportHandled) }
    }
    Column(modifier.fillMaxWidth().widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportTab.entries.forEach { tab ->
                FilterChip(selected = state.tab == tab, onClick = { viewModel.selectTab(tab) }, label = { Text(tab.label) }, modifier = Modifier.heightIn(min = 48.dp))
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
        Text("Período", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 30, 90).forEach { days ->
                FilterChip(selected = state.presetDays == days, onClick = { vm.selectPreset(days) }, label = { Text("Últimos $days días") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            FilterChip(selected = false, onClick = vm::selectCurrentMonth, label = { Text("Este mes") }, modifier = Modifier.heightIn(min = 48.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BpmTextField(state.from, vm::editFrom, "Desde (AAAA-MM-DD)", Modifier.weight(1f), error = state.periodError?.let { ReportPeriodRules.message(it, state.maxDays) })
            BpmTextField(state.to, vm::editTo, "Hasta (AAAA-MM-DD)", Modifier.weight(1f))
        }
        Text("Máximo ${state.maxDays} días en esta vista.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (locations.size > 1) {
            Text("Sede", style = MaterialTheme.typography.labelMedium)
            BpmChoiceRow("Todas las sedes", state.locationId == null) { vm.selectLocation(null) }
            locations.forEach { BpmChoiceRow(it.name, state.locationId == it.id) { vm.selectLocation(it.id) } }
        }
        RaisedButton("Consultar", vm::load, Modifier.widthIn(max = 320.dp).fillMaxWidth(), enabled = !state.loading)
    }
}

@Composable
private fun ExportCard(state: ReportsState, vm: ReportsViewModel) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Exportar historial", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        Text("Cumplimiento del período", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(formatPercent(report.average), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("${report.runs} controles finalizados entre ${longDate(report.from)} y ${longDate(report.to)}", style = MaterialTheme.typography.bodySmall)
        if (report.days.isEmpty()) Text("No hay controles finalizados en este período.")
        else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !line, onClick = { line = false }, label = { Text("Barras") }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(selected = line, onClick = { line = true }, label = { Text("Línea") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            ComplianceChart(report.days, line)
            DayTable(report.days)
        }
    }
    if (report.locations.isNotEmpty()) {
        Text("Por sede", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        BpmCardGrid(report.locations, minCardWidth = 400.dp, maxColumns = 2) { location, cardModifier ->
            BpmCard(cardModifier) {
                Text(location.locationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("Fecha", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text("Controles", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Text("Cumplimiento", Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider()
        days.sortedBy { it.date }.forEach { day ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(longDate(day.date), Modifier.weight(1f))
                Text(day.runs.toString(), Modifier.weight(1f))
                Text(formatPercent(day.average), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SummaryContent(summary: SummaryReport) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Resumen de la empresa", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(formatPercent(summary.average), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
        Text("${summary.runs} controles finalizados entre ${longDate(summary.from)} y ${longDate(summary.to)}", style = MaterialTheme.typography.bodySmall)
    }
    BpmCardGrid(listOf(
        "Incidencias" to "${summary.incidents.reported} reportadas · ${summary.incidents.closed} cerradas · ${summary.incidents.openNow} abiertas hoy",
        "Temperaturas" to "${summary.temperatures.measurements} mediciones · ${summary.temperatures.outOfRange} fuera de rango",
    ), minCardWidth = 300.dp, maxColumns = 2) { (title, text), cardModifier ->
        BpmCard(cardModifier) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(text)
        }
    }
    if (summary.locations.isNotEmpty()) {
        Text("Por sede", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        BpmCardGrid(summary.locations, minCardWidth = 400.dp, maxColumns = 2) { location, cardModifier ->
            BpmCard(cardModifier) {
                Text(location.locationName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${formatPercent(location.average)} · ${location.runs} controles")
                PercentBar(location.average)
                Text("Incidencias: ${location.incidentsReported} reportadas, ${location.incidentsClosed} cerradas, ${location.incidentsOpenNow} abiertas", style = MaterialTheme.typography.bodySmall)
                Text("Temperaturas: ${location.temperatureMeasurements} mediciones, ${location.temperatureOutOfRange} fuera de rango", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (summary.templates.isNotEmpty()) {
        Text("Por plantilla", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        BpmCardGrid(summary.templates, minCardWidth = 400.dp, maxColumns = 2) { template, cardModifier ->
            BpmCard(cardModifier) {
                Text(template.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${template.area} · ${formatPercent(template.average)} · ${template.runs} controles", style = MaterialTheme.typography.bodySmall)
                PercentBar(template.average)
            }
        }
    }
    if (summary.areas.isNotEmpty()) {
        Text("Por área", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        BpmCardGrid(summary.areas, minCardWidth = 260.dp, maxColumns = 3) { area, cardModifier ->
            BpmCard(cardModifier) {
                Text(area.area, fontWeight = FontWeight.Bold)
                Text("${formatPercent(area.average)} · ${area.runs} controles", style = MaterialTheme.typography.bodySmall)
                PercentBar(area.average)
            }
        }
    }
}
