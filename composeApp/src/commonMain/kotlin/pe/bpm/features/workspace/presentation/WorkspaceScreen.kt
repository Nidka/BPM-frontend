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
    onDailyOperations: () -> Unit = {},
    onIncidents: () -> Unit = {},
    onTraining: () -> Unit = {},
    onAccounts: () -> Unit = {}, onDevices: () -> Unit = {}, onAcceptInvitation: () -> Unit = {},
    homeContent: (@Composable () -> Unit)? = null,
    onOpenCourse: ((String) -> Unit)? = null,
    learnHeader: (@Composable () -> Unit)? = null,
    hiddenCourseIds: Set<String> = emptySet(),
 onAlerts:(()->Unit)?=null, unreadAlertCount:Int?=null, onAlertRules:()->Unit={}, onWebhooks:()->Unit={}, onNormative:(()->Unit)?=null,
    taskContent: @Composable () -> Unit = {},
    secondaryContent: (@Composable () -> Unit)? = null,
    secondaryEditor: Boolean = false,
    secondaryPattern: BpmPattern = BpmPattern.Panel,
    /** Con [BpmPattern.StepFlow]: desde la clase expandida usa el ancho del panel (lección y evaluación con panel lateral). */
    secondaryWideOnExpanded: Boolean = false,
    secondaryTitle: String? = null,
    secondaryBusy: Boolean = false,
    secondaryScrollKey: String? = null,
    /**
     * Modo enfoque (regla G4): oculta la barra inferior, el riel y el menú lateral; la cabecera muestra cerrar (X) y
     * [focusTitle] (el nombre del curso) en lugar de la organización. La X llama a [onCloseForm].
     */
    focusMode: Boolean = false,
    focusTitle: String? = null,
    screenNotice: @Composable () -> Unit = {},
    noticeVisible: Boolean = false,
    onUserActivity: () -> Unit = {},
    onOrganizationSearch: (String) -> Unit, onOrganizationNext: () -> Unit, onOrganizationPrevious: () -> Unit, onOrganizationRetry: () -> Unit,
    onPageSearch: (WorkspaceResource, String) -> Unit, onPageNext: (WorkspaceResource) -> Unit,
    onPagePrevious: (WorkspaceResource) -> Unit, onPageRetry: (WorkspaceResource) -> Unit,
    shortcuts: List<WorkspaceShortcut> = emptyList(), activeShortcut: WorkspaceShortcut? = null, onShortcut: (WorkspaceShortcut) -> Unit = {},
) {
    val role = state.workspace?.role
    val available = workspaceDestinations(role, viewer.canCreateOrganization)
    val navItems = workspaceNavigation(workspacePrimaryDestinations(available), shortcuts)
    val navigate: (WorkspaceDestination) -> Unit = { if (it in available) onNavigate(it) }
    val contentScroll = rememberScrollState()
    val actionSlot = remember { BpmActionBarSlot() }
    val workspace = state.workspace
    val superAdmin = viewer.canCreateOrganization
    val open: (WorkspaceFormKind) -> Unit = { onClear(); onOpenForm(it) }
    LaunchedEffect(section, state.pages.mapValues { it.value.request }) { contentScroll.scrollTo(0) }
    LaunchedEffect(noticeVisible) { if (noticeVisible) contentScroll.scrollTo(0) }
    LaunchedEffect(secondaryScrollKey) { if (secondaryScrollKey != null) contentScroll.scrollTo(0) }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
        // BpmAppShell: la clase de ancho se calcula aquí una sola vez y baja por LocalBpmWindowClass.
        // Con altura baja (móvil horizontal) la navegación lateral no cabe: se usa la barra inferior.
        val windowClass = BpmWindowClass.fromWidth(maxWidth)
        val navMode = BpmNavigationMode.of(windowClass, maxHeight)
        val wide = navMode != BpmNavigationMode.BottomBar
        ProvideBpmWindowClass(windowClass, maxHeight) { CompositionLocalProvider(LocalBpmActionBarSlot provides actionSlot) {
        Row(Modifier.fillMaxSize()) {
            if (navMode == BpmNavigationMode.Rail && !focusMode) NavigationRail(Modifier.width(navMode.width).fillMaxHeight(),
                containerColor = MaterialTheme.colorScheme.surface, header = { Spacer(Modifier.height(BpmSpacing.sm)); Brand(compact = true) }) {
                Spacer(Modifier.height(BpmSpacing.lg))
                navItems.forEach { item ->
                    BpmFeedback(MaterialTheme.colorScheme.primary) {
                        NavigationRailItem(selected = item.isSelected(section, activeShortcut), onClick = { item.open(navigate, onShortcut) }, icon = { item.Icon() },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                            label = { Text(item.label(role), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) })
                    }
                }
            }
            if (navMode == BpmNavigationMode.SideMenu && !focusMode) Surface(Modifier.width(navMode.width).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Brand()
                    Spacer(Modifier.height(32.dp))
                    navItems.forEach { item ->
                        BpmFeedback(MaterialTheme.colorScheme.primary) {
                        NavigationDrawerItem(label = { Text(item.label(role), style = BpmType.bodyStrong) },
                            selected = item.isSelected(section, activeShortcut), onClick = { item.open(navigate, onShortcut) },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary),
                            icon = { item.Icon() })
                        }
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                WorkspaceTopBar(workspace?.organization?.name, wide, windowClass,
                    onAccount = if (section == WorkspaceDestination.Account || WorkspaceDestination.Account !in available) null else ({ navigate(WorkspaceDestination.Account) }),
                    canChangeOrganization = form == null && secondaryContent == null && !choosingOrganization && secondaryTitle == null && (state.canChooseOrganization || superAdmin),
                    enabled = !state.saving && !secondaryBusy,
                    onToggleOrganization = onChooseOrganization,
                    onAlerts=onAlerts,unreadCount=unreadAlertCount,
                    focusTitle = if (focusMode) focusTitle ?: "" else null,
 onBack = if (form == null && secondaryContent == null && !choosingOrganization && secondaryTitle == null) null else onCloseForm)
                if (state.loading || state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (section == WorkspaceDestination.Tasks && workspace != null && secondaryContent == null) HostedContent(Modifier.weight(1f).fillMaxWidth(), BpmPattern.Panel) { taskContent() }
                else if (secondaryEditor && secondaryContent != null) HostedContent(Modifier.weight(1f).fillMaxWidth(),
                    if (secondaryWideOnExpanded && secondaryPattern == BpmPattern.StepFlow && (windowClass == BpmWindowClass.Expanded || windowClass == BpmWindowClass.Large)) BpmPattern.Panel else secondaryPattern,
                    preview = secondaryPattern != BpmPattern.Form) { secondaryContent() }
                else if (form != null) HostedContent(Modifier.weight(1f).fillMaxWidth(), BpmPattern.Form, preview = false) {
                    pe.bpm.core.offline.ServerOnly {
                    WorkspaceForm(form, state, onClose = { if (!state.saving) { onCloseForm(); onClear() } },
                        onSubmitForm, formDraft, onFormRetryAccount, onFormSelectAccount, onFormEdit, onFormDiscard, onFormRetry, onUserActivity, onPageSearch, onPageNext, onPagePrevious, onPageRetry)
                    }
                } else if (choosingOrganization) HostedContent(Modifier.weight(1f).fillMaxWidth(), BpmPattern.Access) {
                    OrganizationPickerScreen(state.organizations, state.selected, state.loading, errors = state.errors,
                        canCreate = superAdmin, onSelect = onSelect, onCreate = { open(WorkspaceFormKind.Organization) },
                        onUserActivity = onUserActivity, notice = screenNotice, noticeVisible = noticeVisible,
                        page = state.organizationPage, onSearch = onOrganizationSearch, onNext = onOrganizationNext,
                        onPrevious = onOrganizationPrevious, onRetry = onOrganizationRetry)
                } else key(section, secondaryTitle) { Box(Modifier.weight(1f).fillMaxWidth().bpmAppear().verticalScroll(contentScroll)) { BpmScreenFrame(if (secondaryContent != null) secondaryPattern else BpmPattern.Panel) {
                    // El título de la pantalla va primero; los avisos y accesos de apoyo se muestran debajo.
                    BpmPageHeader(secondaryTitle ?: if (section == WorkspaceDestination.Home) "Hola, ${viewer.displayName.substringBefore(' ')}" else destinationLabel(section, role))
                    screenNotice()
                    if (workspace != null || section == WorkspaceDestination.Account) Errors(state.errors)
                    state.notice?.let { notice -> key(notice) { BpmReveal { Text(notice, color = MaterialTheme.colorScheme.primary) } } }
                    if (section == WorkspaceDestination.Account) {
                        BpmPanelLayout(support = {
                            BpmCard(Modifier.fillMaxWidth()) {
                                Text("Acciones", style = MaterialTheme.typography.titleMedium)
                                if (WorkspaceDestination.Learn in available) BpmSecondaryButton(onClick = { navigate(WorkspaceDestination.Learn) }, modifier = Modifier.fillMaxWidth()) { Text("Mi aprendizaje") }
                                BpmSecondaryButton(onClick = onDevices, modifier = Modifier.fillMaxWidth()) { Text("Mis dispositivos") }
                                BpmSecondaryButton(onClick = onAcceptInvitation, modifier = Modifier.fillMaxWidth()) { Text("Aceptar una invitación") }
                                HorizontalDivider()
                                BpmTextButton(onClick = onLogout, modifier = Modifier.heightIn(min = BpmSpacing.touch)) {
                                    BpmIcon(BpmSymbol.Logout)
                                    Spacer(Modifier.width(BpmSpacing.sm))
                                    Text("Cerrar sesión")
                                }
                            }
                        }) {
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
                        }
                        }
                    }
                    if (section != WorkspaceDestination.Account && workspace == null && !state.loading) {
                        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                            val placeholder = workspacePlaceholder(state, superAdmin)
                            Text(placeholder.title, style = MaterialTheme.typography.headlineSmall)
                            Text(placeholder.message)
                            if (placeholder.canCreate) RaisedButton("Crear organización", { open(WorkspaceFormKind.Organization) })
                            if (placeholder.canRetry) BpmSecondaryButton(onClick = onRefresh) { Text("Volver a intentar") }

                        }
                    }
                    if (secondaryContent != null) secondaryContent()
                    else workspace?.let { current -> when (section) {
                        WorkspaceDestination.Tasks -> Unit
                        WorkspaceDestination.Account -> Unit
                        // El personal (WORKER) ve solo su pantalla «Hoy»; el Home genérico de sedes/cursos/controles no le aplica.
                        WorkspaceDestination.Home -> {
                            if (current.role == "WORKER" && homeContent != null) homeContent() else { homeContent?.invoke(); Home(current, learningAllowed = WorkspaceDestination.Learn in available, onLearn = { navigate(WorkspaceDestination.Learn) }, onCompany = { navigate(WorkspaceDestination.Company) }) }
                            // Buscador de normativa (RF-46/47): acceso para todos los roles.
                            if (onNormative != null) BpmActionTile(BpmSymbol.Learn, "Buscar en la normativa", "Encuentra lo que dice la norma sanitaria sobre temperaturas, higiene y más.", onNormative)
                        }
                        WorkspaceDestination.Company -> {
                            BpmCard(Modifier.fillMaxWidth()) {
                                Eyebrow("ORGANIZACIÓN")
                                Text(current.organization.name, style = MaterialTheme.typography.titleLarge)
                                // Los datos de la organización se agrupan (4 dp entre líneas) y se separan del nombre.
                                Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                                    // La razón social solo se muestra si difiere del nombre comercial.
                                    if (current.organization.legalName.isNotBlank() && current.organization.legalName != current.organization.name) Text(current.organization.legalName)
                                    if (current.organization.contactEmail.isNotBlank()) Text(current.organization.contactEmail)
                                    if (current.organization.contactPhone.isNotBlank()) Text(current.organization.contactPhone)
                                }
                            }
                            if (current.canManage) BpmGridLayout(listOf<@Composable (Modifier) -> Unit>(
                                { m -> BpmActionTile(BpmSymbol.Control, "Plantillas de control", "Prepara preguntas y publica los criterios de tu equipo.", onManageTemplates, m) },
                                { m -> BpmActionTile(BpmSymbol.Sprout, "Operación diaria", "Proveedores, productos, planes de limpieza y registros de tus sedes.", onDailyOperations, m) },
                                { m -> BpmActionTile(BpmSymbol.Bell, "Reglas de avisos", "Elige cuándo avisar a las personas responsables.", onAlertRules, m) },
                                { m -> BpmActionTile(BpmSymbol.Alert, "Incidencias y correcciones", "Atiende lo reportado y valida el cierre.", onIncidents, m) },
                                { m -> BpmActionTile(BpmSymbol.Team, "Cuentas y equipo", "Roles, sedes, invitaciones y datos de la organización.", onAccounts, m) },
                                { m -> BpmActionTile(BpmSymbol.Learn, "Academia BPM", "Cursos con lecciones, video y evaluaciones.", onTraining, m) },
                                { m -> BpmActionTile(BpmSymbol.Bell, "Automatizaciones", "Envía los avisos a n8n u otra herramienta con webhooks firmados.", onWebhooks, m) },
                            )) { card, m -> card(m) }
                            SectionHeading("Sedes", if (current.canManage) "Agregar sede" else null) { open(WorkspaceFormKind.Location) }
                            WorkspacePagedList(WorkspaceResource.Locations, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                            if (current.locations.isEmpty()) EmptyState("Sin sedes para mostrar", "No hay sedes disponibles para esta búsqueda y tus permisos actuales.")
                            BpmGridLayout(current.locations) { location, cardModifier -> BpmCard(cardModifier) {
                                Text(location.name, style = MaterialTheme.typography.titleMedium)
                                Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                                    Text(location.address)
                                    if (location.district.isNotBlank()) Text(location.district, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (location.contactPhone.isNotBlank()) Text(location.contactPhone)
                                }
                            } }
                            }
                        }
                        WorkspaceDestination.Team -> {
                            // Una sola fila de acciones: la principal (agregar) y la de gestión; sin segundo título.
                            if (current.canManage) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                RaisedButton("Agregar persona", { open(WorkspaceFormKind.Member) }, Modifier.bpmPrimaryButtonWidth())
                                BpmSecondaryButton(onClick = onAccounts, modifier = Modifier.bpmPrimaryButtonWidth()) { Text("Gestionar roles, invitaciones y bajas") }
                            }
                            if (!current.canManage) EmptyState("Administración del equipo restringida", "Tu rol permite consultar esta sección, pero solo el administrador de la organización puede gestionar sus miembros. Solicítale los cambios que necesites.")
                            else {
                                WorkspacePagedList(WorkspaceResource.Members, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                                if (current.members.isEmpty()) EmptyState("Sin personas para mostrar", "No hay personas disponibles para esta búsqueda. Puedes agregar cuentas BPM verificadas al equipo.")
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) { // lista-apilada: filas densas de personas
                                    current.members.forEach { member -> BpmListRow(member.name, detail = member.email, chip = roleLabel(member.role)) {
                                        Text(memberLocations(member, current.locations), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } }
                                }
                                }
                            }
                        }
                        WorkspaceDestination.Learn -> {
                            // Con la cabecera del alumno ya hay un saludo contextual: la introducción solo se usa sin ella.
                            if (learnHeader == null) BpmIntroCard("Un hábito a la vez.", "Consulta las capacitaciones publicadas por tu organización.", learning = true)
                            else learnHeader()
                            var catalogFilter by remember { mutableStateOf(CatalogFilter.All) }
                            if (learnHeader != null) Text("Catálogo", style = MaterialTheme.typography.titleLarge)
                            CatalogFilterRow(catalogFilter) { catalogFilter = it }
                            WorkspacePagedList(WorkspaceResource.Courses, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry, fullWidthSearch = true) {
                            Entries(current.courses, "Sin capacitaciones para mostrar", "No hay capacitaciones disponibles para esta búsqueda y tus permisos actuales.", onOpenCourse, hiddenCourseIds, catalogFilter)
                            }
                        }
                        WorkspaceDestination.Operations, WorkspaceDestination.Supervision -> {
                            checklistContent()
                            if (secondaryTitle == null) IncidentBanner(onIncidents)
                        }
                    } }
                } } }
                // Los flujos por pasos publican aquí su barra de acción fija (solo clase compacta) y ocultan la barra de navegación.
                val stepFlow = secondaryContent != null && secondaryPattern == BpmPattern.StepFlow
                BpmActionBarHost(actionSlot)
                // Los atajos (incidencias, investigación, empresas, acceso) son destinos de la barra: al abrirlos, la barra sigue visible y los marca.
                val keepNavigation = activeShortcut != null
                if (navMode == BpmNavigationMode.BottomBar && !focusMode && form == null && !stepFlow && (keepNavigation || (!secondaryEditor && !choosingOrganization))) NavigationBar {
                    navItems.forEach { item -> BpmFeedback(MaterialTheme.colorScheme.primary) {
                        NavigationBarItem(selected = item.isSelected(section, activeShortcut),
                        onClick = { item.open(navigate, onShortcut) }, icon = { item.Icon() },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        label = { Text(item.label(role), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }) }
                    }
                }
            }
        }
        }
        }
    }
}

