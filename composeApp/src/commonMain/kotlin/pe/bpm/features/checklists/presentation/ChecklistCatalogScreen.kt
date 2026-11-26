package pe.bpm.features.checklists.presentation

import pe.bpm.core.designsystem.*

import pe.bpm.core.designsystem.BpmListDetail
import pe.bpm.core.designsystem.BpmSpacing
import pe.bpm.core.designsystem.BpmType
import pe.bpm.core.designsystem.BpmSecondaryButton
import pe.bpm.core.designsystem.BpmTextButton
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.PageNavigation
import pe.bpm.core.designsystem.BpmCard
import pe.bpm.core.designsystem.BpmGridLayout
import pe.bpm.core.designsystem.EmptyState
import pe.bpm.core.designsystem.RaisedButton
import pe.bpm.core.designsystem.BpmTextField
import pe.bpm.core.designsystem.BpmIntroCard
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import pe.bpm.features.checklists.domain.*

/** Contenido sin scroll propio: el contenedor de workspace gestiona el catálogo. */
@Composable
fun ChecklistCatalogScreen(
    state: ChecklistCatalogState,
    templateId: String?,
    onRefresh: () -> Unit,
    onSearch: (String) -> Unit,
    onSelect: (String) -> Unit,
    onUserActivity: () -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit,
    management: Boolean = false,
    onStart: ((String) -> Unit)? = null,
    onAssign: ((String) -> Unit)? = null,
    onVersions: ((String) -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (templateId == null && !management) BpmIntroCard("Cada criterio cuenta.",
            "Elige un control publicado, revisa sus preguntas y complétalo para una sede de tu organización.")
        // Lista–detalle: al consultar una plantilla, desde 840 dp la lista queda a la izquierda.
        val list: @Composable () -> Unit = {
        BpmTextField(state.query, { onUserActivity(); onSearch(it) }, "Buscar controles",
            placeholder = "Nombre, área o descripción", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        when {
            state.loading -> {
                BpmSkeletonRecordCards(3)
            }
            state.failure != null -> BpmCard(Modifier.fillMaxWidth()) {
                Text("No pudimos cargar los controles", style = MaterialTheme.typography.titleMedium)
                Text(failureMessage(state.failure), color = MaterialTheme.colorScheme.error)
                BpmChunkyButton("Reintentar", onRefresh, tone = BpmChunkyTone.White)
            }
            state.templates.isEmpty() && state.query.isEmpty() -> EmptyState("Todavía no hay controles publicados",
                "Cuando tu organización publique una plantilla, podrás consultar aquí sus preguntas y criterios.")
            else -> {
                Text("${state.templates.size} plantillas en esta página", style = MaterialTheme.typography.bodyMedium)
                if (state.visibleTemplates.isEmpty()) {
                    EmptyState("No encontramos coincidencias", "Prueba con otro nombre o área.")
                    BpmTextButton(onClick = { onSearch("") }) { Text("Limpiar búsqueda") }
                } else BpmGridLayout(state.visibleTemplates) { template, cardModifier ->
                    // Misma altura por fila y un solo criterio de columnas, como el resto de listas de registros.
                    TemplateCard(template, cardModifier, selected = template.id == templateId, onAssign = if (management) onAssign else null) { onSelect(template.id) }
                }
            }
        }
        PageNavigation(state.previous.size + 1, state.previous.isNotEmpty(), state.nextToken.isNotEmpty(), state.loading || state.failure != null, onPrevious, onNext)
        }
        if (templateId != null) {
            BpmListDetail(list = { Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) { list() } }, detail = {
            when {
                state.detailLoading -> BpmSkeletonDetail()
                state.detailFailure != null -> { Text(failureMessage(state.detailFailure), color = MaterialTheme.colorScheme.error); BpmChunkyButton("Reintentar", onRefresh, tone = BpmChunkyTone.White) }
                state.detail != null && state.detail.id == templateId -> ChecklistDetail(state.detail, if (management) null else onStart, if (management) onAssign else null, if (management) onVersions else null)
                else -> BpmSkeletonDetail()
            }
            })
            return@Column
        }
        list()
    }
}

@Composable
private fun TemplateCard(template: ChecklistTemplate, modifier: Modifier, selected: Boolean = false, onAssign: ((String) -> Unit)? = null, onSelect: () -> Unit) {
    BpmRecordCard(template.name, modifier, status = BpmRecordStatus("Versión ${template.versionNumber}", BpmTone.Success),
        subtitle = template.area,
        details = listOfNotNull(template.description.takeIf { it.isNotBlank() },
            "Preguntas: ${template.items.size} · Obligatorias: ${template.items.count { it.required }}"),
        onOpen = onSelect, actionLabel = "Abrir plantilla", selected = selected,
        // «Programar» a un toque desde la lista: antes había que abrir la plantilla para encontrarlo.
        textActions = listOfNotNull(onAssign?.let { assign -> "Programar en una sede" to { assign(template.id) } }))
}

