package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.*

@Composable
internal fun WorkspaceScreen(
    viewer: WorkspaceViewer, state: WorkspaceState, section: WorkspaceDestination,
    onNavigate: (WorkspaceDestination) -> Unit,
    onVerifySession: () -> Unit, sessionBusy: Boolean, sessionMessage: String?, sessionConfirmation: String?,
    onSelect: (String) -> Unit, onRefresh: () -> Unit,
    onClear: () -> Unit, onLogout: () -> Unit,
    formDraft: WorkspaceFormState, onSubmitForm: () -> Unit, onFormRetryAccount: () -> Unit, onFormSelectAccount: (String) -> Unit, onFormEdit: ((WorkspaceFormDraft) -> WorkspaceFormDraft) -> Unit, onFormRetry: () -> Unit, onFormDiscard: () -> Unit,
    form: WorkspaceFormKind?, onOpenForm: (WorkspaceFormKind) -> Unit, onCloseForm: () -> Unit,
    choosingOrganization: Boolean, onChooseOrganization: () -> Unit,
    checklistContent: @Composable () -> Unit,
    onManageTemplates: () -> Unit,
    secondaryContent: (@Composable () -> Unit)? = null,
    secondaryEditor: Boolean = false,
    secondaryTitle: String? = null,
    secondaryBusy: Boolean = false,
    secondaryScrollKey: String? = null,
    screenNotice: @Composable () -> Unit = {},
    noticeVisible: Boolean = false,
    onUserActivity: () -> Unit = {},
    onOrganizationSearch: (String) -> Unit, onOrganizationNext: () -> Unit, onOrganizationPrevious: () -> Unit, onOrganizationRetry: () -> Unit,
    onPageSearch: (WorkspaceResource, String) -> Unit, onPageNext: (WorkspaceResource) -> Unit,
    onPagePrevious: (WorkspaceResource) -> Unit, onPageRetry: (WorkspaceResource) -> Unit,
) {
    val available = workspaceDestinations(state.workspace?.role, viewer.canCreateOrganization)
    val destinations = workspacePrimaryDestinations(available)
    val navigate: (WorkspaceDestination) -> Unit = { if (it in available) onNavigate(it) }
    val contentScroll = rememberScrollState()
    val workspace = state.workspace
    val superAdmin = viewer.canCreateOrganization
    val open: (WorkspaceFormKind) -> Unit = { onClear(); onOpenForm(it) }
    LaunchedEffect(section, state.pages.mapValues { it.value.request }) { contentScroll.scrollTo(0) }
    LaunchedEffect(noticeVisible) { if (noticeVisible) contentScroll.scrollTo(0) }
    LaunchedEffect(secondaryScrollKey) { if (secondaryScrollKey != null) contentScroll.scrollTo(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
        val wide = maxWidth >= 950.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(232.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Brand()
                    Spacer(Modifier.height(32.dp))
                    destinations.forEach { item ->
                        BpmFeedback(MaterialTheme.colorScheme.primary) {
                        NavigationDrawerItem(label = { Text(item.label, fontWeight = FontWeight.Bold) },
                            selected = section == item, onClick = { navigate(item) },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary),
                            icon = { WorkspaceIcon(item) })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                WorkspaceTopBar(workspace?.organization?.name, wide,
                    canChangeOrganization = form == null && secondaryContent == null && !choosingOrganization && secondaryTitle == null && (state.canChooseOrganization || superAdmin),
                    enabled = !state.saving && !secondaryBusy,
                    onToggleOrganization = onChooseOrganization,
                    onBack = if (form == null && secondaryContent == null && !choosingOrganization && secondaryTitle == null) null else onCloseForm)
                if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (secondaryEditor && secondaryContent != null) Box(Modifier.weight(1f).fillMaxWidth().padding(if (wide) 32.dp else 16.dp),
                    contentAlignment = Alignment.TopCenter) { secondaryContent() }
                else if (form != null) Box(Modifier.weight(1f).fillMaxWidth().padding(if (wide) 32.dp else 16.dp),
                    contentAlignment = Alignment.TopCenter) {
                    WorkspaceForm(form, state, onClose = { if (!state.saving) { onCloseForm(); onClear() } },
                        onSubmitForm, formDraft, onFormRetryAccount, onFormSelectAccount, onFormEdit, onFormDiscard, onFormRetry, onUserActivity, onPageSearch, onPageNext, onPagePrevious, onPageRetry)
                } else if (choosingOrganization) Box(Modifier.weight(1f).fillMaxWidth().padding(if (wide) 32.dp else 16.dp),
                    contentAlignment = Alignment.TopCenter) {
                    OrganizationPickerScreen(state.organizations, state.selected, state.loading, errors = state.errors,
                        canCreate = superAdmin, onSelect = onSelect, onCreate = { open(WorkspaceFormKind.Organization) },
                        onUserActivity = onUserActivity, notice = screenNotice, noticeVisible = noticeVisible,
                        page = state.organizationPage, onSearch = onOrganizationSearch, onNext = onOrganizationNext,
                        onPrevious = onOrganizationPrevious, onRetry = onOrganizationRetry)
                } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(contentScroll).padding(if (wide) 32.dp else 20.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    screenNotice()
                    Text(secondaryTitle ?: if (section == WorkspaceDestination.Home) "Hola, ${viewer.displayName.substringBefore(' ')}" else section.label,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (workspace != null || section == WorkspaceDestination.Account) Errors(state.errors)
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    if (section == WorkspaceDestination.Account) {
                        BpmCard(Modifier.fillMaxWidth()) {
                            Text(viewer.displayName, style = MaterialTheme.typography.titleLarge)
                            Text(viewer.email)
                            Text(when {
                                viewer.canCreateOrganization -> "Administrador general"
                                workspace != null -> roleLabel(workspace.role)
                                state.loading -> "Consultando organización…"
                                state.errors.isNotEmpty() -> workspacePlaceholder(state, false).title
                                else -> "Sin organización activa"
                            })
                            if (workspace == null && !viewer.canCreateOrganization && !state.loading) {
                                Text("Si tu administrador acaba de agregarte a una organización, puedes consultar tu acceso de nuevo.", style = MaterialTheme.typography.bodySmall)
                                BpmSecondaryButton(onClick = onVerifySession, enabled = !sessionBusy) { Text(if (sessionBusy) "Consultando…" else "Consultar mi acceso") }
                            }
                            if (sessionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            sessionConfirmation?.let { Text(it, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                            sessionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            if (WorkspaceDestination.Learn in available) BpmSecondaryButton(onClick = { navigate(WorkspaceDestination.Learn) }) { Text("Mi aprendizaje") }
                            HorizontalDivider()
                            BpmTextButton(onClick = onLogout, modifier = Modifier.heightIn(min = 48.dp)) {
                                BpmIcon(BpmSymbol.Logout)
                                Spacer(Modifier.width(8.dp))
                                Text("Cerrar sesión")
                            }
                        }
                    }
                    if (section != WorkspaceDestination.Account && workspace == null && !state.loading) {
                        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                            val placeholder = workspacePlaceholder(state, superAdmin)
                            Text(placeholder.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(placeholder.message)
                            if (placeholder.canCreate) RaisedButton("Crear organización", { open(WorkspaceFormKind.Organization) })
                            if (placeholder.canRetry) BpmSecondaryButton(onClick = onRefresh) { Text("Volver a intentar") }

                        }
                    }
                    if (secondaryContent != null) secondaryContent()
                    else workspace?.let { current -> when (section) {
                        WorkspaceDestination.Account -> Unit
                        WorkspaceDestination.Home -> Home(current, learningAllowed = WorkspaceDestination.Learn in available, onLearn = { navigate(WorkspaceDestination.Learn) }, onCompany = { navigate(WorkspaceDestination.Company) })
                        WorkspaceDestination.Company -> {
                            BpmCard(Modifier.fillMaxWidth()) {
                                Eyebrow("ORGANIZACIÓN")
                                Text(current.organization.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(current.organization.legalName)
                                if (current.organization.contactEmail.isNotBlank()) Text(current.organization.contactEmail)
                                if (current.organization.contactPhone.isNotBlank()) Text(current.organization.contactPhone)
                            }
                            if (current.canManage) BpmCard(Modifier.fillMaxWidth()) {
                                Text("Plantillas de control", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("Prepara preguntas y publica los criterios que utilizará tu equipo.")
                                BpmSecondaryButton(onClick = onManageTemplates) { Text("Gestionar plantillas") }
                            }
                            SectionHeading("Sedes", if (current.canManage) "Agregar sede" else null) { open(WorkspaceFormKind.Location) }
                            WorkspacePagedList(WorkspaceResource.Locations, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                            if (current.locations.isEmpty()) EmptyState("Sin sedes para mostrar", "No hay sedes disponibles para esta búsqueda y tus permisos actuales.")
                            current.locations.forEach { location -> BpmCard(Modifier.fillMaxWidth()) {
                                Text(location.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(location.address)
                                if (location.district.isNotBlank()) Text(location.district, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (location.contactPhone.isNotBlank()) Text(location.contactPhone)
                            } }
                            }
                        }
                        WorkspaceDestination.Team -> {
                            SectionHeading("Personas que hacen la diferencia", if (current.canManage) "Agregar persona" else null) { open(WorkspaceFormKind.Member) }
                            if (!current.canManage) EmptyState("Administración del equipo restringida", "Tu rol permite consultar esta sección, pero solo el administrador de la organización puede gestionar sus miembros. Solicítale los cambios que necesites.")
                            else {
                                WorkspacePagedList(WorkspaceResource.Members, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                                if (current.members.isEmpty()) EmptyState("Sin personas para mostrar", "No hay personas disponibles para esta búsqueda. Puedes agregar cuentas BPM verificadas al equipo.")
                                current.members.forEach { member -> BpmCard(Modifier.fillMaxWidth()) {
                                    Text(member.name, fontWeight = FontWeight.Bold)
                                    Text(member.email)
                                    Eyebrow(roleLabel(member.role))
                                    Text(if (member.role == "ADMIN") "Todas las sedes de esta organización" else "${member.locationIds.size} sede(s) asignada(s)", style = MaterialTheme.typography.bodySmall)
                                } }
                                }
                            }
                        }
                        WorkspaceDestination.Learn -> {
                            BpmIntroCard("Un hábito a la vez.", "Consulta las capacitaciones publicadas por tu organización.", learning = true)
                            WorkspacePagedList(WorkspaceResource.Courses, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                            Entries(current.courses, "Sin capacitaciones para mostrar", "No hay capacitaciones disponibles para esta búsqueda y tus permisos actuales.")
                            }
                        }
                        WorkspaceDestination.Operations, WorkspaceDestination.Supervision -> {
                            checklistContent()
                            if (secondaryTitle == null && (current.incidents.isNotEmpty() || (current.totals["incidents"] ?: 0) > 0)) {
                            Eyebrow("INCIDENCIAS DE TUS SEDES")
                            WorkspacePagedList(WorkspaceResource.Incidents, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                            Entries(current.incidents, "No encontramos incidencias", "Prueba con otra búsqueda.")
                            }
                            }
                        }
                    } }
                }
                if (!wide && form == null && !secondaryEditor && !choosingOrganization) NavigationBar {
                    destinations.forEach { item -> BpmFeedback(MaterialTheme.colorScheme.primary) {
                        NavigationBarItem(selected = section == item,
                        onClick = { navigate(item) }, icon = { WorkspaceIcon(item) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        label = { Text(item.label, style = MaterialTheme.typography.labelMedium) }) }
                    }
                }
            }
        }
    }
}