/**
 * Contenedor común de las pantallas alojadas (formularios, selector, gestión): mismo margen que la
 * cabecera, ancho máximo del patrón ([BpmPatternRules.contentMax]) y centrado.
 */
@Composable private fun HostedContent(modifier: Modifier, pattern: BpmPattern, preview: Boolean = true, content: @Composable () -> Unit) {
    val windowClass = currentWindowClass
    val max = BpmPatternRules.contentMax(pattern, windowClass, preview)
    Box(modifier.padding(BpmSpacing.gutter(windowClass)), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max).fillMaxSize()) { content() }
    }
}

/** Sedes de una persona con su nombre cuando la página cargada las incluye; si no, solo la cantidad. */
private fun memberLocations(member: Member, locations: List<Location>): String {
    if (member.role == "ADMIN") return "Todas las sedes de esta organización"
    val names = member.locationIds.mapNotNull { id -> locations.firstOrNull { it.id == id }?.name }
    return when {
        member.locationIds.isEmpty() -> "Sin sedes asignadas"
        names.size == member.locationIds.size -> names.joinToString(", ")
        member.locationIds.size == 1 -> "1 sede asignada"
        else -> "${member.locationIds.size} sedes asignadas"
    }
}

@Composable private fun IncidentEntryCard(onOpen: () -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    ActionCard(modifier, "Incidencias y correcciones",
        "Consulta lo reportado, atiende las correcciones y revisa qué falta para validar el cierre.", "Abrir incidencias", onOpen)
}

