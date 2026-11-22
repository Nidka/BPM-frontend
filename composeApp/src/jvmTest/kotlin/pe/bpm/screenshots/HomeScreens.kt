package pe.bpm.screenshots

import androidx.compose.runtime.Composable
import pe.bpm.features.checklists.domain.AssignedChecklistRun
import pe.bpm.features.checklists.domain.ChecklistAssignment
import pe.bpm.features.checklists.domain.ChecklistFrequency
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsScreen
import pe.bpm.features.checklists.presentation.ChecklistAssignmentsState
import pe.bpm.features.reports.domain.ComplianceWindow
import pe.bpm.features.reports.domain.Kpis
import pe.bpm.features.reports.domain.PendingChecks
import pe.bpm.features.reports.domain.TrainingKpis
import pe.bpm.features.reports.presentation.DashboardPanel
import pe.bpm.features.reports.presentation.DashboardState
import pe.bpm.features.workspace.presentation.WorkspaceDestination
import pe.bpm.screenshots.WorkerFixtures as F

/**
 * Inicio de ADMIN y CHEF (panel de indicadores, controles de hoy y el Home genérico), para el loop de diseño.
 * El de WORKER («Hoy») ya está en [workerScreens]. Los porcentajes usan la escala 0–100 que espera `formatPercent`.
 */
private fun kpis(withTraining: Boolean) = Kpis(
    generatedAt = "2026-11-20T10:00:00Z",
    today = ComplianceWindow(92.5, 4), last7Days = ComplianceWindow(88.0, 27), last30Days = ComplianceWindow(90.1, 112),
    pendingChecks = PendingChecks(assigned = 6, pending = 3, overdue = 1), openAlerts = 2, openIncidents = 1,
    training = if (withTraining) TrainingKpis(5, 14, 9, 4, 64.3, 82.0) else null,
)

private fun assignment(n: Int, name: String, done: Boolean) = ChecklistAssignment(
    id = "d$n", templateId = "t$n", templateName = name, templateVersionId = "v$n", locationId = F.SITE, locationName = "Sede Miraflores",
    frequency = ChecklistFrequency.DAILY, dueTime = "10:00", timezone = "America/Lima", active = true, createdAt = "2026-11-01T10:00:00Z",
    run = if (done) AssignedChecklistRun("r$n", "SUBMITTED", F.USER, F.DISPLAY_NAME, "2026-11-20T09:00:00Z", "2026-11-20T09:30:00Z") else null,
)

private val assignments = ChecklistAssignmentsState(
    userId = F.USER, organizationId = F.ORG,
    items = listOf(assignment(1, "Apertura de cocina", true), assignment(2, "Temperatura de cámaras", false), assignment(3, "Cierre y limpieza", false)),
)

private fun home(role: String, withTraining: Boolean): @Composable () -> Unit = {
    Shell(WorkspaceDestination.Home, role = role, homeContent = {
        DashboardPanel(DashboardState(userId = F.USER, organizationId = F.ORG, role = role, kpis = kpis(withTraining)), onRetry = {}, onOpenReports = {})
        if (role == "ADMIN") ChecklistAssignmentsScreen(assignments, false, {}, {}, {}, {}, onOpen = {}, {}, {}, title = "Controles de hoy")
    })
}

internal val homeScreens: List<ScreenSpec> = listOf(
    ScreenSpec("inicio-admin") { home("ADMIN", withTraining = true) },
    ScreenSpec("inicio-chef") { home("CHEF", withTraining = false) },
)
