package pe.bpm.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import pe.bpm.app.PlatformIndicatorsActions
import pe.bpm.app.PlatformIndicatorsContent
import pe.bpm.app.PlatformOrganizationsActions
import pe.bpm.app.PlatformOrganizationsContent
import pe.bpm.app.ReportsContent
import pe.bpm.app.ResearchContent
import pe.bpm.core.designsystem.BpmPattern
import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.features.reports.FakeReportsRepository
import pe.bpm.features.reports.domain.*
import pe.bpm.features.reports.presentation.DashboardState
import pe.bpm.features.reports.presentation.ReportLocationOption
import pe.bpm.features.reports.presentation.ReportsAdminViewModel
import pe.bpm.features.reports.presentation.ReportsViewModel
import pe.bpm.features.research.domain.*
import pe.bpm.features.research.data.JsonInstrumentImportParser
import pe.bpm.features.research.presentation.ResearchTab
import pe.bpm.features.research.presentation.ResearchViewModel
import pe.bpm.features.workspace.domain.*
import pe.bpm.features.workspace.presentation.*
import pe.bpm.screenshots.WorkerFixtures as F

/*
 * Capturas de la super administración (paquete H4): no hay cuenta de prueba de ese rol, así que se dibujan las pantallas
 * reales dentro del shell real con datos de ejemplo. Se suman a las del personal en la misma corrida
 * (`./gradlew :composeApp:jvmTest --tests '*WorkerScreenshots*'`).
 */

private const val NOW_MS = 1_794_322_800_000L
private const val NOW = "2026-11-20T10:00:00Z"

private val andina = F.organization
private val panda = Organization("b2000000-0000-4000-8000-000000000002", "Panda Marino Inversiones S.A.C.", "Panda Marino", "RESTAURANT")
private val tika = Organization("b2000000-0000-4000-8000-000000000003", "Tika", "", "CAFETERIA")
private val catering = Organization("b2000000-0000-4000-8000-000000000004", "Banquetes del Sur E.I.R.L.", "Sabores del Sur", "CATERING")
private val organizations = listOf(andina, panda, tika, catering)

private val sites = listOf(
    F.location,
    Location("c3000000-0000-4000-8000-000000000002", "Sede San Isidro", "Calle Los Cedros 120", "San Isidro", "01 555 0188", "America/Lima"),
    Location("c3000000-0000-4000-8000-000000000003", "Sede Barranco", "Jr. Domeyer 210", "Barranco", "", "America/Lima"),
)

private val members = listOf(
    Member("m1", "Lucía Quispe", "lucia.quispe@cocinaandina.pe", "ADMIN", emptyList()),
    Member("m2", "Marco Huamán", "marco.huaman@cocinaandina.pe", "CHEF", listOf(sites[0].id)),
)

private val workspace = Workspace(andina.copy(taxId = "20512345678", contactEmail = "contacto@cocinaandina.pe", contactPhone = "01 555 0142"), "ADMIN", sites, members,
    F.workspace.courses, emptyList(), emptyList(), totals = mapOf("locations" to 3, "members" to 2))

private fun stateOf(orgs: List<Organization>, next: Boolean = false, withWorkspace: Boolean = true) = WorkspaceState(
    organizations = orgs, selected = if (withWorkspace) andina.id else null, workspace = if (withWorkspace) workspace else null,
    canChooseOrganization = true,
    organizationPage = WorkspacePageState(nextToken = if (next) "token" else "", request = PageRequest()),
    pages = WorkspaceResource.entries.associateWith { WorkspacePageState() },
)

private val kpis = Kpis(NOW, ComplianceWindow(88.0, 6), ComplianceWindow(84.0, 31), ComplianceWindow(81.0, 118),
    PendingChecks(14, 9, 2), openAlerts = 1, openIncidents = 2, training = TrainingKpis(5, 22, 12, 7, 57.0, 78.0))

private val dashboard = DashboardState(F.USER, andina.id, "SUPER_ADMIN", kpis = kpis)

private val compliance = ComplianceReport("2026-10-22", "2026-11-20", 118, 81.0,
    listOf(DayCompliance("2026-11-17", 5, 80.0), DayCompliance("2026-11-18", 6, 83.0), DayCompliance("2026-11-19", 4, 78.0), DayCompliance("2026-11-20", 7, 88.0)),
    listOf(LocationCompliance(sites[0].id, sites[0].name, 64, 91.0, emptyList()), LocationCompliance(sites[1].id, sites[1].name, 41, 76.0, emptyList()),
        LocationCompliance(sites[2].id, sites[2].name, 13, 58.0, emptyList())))

private fun reportsVm(): ReportsViewModel {
    val repo = FakeReportsRepository().apply { complianceResult = ReportResult.Success(compliance) }
    return ReportsViewModel(LoadCompliance(repo), LoadSummary(repo), ExportComplianceCsv(repo)) { NOW_MS }
}

private fun reportsAdminVm(): ReportsAdminViewModel {
    val repo = FakeReportsRepository()
    return ReportsAdminViewModel(LoadTrainingCourseProgress(repo), LoadTrainingPeopleProgress(repo), LoadReportSettings(repo), SaveReportSettings(repo),
        IssueComplianceCertificate(repo), ListComplianceCertificates(repo)) { NOW_MS }
}