/** Acceso a incidencias cuando va solo bajo otra lista: franja compacta, sin botón a todo el ancho. */
@Composable private fun IncidentBanner(onOpen: () -> Unit) {
    BpmCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val title: @Composable () -> Unit = {
                Text("Incidencias y correcciones", style = MaterialTheme.typography.titleMedium)
                Text("Consulta lo reportado, atiende las correcciones y revisa qué falta para validar el cierre.")
            }
            if (maxWidth >= 640.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { title() }
                BpmSecondaryButton(onClick = onOpen) { Text("Abrir incidencias") }
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { title() }
                BpmSecondaryButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) { Text("Abrir incidencias") }
            }
        }
    }
}

/** Tarjeta de acceso a un módulo: título, descripción y botón a todo el ancho, como en el mockup. */
@Composable private fun ActionCard(modifier: Modifier, title: String, description: String, action: String, onClick: () -> Unit) {
    BpmCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description)
        Spacer(Modifier.weight(1f))
        BpmSecondaryButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
    }
}


/** Etiqueta del elemento de navegación. */
private fun WorkspaceNavItem.label(role: String?): String = when (this) {
    is WorkspaceNavItem.Section -> destinationLabel(destination, role)
    is WorkspaceNavItem.Shortcut -> shortcut.label
}

/** Un atajo activo reemplaza la selección de su sección (Incidencias no marca también Control). */
private fun WorkspaceNavItem.isSelected(section: WorkspaceDestination, active: WorkspaceShortcut?): Boolean = when (this) {
    is WorkspaceNavItem.Section -> active == null && destination == section
    is WorkspaceNavItem.Shortcut -> shortcut == active
}

private fun WorkspaceNavItem.open(navigate: (WorkspaceDestination) -> Unit, onShortcut: (WorkspaceShortcut) -> Unit) = when (this) {
    is WorkspaceNavItem.Section -> navigate(destination)
    is WorkspaceNavItem.Shortcut -> onShortcut(shortcut)
}

@Composable private fun WorkspaceNavItem.Icon() = when (this) {
    is WorkspaceNavItem.Section -> WorkspaceIcon(destination)
    is WorkspaceNavItem.Shortcut -> BpmIcon(when (shortcut) {
        WorkspaceShortcut.Incidents -> BpmSymbol.Alert
        WorkspaceShortcut.Research -> BpmSymbol.Sprout
        WorkspaceShortcut.Companies -> BpmSymbol.Company
        WorkspaceShortcut.Access -> BpmSymbol.Team
    })
}
