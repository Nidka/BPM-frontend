package pe.bpm.features.workspace.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import pe.bpm.core.designsystem.*
import pe.bpm.features.workspace.domain.*

/*
 * Pantallas del administrador de la organización (Equipo, Academia y Empresa) con el estilo nuevo: lista a la izquierda,
 * detalle a la derecha (specs/LAYOUT.md §3 y §5), botones gruesos y solo datos que la API entrega. Los formularios y las
 * pantallas de gestión existentes se abren desde aquí; este archivo no toca la lógica de negocio.
 */

/** Callbacks de paginación del espacio de trabajo, agrupados para no repetir cuatro parámetros por pantalla. */
internal class WorkspacePaging(
    val state: WorkspaceState,
    val onSearch: (WorkspaceResource, String) -> Unit,
    val onNext: (WorkspaceResource) -> Unit,
    val onPrevious: (WorkspaceResource) -> Unit,
    val onRetry: (WorkspaceResource) -> Unit,
) {
    @Composable
    fun list(resource: WorkspaceResource, content: @Composable ColumnScope.() -> Unit) =
        WorkspacePagedList(resource, state, onSearch, onNext, onPrevious, onRetry, fullWidthSearch = true, content = content)
}

private const val ALL = "ALL"

/** Etiqueta corta del rol para las pestañas y las filas. */
private fun roleName(role: String) = when (role) { "ADMIN" -> "Administrador"; "CHEF" -> "Jefe de cocina"; else -> "Personal" }

/** Sedes en una línea de fila: «Todas las sedes» para el administrador. */
private fun siteText(member: Member, sites: List<Location>) = if (member.role == "ADMIN") "Todas las sedes" else memberLocations(member, sites)

/**
 * «Equipo» del administrador: personas de la organización en lista–detalle. La lista filtra por rol la página cargada
 * (Todos · Personal · Jefes · Admin); el detalle muestra rol, correo y sedes, y lleva a asignar un curso o a cambiar el rol
 * o las sedes en «Equipo». El avance por persona no lo entrega la API y no se muestra.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AdminTeamContent(
    current: Workspace,
    paging: WorkspacePaging,
    onManage: () -> Unit,
    onAssignCourse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!current.canManage) {
        EmptyState("Administración del equipo restringida", "Tu rol permite consultar esta sección, pero solo el administrador de la organización puede gestionar sus miembros. Solicítale los cambios que necesites.")
        return
    }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(ALL) }
    val members = current.members
    val shown = members.filter { filter == ALL || it.role == filter }
    val selected = members.firstOrNull { it.id == selectedId }
    val first = shown.firstOrNull()
    BpmListDetail(modifier, detail = selected?.let { member -> { AdminMemberDetail(member, current.locations, { selectedId = null }, onManage, onAssignCourse) } },
        defaultDetail = first?.let { member -> { AdminMemberDetail(member, current.locations, { selectedId = null }, onManage, onAssignCourse) } },
        emptyDetail = { BpmEmptyDetail("Elige a una persona", "Toca una persona de la lista para ver su rol, correo y sedes.") }) {
        val highlighted = bpmSelectedId(selectedId, first?.id)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
            // Los filtros pasan a una segunda línea cuando no caben (360 dp): ninguno queda cortado por el borde.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                listOf(ALL to "Todos", "WORKER" to "Personal", "CHEF" to "Jefes", "ADMIN" to "Admin").forEach { (value, label) ->
                    val count = if (value == ALL) members.size else members.count { it.role == value }
                    BpmFilterChip(selected = filter == value, onClick = { filter = value }, label = { Text("$label $count") })
                }
            }
            paging.list(WorkspaceResource.Members) {
                if (members.isEmpty()) EmptyState("Sin personas para mostrar", "No hay personas disponibles para esta búsqueda. Invita a alguien con «Invitar persona».")
                else if (shown.isEmpty()) EmptyState("Sin personas con este rol", "Prueba con otro filtro; solo se filtran las personas de esta página.")
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) { // lista-apilada: filas densas de personas
                    shown.forEach { member ->
                        BpmPersonRow(member.name, detail = "${roleName(member.role)} · ${siteText(member, current.locations)}",
                            selected = member.id == highlighted, onClick = { selectedId = member.id })
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminMemberDetail(member: Member, sites: List<Location>, onBack: () -> Unit, onManage: () -> Unit, onAssignCourse: () -> Unit) {
    BpmDetailPane(member.name, subtitle = "${roleName(member.role)} · ${siteText(member, sites)}", onBack = onBack, actions = {
        BpmChunkyButton("Asignar curso", onAssignCourse)
        BpmChunkyButton("Cambiar rol o sede", onManage, tone = BpmChunkyTone.White)
    }) {
        BpmSection("Datos") {
            BpmDetailFields(listOf(
                BpmDetailField("Rol", roleName(member.role)),
                BpmDetailField("Correo", member.email),
                BpmDetailField("Sedes", memberLocations(member, sites)),
            ))
        }
    }
}

/**
 * «Academia» del administrador: los cursos de la organización en lista–detalle y, en otra
 * pestaña, su propio aprendizaje ([learnerHeader], el mismo del alumno). La creación, edición y asignación de cursos
 * está en «Gestionar cursos» ([onManage]), que ya tiene la autoría por pasos.
 */