/** Un solo doble para los tres puertos de investigación: concede a la persona acceso completo y devuelve datos de ejemplo. */
private class FakeResearch : ResearchAccessRepository, ResearchCatalogRepository, ResearchAnalysisRepository {
    override suspend fun access() = ResearchResult.Success(ResearchAccess(canResearch = true, canManageGrants = true))
    override suspend fun grants() = ResearchResult.Success(listOf(
        AccessGrant("u1", "investigadora@bpm.pe", "Dra. Elena Ríos", "2026-10-02T10:00:00Z", null, true),
        AccessGrant("u2", "analista@bpm.pe", "", "2026-09-15T10:00:00Z", "2026-11-01T10:00:00Z", false)))
    override suspend fun grant(email: String): ResearchResult<AccessGrant> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun revoke(userId: String): ResearchResult<Unit> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun consentDocuments() = ResearchResult.Success(listOf(
        ConsentDocumentItem("v2", "Consentimiento informado del estudio CAP", "Texto", "Dra. Elena Ríos", ConsentStatus.ACTIVE, "2026-10-01T10:00:00Z"),
        ConsentDocumentItem("v1", "Consentimiento informado del estudio CAP", "Texto", "Dra. Elena Ríos", ConsentStatus.ARCHIVED, "2026-08-01T10:00:00Z")))
    override suspend fun publishConsent(document: NewConsentDocument): ResearchResult<ConsentDocumentItem> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun instruments(): ResearchResult<List<InstrumentSummary>> = ResearchResult.Success(emptyList())
    override suspend fun createInstrument(instrument: NewInstrument, imported: Boolean): ResearchResult<InstrumentDraft> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun instrument(id: String): ResearchResult<InstrumentDetail> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun draft(id: String): ResearchResult<InstrumentDraft> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun saveDraft(id: String, rowVersion: String, questions: List<DraftQuestion>): ResearchResult<InstrumentDraft> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun newVersion(id: String): ResearchResult<InstrumentDraft> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun publish(id: String): ResearchResult<InstrumentVersion> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun organizations() = ResearchResult.Success(organizations.map { ResearchOrganization(it.id, it.name) })
    override suspend fun results(versionId: String?, request: PageRequest): ResearchResult<ResearchResults> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun export(versionId: String?, organizationId: String?): ResearchResult<pe.bpm.features.research.domain.CsvExport> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
    override suspend fun needs(organizationId: String?, request: PageRequest): ResearchResult<Page<DetectedNeed>> = ResearchResult.Success(Page(listOf(
        DetectedNeed("n1", andina.id, andina.name, NeedCategory.TRAINING, "El personal nuevo no recibe inducción de lavado de manos.", "2026-11-12", "Dra. Elena Ríos", "2026-11-12T10:00:00Z"))))
    override suspend fun createNeed(need: NewNeed): ResearchResult<DetectedNeed> = ResearchResult.Failure(ResearchFailure.UNAVAILABLE)
}

private fun researchVm(tab: ResearchTab): ResearchViewModel {
    val repo = FakeResearch()
    return ResearchViewModel(LoadResearchAccess(repo), ManageAccessGrants(repo), ManageConsentDocuments(repo), ManageInstruments(repo),
        JsonInstrumentImportParser(), AnalyzeResearch(repo)) { NOW }.also { it.loadAccess(F.USER); it.selectTab(tab) }
}

/** El shell real de la super administración con los mismos parámetros que `App.kt`; cada pantalla aporta su contenido. */
@Composable
private fun SuperShell(
    section: WorkspaceDestination,
    state: WorkspaceState = stateOf(organizations),
    platformHome: (@Composable () -> Unit)? = null,
    platformIndicators: (@Composable () -> Unit)? = null,
    secondaryContent: (@Composable () -> Unit)? = null,
    secondaryTitle: String? = null,
    secondaryPattern: BpmPattern = BpmPattern.Panel,
    activeShortcut: WorkspaceShortcut? = null,
    form: WorkspaceFormKind? = null,
    formState: WorkspaceFormState = WorkspaceFormState(),
    workerProfile: (@Composable () -> Unit)? = null,
) {
    WorkspaceScreen(
        viewer = WorkspaceViewer(F.USER, "Equipo BPM", "equipo@bpm.pe", true),
        state = state, section = section, onNavigate = {},
        onVerifySession = {}, sessionBusy = false, sessionMessage = null, sessionConfirmation = null,
        onSelect = {}, onRefresh = {}, onClear = {}, onLogout = {},
        formDraft = formState, onSubmitForm = {}, onFormRetryAccount = {}, onFormSelectAccount = {}, onFormEdit = {},
        onFormRetry = {}, onFormDiscard = {},
        form = form, onOpenForm = {}, onCloseForm = {},
        choosingOrganization = false, onChooseOrganization = {}, checklistContent = {}, onManageTemplates = {},
        onAlerts = {}, unreadAlertCount = 1,
        platformHome = platformHome, platformIndicators = platformIndicators, workerProfile = workerProfile,
        secondaryContent = secondaryContent, secondaryEditor = secondaryContent != null, secondaryTitle = secondaryTitle, secondaryPattern = secondaryPattern,
        shortcuts = workspaceShortcuts(null, true, canResearch = true, canManageGrants = true), activeShortcut = activeShortcut,
        onOrganizationSearch = {}, onOrganizationNext = {}, onOrganizationPrevious = {}, onOrganizationRetry = {},
        onPageSearch = { _, _ -> }, onPageNext = {}, onPagePrevious = {}, onPageRetry = {},
    )
}

