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
    onAccounts: () -> Unit = {}, onInvite: () -> Unit = onAccounts, onEditOrganization: () -> Unit = onAccounts, onEditSite: (String) -> Unit = { onEditOrganization() }, onDevices: () -> Unit = {}, onAcceptInvitation: () -> Unit = {},
    homeContent: (@Composable () -> Unit)? = null,
    onOpenCourse: ((String) -> Unit)? = null,
    learnHeader: (@Composable () -> Unit)? = null,
    /**
     * Aprender del personal (camino): reemplaza por completo la cabecera, el filtro y el título del catálogo. Recibe el
     * catálogo paginado de cursos como contenido para mostrarlo al final («Más cursos»).
     */
    workerLearn: (@Composable (catalog: @Composable () -> Unit) -> Unit)? = null,
    hiddenCourseIds: Set<String> = emptySet(),
    /** Perfil del personal (WORKER) y del jefe de cocina (CHEF): reemplaza por completo la pantalla «Mi cuenta» (avatar, racha, insignias, ajustes y cuenta). */
    workerProfile: (@Composable () -> Unit)? = null,
    /** Equipo del jefe de cocina (CHEF): lista–detalle de solo lectura con las personas de sus sedes; reemplaza la lista de gestión. */
    chefTeam: (@Composable () -> Unit)? = null,
    /**
     * Inicio del administrador de la organización (ADMIN): su presencia activa el estilo de gestión del administrador en
     * Inicio, Equipo, Academia y Empresa (Controles lo arma [checklistContent]); super administración conserva el anterior.
     */
    adminHome: (@Composable () -> Unit)? = null,
    /**
     * Super administración: [platformHome] (Organizaciones: indicadores y lista de todas las organizaciones, con «Nueva
     * organización» en la cabecera) y [platformIndicators] (Indicadores de la organización abierta). Entrar en una
     * organización lleva a su espacio (Empresa), con «Todas las organizaciones» para volver.
     */
    platformHome: (@Composable () -> Unit)? = null,
    platformIndicators: (@Composable () -> Unit)? = null,
 onAlerts:(()->Unit)?=null, unreadAlertCount:Int?=null, onAlertRules:()->Unit={}, onWebhooks:()->Unit={}, onNormative:(()->Unit)?=null, onSettings:(()->Unit)?=null, onHelp:(()->Unit)?=null,
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
    /**
     * Con [focusMode]: la pantalla de juego del personal dibuja su propia barra (X y avance) y su pie, así que el shell
     * no pone cabecera ni márgenes y le entrega todo el espacio.
     */
    playFocus: Boolean = false,
    screenNotice: @Composable () -> Unit = {},
    noticeVisible: Boolean = false,
    onUserActivity: () -> Unit = {},
    onOrganizationSearch: (String) -> Unit, onOrganizationNext: () -> Unit, onOrganizationPrevious: () -> Unit, onOrganizationRetry: () -> Unit,
    onPageSearch: (WorkspaceResource, String) -> Unit, onPageNext: (WorkspaceResource) -> Unit,
    onPagePrevious: (WorkspaceResource) -> Unit, onPageRetry: (WorkspaceResource) -> Unit,
    shortcuts: List<WorkspaceShortcut> = emptyList(), activeShortcut: WorkspaceShortcut? = null, onShortcut: (WorkspaceShortcut) -> Unit = {},
) {
    val role = state.workspace?.role
    // Las etiquetas por rol no aplican a la super administración, aunque su espacio muestre un rol de organización.
    val labelRole = if (viewer.canCreateOrganization) null else role
    val adminShell = adminHome != null && role == "ADMIN" && !viewer.canCreateOrganization
    val available = workspaceDestinations(role, viewer.canCreateOrganization)
    val navItems = workspaceNavigation(workspacePrimaryDestinations(available, role), shortcuts)
    // Todos los roles comparten la navegación del sistema: barra inferior hasta 839 dp y menú lateral de 230 dp desde 840 dp.
    // El personal (WORKER) y el jefe de cocina (CHEF) tienen «Perfil» como destino propio: no necesitan el avatar de la cabecera.
    val profileInNav = profileInNavigation(role, available) || viewer.canCreateOrganization
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
        // La organización abierta (Empresa, Equipo y revisiones) cuelga de Organizaciones en la barra de la super administración.
        val navSection = when {
            adminShell && section == WorkspaceDestination.Supervision -> WorkspaceDestination.Operations
            superAdmin && section in setOf(WorkspaceDestination.Company, WorkspaceDestination.Team, WorkspaceDestination.Supervision) -> WorkspaceDestination.Home
            else -> section
        }
        val playItems = navItems.map { item -> BpmPlayNavItem(item.label(labelRole, superAdmin), item.playSymbol(labelRole, superAdmin), item.isSelected(navSection, activeShortcut)) { item.open(navigate, onShortcut) } }
        val wide = navMode != BpmNavigationMode.BottomBar
        ProvideBpmWindowClass(windowClass, maxHeight) { CompositionLocalProvider(LocalBpmActionBarSlot provides actionSlot) {
        Row(Modifier.fillMaxSize()) {
            if (navMode == BpmNavigationMode.SideMenu && !focusMode) BpmPlaySideMenu(BpmBrand.name, playItems)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!(focusMode && playFocus)) WorkspaceTopBar(workspace?.organization?.name, wide, windowClass,
                    onAccount = if (section == WorkspaceDestination.Account || WorkspaceDestination.Account !in available || profileInNav) null else ({ navigate(WorkspaceDestination.Account) }),
                    canChangeOrganization = form == null && secondaryContent == null && !choosingOrganization && secondaryTitle == null && (state.canChooseOrganization || superAdmin) && !(superAdmin && section == WorkspaceDestination.Home),
                    enabled = !state.saving && !secondaryBusy,
                    onToggleOrganization = if (superAdmin) ({ navigate(WorkspaceDestination.Home) }) else onChooseOrganization,
                    onAlerts=onAlerts,unreadCount=unreadAlertCount, onSettings = onSettings, onHelp = onHelp,
                    focusTitle = if (focusMode) focusTitle ?: "" else null,
 onBack = if (form == null && secondaryContent == null && !choosingOrganization && secondaryTitle == null || activeShortcut != null && form == null) null else onCloseForm)
                if (state.loading && workspace == null) BpmSkeletonScreen(Modifier.padding(horizontal = BpmSpacing.gutter(windowClass), vertical = 20.dp), alignStart = adminShell)
                if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (section == WorkspaceDestination.Tasks && workspace != null && secondaryContent == null) HostedContent(Modifier.weight(1f).fillMaxWidth(), BpmPattern.Panel, bare = focusMode && playFocus) { taskContent() }
                // Una sola rama para la pantalla alojada con o sin modo enfoque del personal: si fueran ramas distintas, entrar
                // en la lección movería el contenido de lugar, se descartaría su estado y el curso volvería a la guía.
                else if (secondaryEditor && secondaryContent != null) HostedContent(Modifier.weight(1f).fillMaxWidth(),
                    if (secondaryWideOnExpanded && secondaryPattern == BpmPattern.StepFlow && (windowClass == BpmWindowClass.Expanded || windowClass == BpmWindowClass.Large)) BpmPattern.Panel else secondaryPattern,
                    preview = secondaryPattern != BpmPattern.Form, bare = focusMode && playFocus) { secondaryContent() }
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
                    // Mientras el espacio carga por primera vez solo se ve el esqueleto (sin el título «Hola, …» debajo, que aún no tiene datos).
                    if (!(state.loading && workspace == null) && !(workerLearn != null && section == WorkspaceDestination.Learn && secondaryContent == null) && !(profileInNav && section == WorkspaceDestination.Home && homeContent != null && secondaryContent == null) && !(workerProfile != null && section == WorkspaceDestination.Account && secondaryContent == null)) BpmPageHeader(secondaryTitle ?: if (superAdmin && section == WorkspaceDestination.Company && workspace != null) workspace.organization.name else if (section == WorkspaceDestination.Home && !superAdmin) "Hola, ${viewer.displayName.substringBefore(' ')}" else destinationLabel(section, labelRole, superAdmin),
                        description = if (superAdmin && secondaryContent == null) when (section) {
                            WorkspaceDestination.Home -> "Todas las organizaciones de BPM. Abre una para ver su espacio."
                            WorkspaceDestination.Indicators -> workspace?.let { "Cumplimiento, controles y capacitación de ${it.organization.name}." }
                            else -> null
                        } else null,
                        actions = if (superAdmin && secondaryContent == null && section == WorkspaceDestination.Home) ({
                            BpmChunkyButton("Nueva organización", { open(WorkspaceFormKind.Organization) })
                        }) else if (adminShell && secondaryContent == null && workspace?.canManage == true) when (section) {
                            WorkspaceDestination.Team -> ({
                                BpmChunkyButton("Invitar persona", onInvite)
                                BpmChunkyButton("Agregar persona", { open(WorkspaceFormKind.Member) }, tone = BpmChunkyTone.White)
                            })
                            WorkspaceDestination.Learn -> ({ BpmChunkyButton("Gestionar cursos", onTraining) })
                            WorkspaceDestination.Company -> ({ BpmChunkyButton("Agregar sede", { open(WorkspaceFormKind.Location) }) })
                            else -> null
                        } else null)
                    screenNotice()
                    if (workspace != null || section == WorkspaceDestination.Account) Errors(state.errors)
                    state.notice?.let { notice -> key(notice) { BpmReveal { Text(notice, color = MaterialTheme.colorScheme.primary) } } }
                    if (section == WorkspaceDestination.Account && workerProfile != null) workerProfile()
                    else if (section == WorkspaceDestination.Account) {
                        BpmPanelLayout(support = {
                            BpmCard(Modifier.fillMaxWidth()) {
                                Text("Acciones", style = MaterialTheme.typography.titleMedium)
                                if (WorkspaceDestination.Learn in available) BpmChunkyButton("Mi aprendizaje", { navigate(WorkspaceDestination.Learn) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                                BpmChunkyButton("Mis dispositivos", onDevices, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
                                BpmChunkyButton("Aceptar una invitación", onAcceptInvitation, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
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
                                BpmChunkyButton(if (sessionBusy) "Consultando…" else "Consultar mi acceso", onVerifySession, tone = BpmChunkyTone.White, enabled = !sessionBusy)
                            }
                            if (sessionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            sessionConfirmation?.let { Text(it, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                            sessionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                        }
                    }
                    if (section != WorkspaceDestination.Account && workspace == null && !state.loading && !(superAdmin && (section == WorkspaceDestination.Home || section == WorkspaceDestination.Indicators))) {
                        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                            val placeholder = workspacePlaceholder(state, superAdmin)
                            Text(placeholder.title, style = MaterialTheme.typography.headlineSmall)
                            Text(placeholder.message)
                            if (placeholder.canCreate) BpmChunkyButton("Crear organización", { open(WorkspaceFormKind.Organization) }, Modifier.fillMaxWidth())
                            if (placeholder.canRetry) BpmChunkyButton("Volver a intentar", onRefresh, tone = BpmChunkyTone.White)

                        }
                    }
                    if (secondaryContent != null) secondaryContent()
                    else if (superAdmin && section == WorkspaceDestination.Home) platformHome?.invoke()
                    else if (superAdmin && section == WorkspaceDestination.Indicators) platformIndicators?.invoke()
                    else workspace?.let { current -> when (section) {
                        WorkspaceDestination.Indicators -> Unit
                        WorkspaceDestination.Tasks -> Unit
                        WorkspaceDestination.Account -> Unit
                        // El personal (WORKER) ve solo su pantalla «Hoy»; el Home genérico de sedes/cursos/controles no le aplica.
                        WorkspaceDestination.Home -> {
                            if (adminShell && adminHome != null) adminHome()
                            else if ((current.role == "WORKER" || current.role == "CHEF") && homeContent != null) homeContent() else { homeContent?.invoke(); Home(current, learningAllowed = WorkspaceDestination.Learn in available, onLearn = { navigate(WorkspaceDestination.Learn) }, onCompany = { navigate(WorkspaceDestination.Company) }) }
                            // Buscador de normativa (RF-46/47): acceso para todos los roles.
                            if (onNormative != null && !adminShell && !((current.role == "WORKER" || current.role == "CHEF") && homeContent != null)) BpmActionTile(BpmSymbol.Learn, "Buscar en la normativa", "Encuentra lo que dice la norma sanitaria sobre temperaturas, higiene y más.", onNormative)
                        }
                        WorkspaceDestination.Company -> if (adminShell) AdminCompanyContent(current,
                            WorkspacePaging(state, onPageSearch, onPageNext, onPagePrevious, onPageRetry),
                            AdminCompanyActions(onManageTemplates, onDailyOperations, onAlertRules, onWebhooks, onEditOrganization, onEditSite)) else {
                            if (superAdmin) BpmBackLink(label = "Todas las organizaciones") { navigate(WorkspaceDestination.Home) }
                            BpmSectionCard("Datos de la organización", BpmSymbol.Company, tone = BpmPlayTone.Streak) {
                                if (!superAdmin) Text(current.organization.name, style = MaterialTheme.typography.titleLarge)
                                // La razón social solo se muestra si difiere del nombre comercial.
                                if (current.organization.legalName.isNotBlank() && current.organization.legalName != current.organization.name) BpmInfoRow(BpmSymbol.Control, "Razón social", current.organization.legalName)
                                if (current.organization.taxId.isNotBlank()) BpmInfoRow(BpmSymbol.Award, "RUC", current.organization.taxId, tone = BpmPlayTone.Gold)
                                if (current.organization.contactEmail.isNotBlank()) BpmInfoRow(BpmSymbol.Bell, "Correo de contacto", current.organization.contactEmail)
                                if (current.organization.contactPhone.isNotBlank()) BpmInfoRow(BpmSymbol.Play, "Teléfono", current.organization.contactPhone, tone = BpmPlayTone.Correct)
                            }
                            if (current.canManage) BpmGridLayout(listOfNotNull<@Composable (Modifier) -> Unit>(
                                { m -> BpmActionTile(BpmSymbol.Control, "Plantillas de control", "Prepara preguntas y publica los criterios de tu equipo.", onManageTemplates, m) },
                                { m -> BpmActionTile(BpmSymbol.Sprout, "Operación diaria", "Proveedores, productos, planes de limpieza y registros de tus sedes.", onDailyOperations, m) },
                                { m -> BpmActionTile(BpmSymbol.Bell, "Reglas de avisos", "Elige cuándo avisar a las personas responsables.", onAlertRules, m) },
                                { m -> BpmActionTile(BpmSymbol.Alert, "Incidencias y correcciones", "Atiende lo reportado y valida el cierre.", onIncidents, m) },
                                { m -> BpmActionTile(BpmSymbol.Team, "Equipo", "Personas, roles, sedes, invitaciones y datos de la organización.", onAccounts, m) },
                                { m -> BpmActionTile(BpmSymbol.Learn, "Academia BPM", "Cursos con lecciones, video y evaluaciones.", onTraining, m) },
                                { m -> BpmActionTile(BpmSymbol.Bell, "Automatizaciones", "Opción avanzada: conecta los avisos con otras herramientas de tu empresa.", onWebhooks, m) },
                                // La super administración también llega a las revisiones de la organización abierta (un solo acceso «Equipo»).
                                if (superAdmin) { m -> BpmActionTile(BpmSymbol.Eye, "Revisiones del equipo", "Qué revisó cada persona y cada sede.", { navigate(WorkspaceDestination.Supervision) }, m) } else null,
                            )) { card, m -> card(m) }
                            SectionHeading("Sedes", if (current.canManage) "Agregar sede" else null) { open(WorkspaceFormKind.Location) }
                            WorkspacePagedList(WorkspaceResource.Locations, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry) {
                            if (current.locations.isEmpty()) EmptyState("Sin sedes para mostrar", "No hay sedes disponibles para esta búsqueda y tus permisos actuales.")
                            if (current.locations.isNotEmpty()) BpmPlayCard(Modifier.fillMaxWidth()) {
                                current.locations.forEachIndexed { index, location ->
                                    BpmDataRow(location.name, subtitle = listOf(location.address, location.district, location.contactPhone).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null },
                                        divider = index < current.locations.lastIndex)
                                }
                            }
                            }
                        }
                        WorkspaceDestination.Team -> if (chefTeam != null) chefTeam() else if (adminShell) AdminTeamContent(current,
                            WorkspacePaging(state, onPageSearch, onPageNext, onPagePrevious, onPageRetry), onManage = onAccounts, onAssignCourse = onTraining) else {
                            // Una sola fila de acciones: la principal (agregar) y la de gestión; sin segundo título.
                            if (current.canManage) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid),
                                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                BpmChunkyButton("Agregar persona", { open(WorkspaceFormKind.Member) }, Modifier.bpmPrimaryButtonWidth())
                                BpmChunkyButton("Gestionar roles, invitaciones y bajas", onAccounts, tone = BpmChunkyTone.White)
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
                        WorkspaceDestination.Learn -> if (workerLearn != null) workerLearn {
                            WorkspacePagedList(WorkspaceResource.Courses, state, onPageSearch, onPageNext, onPagePrevious, onPageRetry, fullWidthSearch = true) {
                                Entries(current.courses, "Sin capacitaciones para mostrar", "No hay capacitaciones disponibles para esta búsqueda y tus permisos actuales.", onOpenCourse, hiddenCourseIds, CatalogFilter.All)
                            }
                        } else if (adminShell) AdminAcademyContent(current, WorkspacePaging(state, onPageSearch, onPageNext, onPagePrevious, onPageRetry),
                            onOpenCourse = { id -> onOpenCourse?.invoke(id) }, onManage = onTraining, learnerHeader = learnHeader)
                        else {
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
                            // El jefe de cocina ya tiene las incidencias dentro de Controles (su propia tarjeta); no se repite el banner.
                            if (secondaryTitle == null && role != "CHEF" && !adminShell) IncidentBanner(onIncidents)
                        }
                    } }
                } } }
                // Los flujos por pasos publican aquí su barra de acción fija (solo clase compacta) y ocultan la barra de navegación.
                val stepFlow = secondaryContent != null && secondaryPattern == BpmPattern.StepFlow
                BpmActionBarHost(actionSlot)
                // Los atajos (incidencias, investigación, empresas, acceso) son destinos de la barra: al abrirlos, la barra sigue visible y los marca.
                val keepNavigation = activeShortcut != null
                if (navMode == BpmNavigationMode.BottomBar && !focusMode && form == null && !stepFlow && (keepNavigation || (!secondaryEditor && !choosingOrganization))) BpmPlayBottomBar(playItems)
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
@Composable private fun HostedContent(modifier: Modifier, pattern: BpmPattern, preview: Boolean = true, bare: Boolean = false, content: @Composable () -> Unit) {
    val windowClass = currentWindowClass
    val max = BpmPatternRules.contentMax(pattern, windowClass, preview)
    // [bare] (modo enfoque del personal): sin margen ni tope de ancho, pero con el mismo árbol de composición, así el
    // contenido conserva su estado al entrar y salir del modo enfoque.
    Box(if (bare) modifier else modifier.padding(BpmSpacing.gutter(windowClass)), contentAlignment = Alignment.TopCenter) {
        Box((if (bare) Modifier else Modifier.widthIn(max = max)).fillMaxSize()) { content() }
    }
}

/** Sedes de una persona con su nombre cuando la página cargada las incluye; si no, solo la cantidad. */
internal fun memberLocations(member: Member, locations: List<Location>): String {
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
                BpmChunkyButton("Abrir incidencias", onOpen, tone = BpmChunkyTone.White)
            } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { title() }
                BpmChunkyButton("Abrir incidencias", onOpen, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
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
        BpmChunkyButton(action, onClick, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
    }
}


/** Etiqueta del elemento de navegación. */
private fun WorkspaceNavItem.label(role: String?, platformAdmin: Boolean = false): String = when (this) {
    is WorkspaceNavItem.Section -> destinationLabel(destination, role, platformAdmin)
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

/** Iconos de la navegación (para todos los roles): Aprender usa el camino en Personal y el libro en el resto. */
private fun WorkspaceNavItem.playSymbol(role: String?, platformAdmin: Boolean = false): BpmSymbol = when (this) {
    is WorkspaceNavItem.Section -> when (destination) {
        WorkspaceDestination.Home -> if (platformAdmin) BpmSymbol.Company else BpmSymbol.Home
        WorkspaceDestination.Indicators -> BpmSymbol.Target
        WorkspaceDestination.Learn -> if (role == "WORKER") BpmSymbol.Route else BpmSymbol.Learn
        WorkspaceDestination.Account -> BpmSymbol.Account
        WorkspaceDestination.Team -> BpmSymbol.Team
        WorkspaceDestination.Company -> BpmSymbol.Company
        WorkspaceDestination.Supervision -> BpmSymbol.Eye
        WorkspaceDestination.Tasks, WorkspaceDestination.Operations -> BpmSymbol.Control
    }
    is WorkspaceNavItem.Shortcut -> when (shortcut) {
        WorkspaceShortcut.Incidents -> BpmSymbol.Alert
        WorkspaceShortcut.Research -> BpmSymbol.Sprout
        WorkspaceShortcut.Access -> BpmSymbol.Lock
    }
}
