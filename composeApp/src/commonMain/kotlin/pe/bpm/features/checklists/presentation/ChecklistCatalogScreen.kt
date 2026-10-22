package pe.bpm.features.checklists.presentation

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
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (templateId == null && !management) BpmIntroCard("Cada criterio cuenta.",
            "Elige un control publicado, revisa sus preguntas y complétalo para una sede de tu organización.")
        if (templateId != null) {
            when {
                state.detailLoading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                state.detailFailure != null -> { Text(failureMessage(state.detailFailure), color = MaterialTheme.colorScheme.error); BpmSecondaryButton(onClick = onRefresh) { Text("Reintentar") } }
                state.detail != null && state.detail.id == templateId -> ChecklistDetail(state.detail, if (management) null else onStart)
                else -> Text("Cargando plantilla…")
            }
            return@Column
        }
        BpmTextField(state.query, { onUserActivity(); onSearch(it) }, "Buscar controles",
            placeholder = "Nombre, área o descripción", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        when {
            state.loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Cargando controles…")
            }
            state.failure != null -> BpmCard(Modifier.fillMaxWidth()) {
                Text("No pudimos cargar los controles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(failureMessage(state.failure), color = MaterialTheme.colorScheme.error)
                BpmSecondaryButton(onClick = onRefresh) { Text("Reintentar") }
            }
            state.templates.isEmpty() && state.query.isEmpty() -> EmptyState("Todavía no hay controles publicados",
                "Cuando tu organización publique una plantilla, podrás consultar aquí sus preguntas y criterios.")
            else -> {
                Text("${state.templates.size} plantillas en esta página", style = MaterialTheme.typography.bodyMedium)
                if (state.visibleTemplates.isEmpty()) {
                    EmptyState("No encontramos coincidencias", "Prueba con otro nombre o área.")
                    BpmTextButton(onClick = { onSearch("") }) { Text("Limpiar búsqueda") }
                } else BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 720.dp) 2 else 1
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.visibleTemplates.chunked(columns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                row.forEach { template -> TemplateCard(template, Modifier.weight(1f)) { onSelect(template.id) } }
                                if (row.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
        PageNavigation(state.previous.size + 1, state.previous.isNotEmpty(), state.nextToken.isNotEmpty(), state.loading || state.failure != null, onPrevious, onNext)
    }
}

@Composable
private fun TemplateCard(template: ChecklistTemplate, modifier: Modifier, onSelect: () -> Unit) {
    BpmCard(modifier) {
        Text(template.area, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(template.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() })
        if (template.description.isNotBlank()) Text(template.description)
        Text("Versión ${template.versionNumber} · Publicada", style = MaterialTheme.typography.bodyMedium)
        Text("Preguntas: ${template.items.size} · Obligatorias: ${template.items.count { it.required }}", style = MaterialTheme.typography.bodyMedium)
        RaisedButton("Abrir plantilla", onSelect, Modifier.fillMaxWidth())
    }
}

/** Consulta de una versión publicada; la raíz coordina su ruta y el regreso al catálogo. */
@Composable
private fun ChecklistDetail(template: ChecklistTemplate, onStart: ((String) -> Unit)?) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (onStart != null) RaisedButton("Preparar revisión", { onStart(template.id) })
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
            Text("DETALLE DEL CONTROL", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(template.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text("${template.area} · Versión ${template.versionNumber}")
            if (template.description.isNotBlank()) Text(template.description)
            Text(if (onStart != null) "Para responder estas preguntas, pulsa Preparar revisión y elige la sede." else "Tu equipo utilizará estas preguntas al realizar el control desde su sección Control.", style = MaterialTheme.typography.bodyMedium)
        }
        template.items.forEach { item -> Question(item) }
    }
}

@Composable
private fun Question(item: ChecklistItem) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text(item.sectionName, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("${item.displayOrder}. ${item.label}", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
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
