package pe.bpm.features.research.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.research.domain.*

enum class ResearchTab { CONSENT, INSTRUMENTS, RESULTS, NEEDS, ACCESS }

data class ConsentForm(val version: String = "", val title: String = "", val text: String = "", val investigator: String = "", val showIssues: Boolean = false) {
    fun toDocument() = NewConsentDocument(version.trim(), title.trim(), text.trim(), investigator.trim())
    val valid: Boolean
        get() = toDocument().let {
            ResearchLimits.versionPattern.matches(it.version) && it.title.isNotEmpty() && it.title.length <= ResearchLimits.TITLE_MAX &&
                it.text.isNotEmpty() && it.text.length <= ResearchLimits.CONSENT_TEXT_MAX && it.responsibleInvestigator.isNotEmpty() &&
                it.responsibleInvestigator.length <= ResearchLimits.INVESTIGATOR_MAX
        }
}

data class ResearchState(
    val userId: String? = null,
    val accessLoading: Boolean = false,
    val access: ResearchAccess? = null,
    val failure: ResearchFailure? = null,
    val tab: ResearchTab? = null,
    val grants: List<AccessGrant> = emptyList(),
    val grantsLoaded: Boolean = false,
    val grantsLoading: Boolean = false,
    val grantsFailure: ResearchFailure? = null,
    val grantEmail: String = "",
    val grantEmailInvalid: Boolean = false,
    val grantBusy: Boolean = false,
    val revokingUserId: String? = null,
    val documents: List<ConsentDocumentItem> = emptyList(),
    val documentsLoaded: Boolean = false,
    val documentsLoading: Boolean = false,
    val documentsFailure: ResearchFailure? = null,
    val documentForm: ConsentForm? = null,
    val documentBusy: Boolean = false,
    val documentFailure: ResearchFailure? = null,
    val notice: String? = null,
) {
    /** Pestañas visibles según la concesión; el servidor autoriza cada operación. */
    val tabs: List<ResearchTab>
        get() = buildList {
            if (access?.canResearch == true) addAll(listOf(ResearchTab.CONSENT, ResearchTab.INSTRUMENTS, ResearchTab.RESULTS, ResearchTab.NEEDS))
            if (access?.canManageGrants == true) add(ResearchTab.ACCESS)
        }
    val hasAccess: Boolean get() = tabs.isNotEmpty()
}

/**
 * Sección de investigación. Consulta `GET /research/access` para decidir si mostrarla y agrupa
 * concesiones (SUPER_ADMIN), consentimiento, instrumentos, resultados y necesidades.
 */