/** Consulta de una versión publicada; la raíz coordina su ruta y el regreso al catálogo. */
@Composable
private fun ChecklistDetail(template: ChecklistTemplate, onStart: ((String) -> Unit)?, onAssign: ((String) -> Unit)?, onVersions: ((String) -> Unit)? = null) {
    // Detalle del registro (specs/LAYOUT.md §5): un solo botón principal; programar y versiones quedan como secundarios.
    BpmDetailPane(template.name, status = BpmRecordStatus("Versión ${template.versionNumber}"), subtitle = template.area, eyebrow = "Control",
        facts = listOf(BpmDetailFact("Preguntas", template.items.size.toString()), BpmDetailFact("Obligatorias", template.items.count { it.required }.toString()),
            BpmDetailFact("Con evidencia", template.items.count { it.evidenceRequiredOnFailure }.toString())),
        actions = if (onStart == null && onAssign == null && onVersions == null) null else ({
            when {
                onStart != null -> BpmChunkyButton("Preparar revisión", { onStart(template.id) }, Modifier.bpmPrimaryButtonWidth())
                onAssign != null -> BpmChunkyButton("Programar en una sede", { onAssign(template.id) }, Modifier.bpmPrimaryButtonWidth())
            }
            if (onStart != null && onAssign != null) BpmChunkyButton("Programar en una sede", { onAssign(template.id) }, tone = BpmChunkyTone.White)
            if (onVersions != null) BpmChunkyButton("Editar preguntas y ver versiones", { onVersions(template.id) }, tone = BpmChunkyTone.White)
        })) {
        BpmSection("Sobre este control") {
            if (template.description.isNotBlank()) Text(template.description)
            Text(if (onStart != null) "Para responder estas preguntas, pulsa Preparar revisión y elige la sede." else "Tu equipo utilizará estas preguntas al realizar el control desde su sección Control.",
                style = MaterialTheme.typography.bodyMedium)
        }
        BpmListenButton(template.name + ". " + template.items.joinToString(" ") { "${it.displayOrder}. ${it.label}." }, label = "Escuchar las preguntas")
        template.items.forEach { item -> Question(item) }
    }
}

@Composable
private fun Question(item: ChecklistItem) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text(item.sectionName, color = MaterialTheme.colorScheme.primary, style = BpmType.bodyStrong)
        Text("${item.displayOrder}. ${item.label}", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() })
        Text(if (item.required) "Respuesta obligatoria" else "Respuesta opcional", style = MaterialTheme.typography.bodyMedium)
        Text(when (item.responseType) {
            ChecklistResponseType.COMPLIANCE -> "Cumplimiento: cumple, no cumple o no aplica."
            ChecklistResponseType.TEXT -> "Respuesta de texto."
            ChecklistResponseType.TEMPERATURE -> "Medición de temperatura."
            ChecklistResponseType.NUMBER -> "Respuesta numérica."
        })
        if (item.minimumValue != null && item.maximumValue != null) {
            val unit = item.unit.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            Text("Criterio publicado: de ${decimal(item.minimumValue)} a ${decimal(item.maximumValue)}$unit.")
        }
        if (item.evidenceRequiredOnFailure) Text("Requiere evidencia ante un incumplimiento.")
    }
}

@Composable internal fun ChecklistQuestionPreview(template: ChecklistTemplate) {
    Text("Preguntas de esta revisión", style = MaterialTheme.typography.titleLarge)
    template.items.forEach { Question(it) }
}

private fun decimal(value: Double) = value.toString().removeSuffix(".0")

internal fun failureMessage(failure: ChecklistFailure): String = when (failure) {
    ChecklistFailure.SESSION_REQUIRED -> "Tu sesión terminó. Vuelve a iniciar sesión."
    ChecklistFailure.ACCESS_DENIED -> "Ya no tienes permiso para consultar estos controles. Solicita acceso al administrador de tu organización."
    ChecklistFailure.NOT_FOUND -> "La organización o el catálogo solicitado no está disponible."
    ChecklistFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentar."
    ChecklistFailure.CONNECTION -> "Revisa tu conexión y vuelve a intentar."
    ChecklistFailure.UNAVAILABLE -> "El servicio no está disponible. Inténtalo de nuevo."
    ChecklistFailure.INVALID_PAGE -> "La lista cambió. Actualízala para volver a empezar."
    ChecklistFailure.INVALID_RESPONSE -> "No pudimos interpretar los controles. Inténtalo de nuevo."
}
