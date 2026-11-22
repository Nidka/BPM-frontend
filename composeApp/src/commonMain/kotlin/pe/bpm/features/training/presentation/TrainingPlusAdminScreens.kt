package pe.bpm.features.training.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import pe.bpm.core.designsystem.*
import pe.bpm.features.training.domain.*

/** Selector de persona o sede que aporta la raíz de la app (el catálogo de miembros y sedes vive en otra funcionalidad). */
typealias TargetSelector = @Composable (selectedId: String?, selectedName: String, enabled: Boolean, onSelect: (id: String, name: String) -> Unit) -> Unit

@Composable
private fun Failure(failure: TrainingFailure?) {
    failure?.let { Text(trainingFailureMessage(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

@Composable
private fun Notice(text: String?) {
    text?.let { Text(it, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

private fun targetLabel(target: AssignmentTarget) = when (target) {
    is AssignmentTarget.User -> "Persona: " + target.userName.ifBlank { "cuenta seleccionada" }
    is AssignmentTarget.Role -> "Rol: ${roleName(target.role)}" + (if (target.locationId != null) " en ${target.locationName.ifBlank { "una sede" }}" else " (todas las sedes)")
    is AssignmentTarget.Location -> "Sede: " + target.locationName.ifBlank { "sede seleccionada" }
}

private fun roleName(role: String) = when (role) { "ADMIN" -> "Administración"; "CHEF" -> "Chef"; else -> "Operativo" }

// ---- Asignaciones ----------------------------------------------------------------------------

@Composable
fun AssignmentAdminScreen(viewModel: TrainingAssignmentAdminViewModel, memberSelector: TargetSelector, locationSelector: TargetSelector, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Failure(state.failure)
        Notice(state.notice)
        val form = state.form
        if (form != null) AssignmentFormPanel(state, form, viewModel, memberSelector, locationSelector) else AssignmentList(state, viewModel)
    }
}

@Composable
private fun AssignmentList(state: AssignmentAdminState, viewModel: TrainingAssignmentAdminViewModel) {
    Text("Asignaciones", style = MaterialTheme.typography.headlineMedium)
    Text("Asigna cursos publicados a una persona, a un rol o a una sede, con fecha límite opcional. El aviso llega en el siguiente ciclo de notificaciones.")
    RaisedButton("Asignar un curso", viewModel::startCreate, Modifier.bpmPrimaryButtonWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BpmFilterChip(state.status == AssignmentRecordStatus.ACTIVE, { viewModel.filterStatus(AssignmentRecordStatus.ACTIVE) }, { Text("Activas") })
        BpmFilterChip(state.status == AssignmentRecordStatus.CANCELLED, { viewModel.filterStatus(AssignmentRecordStatus.CANCELLED) }, { Text("Canceladas") })
        BpmFilterChip(state.status == null, { viewModel.filterStatus(null) }, { Text("Todas") })
    }
    if (state.publishedCourses.isNotEmpty() || state.filterCourseId != null) {
        Text("Filtrar por curso", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BpmFilterChip(state.filterCourseId == null, { viewModel.filterCourse(null) }, { Text("Todos los cursos") })
            state.publishedCourses.take(12).forEach { BpmFilterChip(state.filterCourseId == it.id, { viewModel.filterCourse(it.id) }, { Text(it.code) }) }
        }
    } else BpmTextButton(onClick = viewModel::loadCourses) { Text("Filtrar por curso") }
    if (state.items.isEmpty() && !state.loading && state.failure == null) EmptyState("Sin asignaciones", "No hay asignaciones con este filtro.")
    BpmGridLayout(state.items) { item, m ->
        BpmCard(m) {
            Eyebrow(if (item.status == AssignmentRecordStatus.ACTIVE) "ACTIVA" else "CANCELADA")
            Text(item.course.title, style = MaterialTheme.typography.titleMedium)
            Text(targetLabel(item.target))
            Text(item.dueAt?.let { "Fecha límite: ${dateLabel(it)}" } ?: "Sin fecha límite", style = MaterialTheme.typography.bodySmall)
            Text("Creada el ${dateLabel(item.createdAt)}", style = MaterialTheme.typography.bodySmall)
            if (item.status == AssignmentRecordStatus.ACTIVE)
                BpmSecondaryButton(onClick = { viewModel.cancel(item.id) }, enabled = state.cancellingId == null, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.cancellingId == item.id) "Cancelando…" else "Cancelar asignación")
                }
        }
    }
    if (state.next != null) BpmSecondaryButton(onClick = viewModel::more, enabled = !state.loading) { Text("Ver más asignaciones") }
}

@Composable
private fun AssignmentFormPanel(state: AssignmentAdminState, form: AssignmentForm, viewModel: TrainingAssignmentAdminViewModel, memberSelector: TargetSelector, locationSelector: TargetSelector) {
    val issues = if (form.showIssues) AssignmentFormRules.issues(form, Clock.System.now()) else emptyMap()
    val enabled = !state.saving
    BpmFormLayout(actions = { BpmFormActions("Crear asignación", viewModel::create, busy = state.saving, onCancel = viewModel::cancelCreate) }) {
        Text("Asignar un curso", style = MaterialTheme.typography.titleLarge)
        BpmSection("Curso publicado") {
            if (state.publishedCourses.isEmpty()) Text("No hay cursos publicados para asignar.")
            state.publishedCourses.forEach { course ->
                BpmChoiceRow("${course.code} · ${course.title}", form.courseId == course.id, enabled) { viewModel.edit { it.copy(courseId = course.id, courseTitle = course.title) } }
            }
            issues["course"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        BpmSection("¿A quién?") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BpmFilterChip(form.kind == TargetKind.USER, { viewModel.edit { it.copy(kind = TargetKind.USER) } }, { Text("Una persona") }, enabled = enabled)
                BpmFilterChip(form.kind == TargetKind.ROLE, { viewModel.edit { it.copy(kind = TargetKind.ROLE) } }, { Text("Un rol") }, enabled = enabled)
                BpmFilterChip(form.kind == TargetKind.LOCATION, { viewModel.edit { it.copy(kind = TargetKind.LOCATION) } }, { Text("Una sede") }, enabled = enabled)
            }
            when (form.kind) {
                TargetKind.USER -> memberSelector(form.userId, form.userName, enabled) { id, name -> viewModel.edit { it.copy(userId = id, userName = name) } }
                TargetKind.LOCATION -> locationSelector(form.locationId, form.locationName, enabled) { id, name -> viewModel.edit { it.copy(locationId = id, locationName = name) } }
                TargetKind.ROLE -> {
                    listOf("WORKER", "CHEF", "ADMIN").forEach { role -> BpmChoiceRow(roleName(role), form.role == role, enabled) { viewModel.edit { it.copy(role = role) } } }
                    Text("Sede (opcional): si eliges una, solo recibe quien pertenece a ella.", style = MaterialTheme.typography.bodySmall)
                    locationSelector(form.locationId, form.locationName, enabled) { id, name -> viewModel.edit { it.copy(locationId = id, locationName = name) } }
                    if (form.locationId != null) BpmTextButton(onClick = { viewModel.edit { it.copy(locationId = null, locationName = "") } }, enabled = enabled) { Text("Quitar sede (todo el rol)") }
                }
            }
            issues["target"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            BpmTextField(form.dueDate, { v -> viewModel.edit { it.copy(dueDate = v.filter { c -> c.isDigit() || c == '-' }.take(10)) } }, "Fecha límite (AAAA-MM-DD, opcional)",
                error = issues["dueDate"], enabled = enabled, supportingText = "Vence al terminar ese día (hora universal).")
        }
        Failure(state.formFailure)
    }
}

// ---- Insignias -------------------------------------------------------------------------------

@Composable
fun AchievementAdminScreen(viewModel: TrainingAchievementAdminViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Failure(state.failure)
        Notice(state.notice)
        val editor = state.editor
        if (editor != null) {
            val shown = if (editor.showIssues) editor.issues else emptyList()
            val enabled = !state.saving
            BpmFormLayout(actions = { BpmFormActions("Guardar insignia", viewModel::save, busy = state.saving, onCancel = viewModel::cancelEdit) }) {
                BpmSection("Editar insignia", description = "${criterionLabel(editor.achievement)}. El tipo de criterio no se puede cambiar.") {
                    BpmTextField(editor.name, { v -> viewModel.edit { it.copy(name = v) } }, "Nombre", enabled = enabled)
                    BpmTextField(editor.description, { v -> viewModel.edit { it.copy(description = v) } }, "Descripción", singleLine = false, minLines = 2, enabled = enabled)
                    BpmTextField(editor.points, { v -> viewModel.edit { it.copy(points = v.filter(Char::isDigit).take(4)) } }, "Puntos (0 a ${AchievementRules.POINTS_MAX})", inputKind = BpmInputKind.Integer, enabled = enabled)
                    BpmTextField(editor.threshold, { v -> viewModel.edit { it.copy(threshold = v.filter(Char::isDigit).take(3)) } }, "Umbral (1 a ${AchievementRules.THRESHOLD_MAX})", inputKind = BpmInputKind.Integer, enabled = enabled)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Switch(editor.active, { v -> viewModel.edit { it.copy(active = v) } }, enabled = enabled)
                        Text(if (editor.active) "Activa: se otorga a quien cumpla el criterio" else "Inactiva: deja de otorgarse (las ya otorgadas se conservan)")
                    }
                    shown.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
                    Failure(state.saveFailure)
                }
            }
        } else {
            Text("Catálogo de logros", style = MaterialTheme.typography.headlineMedium)
            Text("Quienes aprenden ganan estas insignias de forma automática. Puedes ajustar nombre, puntos, umbral y vigencia.")
            if (state.items.isEmpty() && !state.loading && state.failure == null) EmptyState("Sin insignias", "El catálogo se crea la primera vez que alguien las necesita.")
            BpmGridLayout(state.items) { a, m ->
                BpmCard(m) {
                    Eyebrow(if (a.active) "ACTIVA" else "INACTIVA")
                    Text(a.name, style = MaterialTheme.typography.titleMedium)
                    if (a.description.isNotBlank()) Text(a.description)
                    Text(criterionLabel(a) + " · ${a.points} XP", style = MaterialTheme.typography.bodySmall)
                    BpmSecondaryButton(onClick = { viewModel.startEdit(a.id) }, modifier = Modifier.fillMaxWidth()) { Text("Editar") }
                }
            }
        }
    }
}

// ---- Rutas -----------------------------------------------------------------------------------

@Composable
fun LearningPathAdminScreen(viewModel: LearningPathAdminViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Failure(state.failure)
        Notice(state.notice)
        val editor = state.editor
        if (editor != null) PathEditorPanel(state, editor, viewModel) else {
            Text("Rutas de aprendizaje", style = MaterialTheme.typography.headlineMedium)
            Text("Agrupa cursos en un orden recomendado. Solo las rutas publicadas las ven quienes aprenden.")
            RaisedButton("Crear ruta", viewModel::startCreate, Modifier.bpmPrimaryButtonWidth())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BpmFilterChip(state.status == null, { viewModel.filter(null) }, { Text("Todas") })
                PathStatus.entries.forEach { status -> BpmFilterChip(state.status == status, { viewModel.filter(status) }, { Text(pathStatusLabel(status)) }) }
            }
            if (state.items.isEmpty() && !state.loading && state.failure == null) EmptyState("Sin rutas", "Todavía no hay rutas con este filtro.")
            BpmGridLayout(state.items) { path, m ->
                BpmCard(m) {
                    Eyebrow(pathStatusLabel(path.status).uppercase())
                    Text(path.title, style = MaterialTheme.typography.titleMedium)
                    Text("${path.courses.size} curso(s)", style = MaterialTheme.typography.bodySmall)
                    BpmSecondaryButton(onClick = { viewModel.openPath(path) }, modifier = Modifier.fillMaxWidth()) { Text(if (path.status == PathStatus.ARCHIVED) "Ver ruta" else "Editar ruta") }
                }
            }
            if (state.next != null) BpmSecondaryButton(onClick = viewModel::more, enabled = !state.loading) { Text("Ver más rutas") }
        }
    }
}

@Composable
private fun PathEditorPanel(state: PathAdminState, editor: PathEditor, viewModel: LearningPathAdminViewModel) {
    val enabled = !state.busy && editor.status != PathStatus.ARCHIVED
    val issues = if (editor.showIssues) editor.issues else emptyList()
    Eyebrow(if (editor.isNew) "RUTA NUEVA" else pathStatusLabel(editor.status).uppercase())
    Text(editor.title.ifBlank { "Ruta sin título" }, style = MaterialTheme.typography.headlineMedium)
    if (editor.status == PathStatus.ARCHIVED) Text("Esta ruta está archivada y ya no admite cambios.")
    Text(if (editor.dirty) "Tienes cambios sin guardar." else "Todo guardado.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    BpmTextField(editor.title, { v -> viewModel.edit { it.copy(title = v) } }, "Título", enabled = enabled)
    BpmTextField(editor.description, { v -> viewModel.edit { it.copy(description = v) } }, "Descripción", singleLine = false, minLines = 3, enabled = enabled)
    Text("Cursos de la ruta (${editor.courses.size} de ${PathRules.MAX_COURSES})", style = MaterialTheme.typography.titleLarge)
    editor.courses.forEachIndexed { index, course ->
        key(course.courseId) {
            BpmCard(Modifier.fillMaxWidth()) {
                Eyebrow("PASO ${index + 1}")
                Text("${course.code} · ${course.title}", style = BpmType.bodyStrong)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BpmSecondaryButton(onClick = { viewModel.moveCourse(course.courseId, -1) }, enabled = enabled && index > 0) { Text("Subir") }
                    BpmSecondaryButton(onClick = { viewModel.moveCourse(course.courseId, 1) }, enabled = enabled && index < editor.courses.lastIndex) { Text("Bajar") }
                    BpmTextButton(onClick = { viewModel.removeCourse(course.courseId) }, enabled = enabled) { Text("Quitar") }
                }
            }
        }
    }
    val available = state.allCourses.filter { c -> editor.courses.none { it.courseId.equals(c.id, true) } }
    if (enabled && available.isNotEmpty() && editor.courses.size < PathRules.MAX_COURSES) {
        Text("Agregar un curso", style = MaterialTheme.typography.titleMedium)
        available.take(30).forEach { c ->
            BpmSecondaryButton(onClick = { viewModel.addCourse(c) }, modifier = Modifier.fillMaxWidth()) { Text("+ ${c.code} · ${c.title}" + if (c.status != CourseStatus.PUBLISHED) " (sin publicar)" else "") }
        }
    }
    issues.forEach { Text("• $it", color = MaterialTheme.colorScheme.error) }
    state.editorFailure?.let {
        Failure(it)
        if (it == TrainingFailure.PATH_CONFLICT && !editor.isNew) BpmSecondaryButton(onClick = viewModel::reloadEditor) { Text("Recargar ruta (descarta mis cambios locales)") }
    }
    RaisedButton("Guardar ruta", viewModel::save, Modifier.bpmPrimaryButtonWidth(), enabled = enabled && (editor.dirty || editor.isNew))
    if (!editor.isNew && editor.status == PathStatus.DRAFT)
        BpmSecondaryButton(onClick = viewModel::publish, enabled = enabled && !editor.dirty, modifier = Modifier.bpmPrimaryButtonWidth()) { Text("Publicar ruta") }
    if (!editor.isNew && editor.status != PathStatus.ARCHIVED)
        BpmTextButton(onClick = viewModel::archive, enabled = enabled) { Text("Archivar ruta") }
    BpmTextButton(onClick = viewModel::closeEditor, enabled = !state.busy) { Text("Volver a la lista de rutas") }
}