class ResearchViewModel(
    private val loadAccess: LoadResearchAccess,
    private val grantsUseCase: ManageAccessGrants,
    private val documentsUseCase: ManageConsentDocuments,
    instrumentsUseCase: ManageInstruments,
    parser: InstrumentImportParser,
    analysisUseCase: AnalyzeResearch,
    now: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(ResearchState())
    val state: StateFlow<ResearchState> = mutable.asStateFlow()
    val instruments = InstrumentsController(instrumentsUseCase, parser, viewModelScope)
    val analysis = AnalysisController(analysisUseCase, now, viewModelScope)
    private var generation = 0L
    private var accessJob: Job? = null
    private var grantsJob: Job? = null
    private var documentsJob: Job? = null

    fun reset() {
        generation++
        listOf(accessJob, grantsJob, documentsJob).forEach { it?.cancel() }
        mutable.value = ResearchState()
        instruments.reset()
        analysis.reset()
    }

    /** Consulta la concesión del usuario; se repite solo si cambió la persona o falló antes. */
    fun loadAccess(userId: String) {
        val s = mutable.value
        if (s.userId == userId && (s.accessLoading || s.access != null)) return
        if (s.userId != userId) reset()
        mutable.value = mutable.value.copy(userId = userId, accessLoading = true, failure = null)
        val expected = generation
        accessJob?.cancel()
        accessJob = viewModelScope.launch {
            val result = loadAccess()
            ensureActive()
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> mutable.value.copy(accessLoading = false, access = result.value)
                is ResearchResult.Failure -> mutable.value.copy(accessLoading = false, failure = result.reason)
            }
            selectTab(mutable.value.tab?.takeIf { it in mutable.value.tabs } ?: mutable.value.tabs.firstOrNull())
        }
    }

    fun retry() {
        val userId = mutable.value.userId ?: return
        mutable.value = mutable.value.copy(access = null, accessLoading = false)
        loadAccess(userId)
    }

    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }

    fun selectTab(tab: ResearchTab?) {
        if (tab == null || tab !in mutable.value.tabs) { mutable.value = mutable.value.copy(tab = null); return }
        mutable.value = mutable.value.copy(tab = tab, notice = null)
        when (tab) {
            ResearchTab.CONSENT -> loadDocuments()
            ResearchTab.INSTRUMENTS -> instruments.load()
            ResearchTab.RESULTS -> analysis.loadResults()
            ResearchTab.NEEDS -> { analysis.loadOrganizations(); analysis.loadNeeds() }
            ResearchTab.ACCESS -> loadGrants()
        }
    }

    // ---- Concesiones -------------------------------------------------------------------------

    fun loadGrants(force: Boolean = false) {
        val s = mutable.value
        if (s.access?.canManageGrants != true || s.grantsLoading || (s.grantsLoaded && !force)) return
        mutable.value = s.copy(grantsLoading = true, grantsFailure = null)
        val expected = generation
        grantsJob?.cancel()
        grantsJob = viewModelScope.launch {
            val result = grantsUseCase.list()
            ensureActive()
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> mutable.value.copy(grantsLoading = false, grantsLoaded = true, grants = result.value)
                is ResearchResult.Failure -> mutable.value.copy(grantsLoading = false, grantsFailure = result.reason)
            }
        }
    }

    fun editGrantEmail(email: String) { mutable.value = mutable.value.copy(grantEmail = email.take(254), grantEmailInvalid = false, grantsFailure = null) }

    /** Concede (o reactiva) el acceso por correo; la operación es idempotente en el servidor. */
    fun grant() {
        val s = mutable.value
        if (s.grantBusy) return
        val email = s.grantEmail.trim()
        mutable.value = s.copy(grantBusy = true, grantsFailure = null, notice = null)
        val expected = generation
        grantsJob?.cancel()
        grantsJob = viewModelScope.launch {
            val result = grantsUseCase.grant(email)
            ensureActive()
            if (expected != generation) return@launch
            val current = mutable.value.copy(grantBusy = false)
            when (result) {
                is ResearchResult.Success -> {
                    mutable.value = current.copy(grantEmail = "", notice = "Concedimos acceso de investigación a ${result.value.email}.",
                        grants = (listOf(result.value) + current.grants.filterNot { it.userId.equals(result.value.userId, ignoreCase = true) }))
                }
                is ResearchResult.Failure -> mutable.value = current.copy(
                    grantEmailInvalid = result.reason == ResearchFailure.INVALID, grantsFailure = result.reason.takeIf { it != ResearchFailure.INVALID })
            }
        }
    }

    fun askRevoke(userId: String) { mutable.value = mutable.value.copy(revokingUserId = userId) }

    fun cancelRevoke() { mutable.value = mutable.value.copy(revokingUserId = null) }

    fun confirmRevoke() {
        val s = mutable.value
        val userId = s.revokingUserId ?: return
        if (s.grantBusy) return
        mutable.value = s.copy(grantBusy = true, revokingUserId = null, grantsFailure = null)
        val expected = generation
        grantsJob?.cancel()
        grantsJob = viewModelScope.launch {
            val result = grantsUseCase.revoke(userId)
            ensureActive()
            if (expected != generation) return@launch
            val current = mutable.value.copy(grantBusy = false)
            when (result) {
                is ResearchResult.Success -> {
                    mutable.value = current.copy(notice = "Retiramos el acceso de investigación.")
                    loadGrants(force = true)
                }
                is ResearchResult.Failure -> mutable.value = current.copy(grantsFailure = result.reason)
            }
        }
    }

    // ---- Documentos de consentimiento --------------------------------------------------------

    fun loadDocuments(force: Boolean = false) {
        val s = mutable.value
        if (s.documentsLoading || (s.documentsLoaded && !force)) return
        mutable.value = s.copy(documentsLoading = true, documentsFailure = null)
        val expected = generation
        documentsJob?.cancel()
        documentsJob = viewModelScope.launch {
            val result = documentsUseCase.list()
            ensureActive()
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is ResearchResult.Success -> mutable.value.copy(documentsLoading = false, documentsLoaded = true, documents = result.value)
                is ResearchResult.Failure -> mutable.value.copy(documentsLoading = false, documentsFailure = result.reason)
            }
        }
    }

    fun startDocument() { mutable.value = mutable.value.copy(documentForm = ConsentForm(), documentFailure = null, notice = null) }

    fun editDocument(change: (ConsentForm) -> ConsentForm) {
        val form = mutable.value.documentForm ?: return
        if (!mutable.value.documentBusy) mutable.value = mutable.value.copy(documentForm = change(form), documentFailure = null)
    }

    fun cancelDocument() { if (!mutable.value.documentBusy) mutable.value = mutable.value.copy(documentForm = null) }

    /** Publica un consentimiento nuevo: el vigente se archiva y todos los participantes deberán aceptarlo de nuevo. */
    fun publishDocument() {
        val s = mutable.value
        val form = s.documentForm ?: return
        if (s.documentBusy) return
        if (!form.valid) { mutable.value = s.copy(documentForm = form.copy(showIssues = true)); return }
        mutable.value = s.copy(documentBusy = true, documentFailure = null)
        val expected = generation
        documentsJob?.cancel()
        documentsJob = viewModelScope.launch {
            val result = documentsUseCase.publish(form.toDocument())
            ensureActive()
            if (expected != generation) return@launch
            val current = mutable.value.copy(documentBusy = false)
            when (result) {
                is ResearchResult.Success -> {
                    mutable.value = current.copy(documentForm = null, notice = "Publicamos la versión ${result.value.version} del consentimiento.")
                    loadDocuments(force = true)
                }
                is ResearchResult.Failure -> mutable.value = current.copy(documentFailure = result.reason)
            }
        }
    }

    /** Devuelve `true` si el regreso se resolvió dentro de la sección. */
    fun back(): Boolean {
        val s = mutable.value
        if (s.documentForm != null) { cancelDocument(); return true }
        if (instruments.closeEditor()) return true
        if (instruments.state.value.import != null) { instruments.cancelImport(); return true }
        if (analysis.state.value.needForm != null) { analysis.cancelNeed(); return true }
        return false
    }

    override fun onCleared() { generation++ }
}