@Composable
internal fun AdminAcademyContent(
    current: Workspace,
    paging: WorkspacePaging,
    onOpenCourse: (String) -> Unit,
    onManage: () -> Unit,
    learnerHeader: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf("company") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
        BpmSegmentedTabs(listOf(BpmTabOption("company", "Cursos"), BpmTabOption("mine", "Mi aprendizaje")), tab, { tab = it })
        if (tab == "mine") {
            if (learnerHeader != null) learnerHeader()
            else BpmIntroCard("Un hábito a la vez.", "Consulta las capacitaciones publicadas por tu organización.", learning = true)
            return@Column
        }
        val courses = current.courses
        val selected = courses.firstOrNull { it.id == selectedId }
        val first = courses.firstOrNull()
        BpmListDetail(detail = selected?.let { course -> { AdminCourseDetail(course, { selectedId = null }, { onOpenCourse(course.id) }, onManage) } },
            defaultDetail = first?.let { course -> { AdminCourseDetail(course, { selectedId = null }, { onOpenCourse(course.id) }, onManage) } },
            emptyDetail = { BpmEmptyDetail("Elige un curso", "Toca un curso de la lista para ver su resumen y abrirlo.") }) {
            val highlighted = bpmSelectedId(selectedId, first?.id)
            paging.list(WorkspaceResource.Courses) {
                if (courses.isEmpty()) EmptyState("Sin cursos para mostrar", "No hay cursos disponibles para esta búsqueda. Crea el primero en «Gestionar cursos».")
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) { // lista-apilada: lista junto al detalle
                    courses.forEach { course ->
                        val chip = catalogStatusChip(course.status)
                        BpmRecordCard(course.title, status = BpmRecordStatus(chip?.first ?: entryStatusLabel(course.status), chip?.second ?: BpmTone.Neutral),
                            subtitle = catalogSummary(course.detail).ifEmpty { "Curso de tu organización" },
                            selected = course.id == highlighted, onOpen = { selectedId = course.id })
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminCourseDetail(course: Entry, onBack: () -> Unit, onOpen: () -> Unit, onManage: () -> Unit) {
    val chip = catalogStatusChip(course.status)
    BpmDetailPane(course.title, status = BpmRecordStatus(chip?.first ?: entryStatusLabel(course.status), chip?.second ?: BpmTone.Neutral),
        onBack = onBack, actions = {
            BpmChunkyButton("Abrir curso", onOpen)
            BpmChunkyButton("Gestionar cursos", onManage, tone = BpmChunkyTone.White)
        }) {
        BpmSection("Descripción") { Text(course.detail.ifBlank { "Este curso no tiene descripción." }, style = BpmPlayType.sub, color = BpmPlayColors.ink) }
    }
}

/** Accesos de gestión de la empresa (cada uno abre una pantalla existente). */
internal class AdminCompanyActions(
    val onTemplates: () -> Unit,
    val onDaily: () -> Unit,
    val onAlertRules: () -> Unit,
    val onWebhooks: () -> Unit,
    val onOrganization: () -> Unit,
)

/**
 * «Empresa» del administrador: datos de la organización, accesos de gestión (plantillas, operación diaria, reglas de avisos,
 * automatizaciones y datos de la organización) y las sedes en lista–detalle. Editar la organización o una sede lleva a
 * «Equipo → Empresa y sedes».
 */
@Composable
internal fun AdminCompanyContent(
    current: Workspace,
    paging: WorkspacePaging,
    actions: AdminCompanyActions,
    modifier: Modifier = Modifier,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val organization = current.organization
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        BpmSection("Organización") {
            BpmDetailFields(listOf(
                BpmDetailField("Nombre comercial", organization.name),
                BpmDetailField("Razón social", organization.legalName.takeIf { it != organization.name }),
                BpmDetailField("Correo de contacto", organization.contactEmail),
                BpmDetailField("Teléfono", organization.contactPhone),
            ))
            if (current.canManage) BpmChunkyButton("Editar datos", actions.onOrganization, tone = BpmChunkyTone.White)
        }
        if (current.canManage) {
            Text("Gestión", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            BpmGridLayout(listOf<@Composable (Modifier) -> Unit>(
                { m -> BpmActionTile(BpmSymbol.Control, "Plantillas de control", "Prepara preguntas y publica los criterios de tu equipo.", actions.onTemplates, m) },
                { m -> BpmActionTile(BpmSymbol.Sprout, "Operación diaria", "Proveedores, productos, planes de limpieza y registros de tus sedes.", actions.onDaily, m) },
                { m -> BpmActionTile(BpmSymbol.Bell, "Reglas de avisos", "Elige cuándo avisar a las personas responsables.", actions.onAlertRules, m) },
                { m -> BpmActionTile(BpmSymbol.Bell, "Automatizaciones", "Envía los avisos a n8n u otra herramienta con webhooks firmados.", actions.onWebhooks, m) },
            ), maxColumns = 2) { card, m -> card(m) } // 4 accesos: 2 × 2, sin una tarjeta huérfana en la tercera columna
        }
        Text("Sedes", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        val sites = current.locations
        val selected = sites.firstOrNull { it.id == selectedId }
        val first = sites.firstOrNull()
        BpmListDetail(detail = selected?.let { site -> { AdminSiteDetail(site, current.canManage, { selectedId = null }, actions.onOrganization) } },
            defaultDetail = first?.let { site -> { AdminSiteDetail(site, current.canManage, { selectedId = null }, actions.onOrganization) } },
            emptyDetail = { BpmEmptyDetail("Elige una sede", "Toca una sede de la lista para ver su dirección, teléfono y zona horaria.") }) {
            val highlighted = bpmSelectedId(selectedId, first?.id)
            paging.list(WorkspaceResource.Locations) {
                if (sites.isEmpty()) EmptyState("Sin sedes para mostrar", "No hay sedes disponibles para esta búsqueda y tus permisos actuales.")
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) { // lista-apilada: lista junto al detalle
                    sites.forEach { site ->
                        BpmRecordCard(site.name, subtitle = listOf(site.address, site.district).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { null },
                            selected = site.id == highlighted, onOpen = { selectedId = site.id })
                    }
                }
            }
        }
    }
}

@Composable
private fun AdminSiteDetail(site: Location, canManage: Boolean, onBack: () -> Unit, onEdit: () -> Unit) {
    BpmDetailPane(site.name, subtitle = site.address, onBack = onBack,
        actions = if (canManage) ({ BpmChunkyButton("Editar sede", onEdit, tone = BpmChunkyTone.White) }) else null) {
        BpmSection("Datos") {
            BpmDetailFields(listOf(
                BpmDetailField("Dirección", site.address),
                BpmDetailField("Distrito", site.district),
                BpmDetailField("Teléfono", site.contactPhone),
                BpmDetailField("Zona horaria", site.timezone),
            ))
        }
    }
}
