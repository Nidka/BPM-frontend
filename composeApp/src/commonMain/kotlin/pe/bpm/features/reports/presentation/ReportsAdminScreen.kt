package pe.bpm.features.reports.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.reports.domain.*

/** Capacitación consolidada (RF-09), constancias (RF-39) y configuración (RF-29); solo administración. */
@Composable
fun ReportsAdminScreen(viewModel: ReportsAdminViewModel, state: AdminState, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminTab.entries.forEach { tab ->
                BpmFilterChip(selected = state.tab == tab, onClick = { viewModel.selectTab(tab) }, label = { Text(tab.label) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        when (state.tab) {
            AdminTab.TRAINING -> TrainingSection(viewModel, state)
            AdminTab.CERTIFICATES -> CertificatesSection(viewModel, state)
            AdminTab.SETTINGS -> SettingsSection(viewModel, state)
        }
    }
}

@Composable
private fun Failure(failure: ReportFailure, onRetry: () -> Unit, fields: Map<String, String> = emptyMap()) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text(reportFailureMessage(failure, fields), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (failure.retryable) BpmChunkyButton("Reintentar", onRetry, tone = BpmChunkyTone.White)
    }
}

@Composable
private fun TrainingSection(vm: ReportsAdminViewModel, state: AdminState) {
    Text("Avance por curso", style = MaterialTheme.typography.titleLarge)
    if (state.coursesLoading) BpmSkeletonList(rows = 3)
    state.coursesFailure?.let { Failure(it, vm::loadCourseRows) }
    state.courses?.let { courses ->
        if (courses.isEmpty()) EmptyState("Sin cursos publicados", "Cuando publiques un curso verás aquí el avance de tu equipo.")
        BpmGridLayout(courses) { course, cardModifier ->
            BpmCard(cardModifier) {
                Text(course.title, style = MaterialTheme.typography.titleMedium)
                Text(course.code, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${formatPercent(course.completionPercent)} completado")
                PercentBar(course.completionPercent)
                Text("${course.completed} completaron · ${course.inProgress} en curso · ${course.enrolled} inscritas de ${course.assignable}", style = MaterialTheme.typography.bodySmall)
                if (course.averageBestScore != null) Text("Puntaje promedio: ${formatPercent(course.averageBestScore)}", style = MaterialTheme.typography.bodySmall)
                BpmChunkyButton("Ver personas de este curso", { vm.filterCourse(course.courseId) }, tone = BpmChunkyTone.White)
            }
        }
        Text("Avance por persona", style = MaterialTheme.typography.titleLarge)
        if (state.courseFilter != null) {
            Text("Mostrando un curso: ${courses.firstOrNull { it.courseId == state.courseFilter }?.title.orEmpty()}")
            BpmTextButton(onClick = { vm.filterCourse(null) }) { Text("Ver todos los cursos") }
        }
    }
    if (state.peopleLoading) BpmSkeletonList(rows = 3)
    state.peopleFailure?.let { Failure(it, vm::loadFirstPeople) }
    if (state.peopleLoaded && state.people.isEmpty()) EmptyState("Sin personas para mostrar", "No hay personas activas con cursos publicados.")
    BpmGridLayout(state.people) { person, cardModifier ->
        BpmCard(cardModifier) {
            Text(person.displayName, style = BpmType.bodyStrong)
            Eyebrow(roleName(person.role))
            Text("${person.completed} de ${person.publishedCourses} cursos completados · ${person.inProgress} en curso", style = MaterialTheme.typography.bodySmall)
            if (person.averageBestScore != null) Text("Puntaje promedio: ${formatPercent(person.averageBestScore)}", style = MaterialTheme.typography.bodySmall)
            Text(person.lastActivityAt?.let { "Última actividad: ${longDate(it)}" } ?: "Sin actividad", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.peopleToken.isNotEmpty()) BpmChunkyButton("Ver más personas", vm::loadMorePeople, tone = BpmChunkyTone.White, enabled = !state.peopleLoading)
}

@Composable
private fun SettingsSection(vm: ReportsAdminViewModel, state: AdminState) {
    if (state.settingsLoading) BpmSkeletonList(rows = 3)
    state.settingsFailure?.let { Failure(it, vm::loadSettingsNow) }
    if (state.settings == null) return
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Configuración de reportes", style = MaterialTheme.typography.titleMedium)
        BpmTextField(state.thresholdText, vm::editThreshold, "Umbral de constancia (1 a 100 %)", Modifier.widthIn(max = 320.dp), // medida: campo numérico corto (1 a 100)
            error = if (state.thresholdInvalid) "Escribe un número entre 1 y 100 con hasta 2 decimales." else null,
            supportingText = "Una constancia solo se emite si el cumplimiento promedio del período alcanza este valor.")
        BpmCheckboxRow("Enviar el correo semanal de cumplimiento a las personas administradoras", state.weeklyEnabled) { vm.toggleWeekly(it) }
        BpmChunkyButton(if (state.settingsSaving) "Guardando…" else "Guardar configuración", vm::saveSettingsNow, Modifier.bpmPrimaryButtonWidth(), enabled = !state.settingsSaving && state.settingsDirty)
        if (state.settingsSaved) Text("Configuración guardada.", color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun CertificatesSection(vm: ReportsAdminViewModel, state: AdminState) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Solicitar constancia de cumplimiento", style = MaterialTheme.typography.titleMedium)
        Text("Se calcula con todas las sedes del período. El período debe haber terminado antes de hoy.")
        BpmFieldPair(
            { m -> BpmTextField(state.certFrom, vm::editCertFrom, "Desde (AAAA-MM-DD)", m,
                error = state.certPeriodError?.let { ReportPeriodRules.message(it, ReportPeriodRules.SUMMARY_MAX_DAYS) }) },
            { m -> BpmTextField(state.certTo, vm::editCertTo, "Hasta (AAAA-MM-DD)", m) },
        )
        BpmChunkyButton(if (state.issuing) "Solicitando…" else "Solicitar constancia", vm::issue, Modifier.bpmPrimaryButtonWidth(), enabled = !state.issuing)
        state.issueFailure?.let { Text(reportFailureMessage(it, state.issueFields), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        state.issued?.let {
            Text(if (it.created) "Constancia emitida. Código: ${it.certificate.verificationCode}" else "Ese período ya tenía una constancia. Código: ${it.certificate.verificationCode}",
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
    Text("Constancias emitidas", style = MaterialTheme.typography.titleLarge)
    if (state.certificatesLoading) BpmSkeletonList(rows = 3)
    state.certificatesFailure?.let { Failure(it, { vm.loadCertificates(false) }) }
    if (state.certificatesLoaded && state.certificates.isEmpty()) EmptyState("Aún no hay constancias", "Cuando solicites una constancia aparecerá en esta lista.")
    BpmGridLayout(state.certificates) { certificate, cardModifier ->
        BpmCard(cardModifier) {
            Text("${longDate(certificate.periodStart)} al ${longDate(certificate.periodEnd)}", style = MaterialTheme.typography.titleMedium)
            Text("Cumplimiento ${formatPercent(certificate.averageCompliance)} (umbral ${formatPercent(certificate.threshold)}) · ${certificate.runs} controles")
            Text("Emitida el ${longDate(certificate.issuedAt)}", style = MaterialTheme.typography.bodySmall)
            Text("Código de verificación", style = MaterialTheme.typography.labelMedium)
            Text(certificate.verificationCode, style = BpmType.bodyStrong)
            Text("Verificación pública: /verificar-cumplimiento/${certificate.verificationCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (state.certificatesToken.isNotEmpty()) BpmChunkyButton("Ver más constancias", { vm.loadCertificates(true) }, tone = BpmChunkyTone.White, enabled = !state.certificatesLoading)
}
