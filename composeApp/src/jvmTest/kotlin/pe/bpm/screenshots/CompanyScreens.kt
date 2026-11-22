package pe.bpm.screenshots

import pe.bpm.features.workspace.domain.Location
import pe.bpm.features.workspace.domain.Member
import pe.bpm.features.workspace.domain.Organization
import pe.bpm.features.workspace.domain.Workspace
import pe.bpm.features.workspace.domain.WorkspaceFormDraft
import pe.bpm.features.workspace.presentation.WorkspaceFormState
import pe.bpm.features.workspace.presentation.WorkspaceDestination
import pe.bpm.features.workspace.presentation.WorkspaceFormKind
import pe.bpm.screenshots.WorkerFixtures as F

/**
 * Empresa, Equipo y los formularios de alta del shell de ADMIN para el loop de diseño. Usan un workspace con varias
 * sedes y personas para que las rejillas y las filas se vean con datos reales.
 */
private val locations = listOf(
    F.location,
    Location("loc-2", "Sede San Isidro", "Calle Los Cedros 120", "San Isidro", "01 555 0188", "America/Lima"),
    Location("loc-3", "Cocina central", "Av. Industrial 980", "Ate", "", "America/Lima"),
)

private val members = listOf(
    Member("m1", "Lucía Quispe", "lucia.quispe@cocinaandina.pe", "ADMIN", emptyList()),
    Member("m2", "Marco Huamán Ríos", "marco.huaman@cocinaandina.pe", "CHEF", listOf(F.SITE, "loc-2")),
    Member("m3", "Rosa Mamani", "rosa.mamani@cocinaandina.pe", "WORKER", listOf("loc-3")),
    Member("m4", "Diego Fernández Castillo de la Torre", "diego.fernandez@cocinaandina.pe", "WORKER", emptyList()),
)

private val team = Workspace(
    organization = Organization(F.ORG, "Inversiones Gastronómicas Andina S.A.C.", "Cocina Andina", contactEmail = "contacto@cocinaandina.pe", contactPhone = "01 555 0100"),
    role = "ADMIN", locations = locations, members = members, courses = emptyList(), checklists = emptyList(), incidents = emptyList(),
    totals = mapOf("locations" to 3, "members" to 4),
)

internal val companyScreens: List<ScreenSpec> = listOf(
    ScreenSpec("empresa") { { Shell(WorkspaceDestination.Company, role = "ADMIN", team = team) } },
    ScreenSpec("equipo") { { Shell(WorkspaceDestination.Team, role = "ADMIN", team = team) } },
    ScreenSpec("form-persona") { { Shell(WorkspaceDestination.Team, role = "ADMIN", team = team, form = WorkspaceFormKind.Member, formDraft = WorkspaceFormState(draft = WorkspaceFormDraft("d1"))) } },
    ScreenSpec("form-sede") { { Shell(WorkspaceDestination.Company, role = "ADMIN", team = team, form = WorkspaceFormKind.Location, formDraft = WorkspaceFormState(draft = WorkspaceFormDraft("d2"))) } },
    ScreenSpec("form-organizacion") { { Shell(WorkspaceDestination.Company, role = "ADMIN", team = team, form = WorkspaceFormKind.Organization, formDraft = WorkspaceFormState(draft = WorkspaceFormDraft("d3"))) } },
)