private val noActions = PlatformOrganizationsActions({}, {}, {}, {}, {}, {})

@OptIn(ExperimentalCoroutinesApi::class)
internal val platformScreens: List<ScreenSpec> = listOf(
    ScreenSpec("super-organizaciones") {
        val state = stateOf(organizations)
        val content: @Composable () -> Unit = { SuperShell(WorkspaceDestination.Home, state, platformHome = { PlatformOrganizationsContent(state, dashboard, noActions) }) }
        content
    },
    ScreenSpec("super-organizaciones-paginas") {
        val state = stateOf(organizations, next = true).copy(loading = false)
        val content: @Composable () -> Unit = { SuperShell(WorkspaceDestination.Home, state, platformHome = { PlatformOrganizationsContent(state, DashboardState(), noActions) }) }
        content
    },
    ScreenSpec("super-organizaciones-vacio") {
        val state = stateOf(emptyList(), withWorkspace = false)
        val content: @Composable () -> Unit = { SuperShell(WorkspaceDestination.Home, state, platformHome = { PlatformOrganizationsContent(state, DashboardState(), noActions) }) }
        content
    },
    ScreenSpec("super-organizacion") {
        val content: @Composable () -> Unit = { SuperShell(WorkspaceDestination.Company) }
        content
    },
    ScreenSpec("super-nueva-organizacion", full = false) {
        val content: @Composable () -> Unit = { SuperShell(WorkspaceDestination.Company, form = WorkspaceFormKind.Organization, formState = WorkspaceFormState(draft = pe.bpm.features.workspace.domain.WorkspaceFormDraft("d1", name = "Sabores del Sur E.I.R.L."))) }
        content
    },
    ScreenSpec("super-indicadores") {
        val reports = reportsVm()
        val content: @Composable () -> Unit = {
            SuperShell(WorkspaceDestination.Indicators, platformIndicators = {
                PlatformIndicatorsContent(F.USER, workspace.organization, sites, dashboard, {}, reports, PlatformIndicatorsActions({}, {}, {}))
            })
        }
        content
    },
    ScreenSpec("super-indicadores-sin-organizacion") {
        val reports = reportsVm()
        val state = stateOf(emptyList(), withWorkspace = false)
        val content: @Composable () -> Unit = {
            SuperShell(WorkspaceDestination.Indicators, state, platformIndicators = {
                PlatformIndicatorsContent(F.USER, null, emptyList(), DashboardState(), {}, reports, PlatformIndicatorsActions({}, {}, {}))
            })
        }
        content
    },
    ScreenSpec("super-reportes") {
        val reports = reportsVm(); val admin = reportsAdminVm()
        val content: @Composable () -> Unit = {
            SuperShell(WorkspaceDestination.Indicators, secondaryContent = {
                ReportsContent(F.USER, andina.id, "SUPER_ADMIN", sites.map { ReportLocationOption(it.id, it.name) }, reports, admin)
            }, secondaryTitle = "Reportes")
        }
        content
    },
    ScreenSpec("super-acceso") {
        val research = researchVm(ResearchTab.ACCESS)
        val content: @Composable () -> Unit = {
            SuperShell(WorkspaceDestination.Home, secondaryContent = { ResearchContent(F.USER, research) }, secondaryTitle = "Investigación", activeShortcut = WorkspaceShortcut.Access)
        }
        content
    },
    ScreenSpec("super-acceso-consentimiento") {
        val research = researchVm(ResearchTab.CONSENT)
        val content: @Composable () -> Unit = {
            SuperShell(WorkspaceDestination.Home, secondaryContent = { ResearchContent(F.USER, research) }, secondaryTitle = "Investigación", activeShortcut = WorkspaceShortcut.Access)
        }
        content
    },
    ScreenSpec("super-acceso-necesidades") {
        val research = researchVm(ResearchTab.NEEDS)
        val content: @Composable () -> Unit = {
            SuperShell(WorkspaceDestination.Home, secondaryContent = { ResearchContent(F.USER, research) }, secondaryTitle = "Investigación", activeShortcut = WorkspaceShortcut.Access)
        }
        content
    },
    ScreenSpec("super-perfil") {
        val content: @Composable () -> Unit = {
            SuperShell(WorkspaceDestination.Account, workerProfile = {
                pe.bpm.app.ManagerProfileContent("Equipo BPM", "Administrador general", "equipo@bpm.pe", pe.bpm.core.designsystem.BpmFeedbackSettings(sound = true, vibration = false),
                    {}, {}, {}, {}, {})
            })
        }
        content
    },
)
