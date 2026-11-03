package pe.bpm.features.operations.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.domain.*
import pe.bpm.features.operations.domain.*

/**
 * La sede se selecciona en el contenedor de la aplicación, sin dependencias entre funcionalidades.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OperationsScreen(viewModel: OperationsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsState()
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (state.role == "WORKER") "Mis tareas de limpieza" else "Operación diaria",
            style = MaterialTheme.typography.headlineSmall,
        )
        if (state.form == null && state.detail == null) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OperationsResource.entries
                    .filter { OperationsAccess.canRead(state.role, it) }
                    .forEach { resource ->
                        FilterChip(
                            state.resource == resource,
                            { viewModel.resource(resource) },
                            label = { Text(resource.title) },
                            enabled = !state.loading && !state.busy,
                        )
                    }
            }
        }
        state.failure?.let { Text(failureText(it), color = MaterialTheme.colorScheme.error) }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        when {
            state.picker != null -> OperationsPicker(state, viewModel)
            state.saved != null -> {
                Text("Registro confirmado", style = MaterialTheme.typography.titleLarge)
                OperationDetails(state.saved!!)
                RaisedButton(
                    "Volver a la lista",
                    viewModel::closeForm,
                    Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                )
            }
            state.form != null -> OperationForm(state, viewModel)
            state.detail != null -> {
                OperationDetails(state.detail!!)
                BpmSecondaryButton(viewModel::closeDetail) { Text("Volver a la lista") }
            }
            else -> {
                if (state.resource.needsSite && state.siteId == null)
                    Text("Selecciona una sede para consultar esta sección.")
                else {
                    BpmSearchField(
                        state.query,
                        viewModel::query,
                        "Buscar en ${state.resource.title.lowercase()}",
                        loading = state.loading,
                    )
                    if (OperationsAccess.canCreate(state.role, state.resource))
                        RaisedButton(
                            if (state.resource == OperationsResource.EXECUTIONS)
                                "Registrar limpieza"
                            else
                                when (state.resource) {
                                    OperationsResource.SUPPLIERS -> "Agregar proveedor"
                                    OperationsResource.PRODUCTS -> "Agregar producto"
                                    OperationsResource.RECEIPTS -> "Registrar recepción"
                                    OperationsResource.PLANS -> "Crear plan de limpieza"
                                    else -> "Registrar limpieza"
                                },
                            viewModel::newForm,
                            Modifier.fillMaxWidth(),
                            enabled = !state.loading,
                        )
                    if (state.failure != null)
                        BpmTextButton(viewModel::refresh, enabled = !state.loading) {
                            Text("Reintentar consulta")
                        }
                    if (state.rows.isEmpty() && !state.loading && state.failure == null)
                        Text("Todavía no hay registros para esta consulta.")
                    if (state.resource == OperationsResource.LOTS)
                        Text(
                            "Los lotes más antiguos se muestran primero (PEPS). Revisa también su fecha de vencimiento antes de usarlos.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    state.rows.forEach { entry ->
                        BpmCard(Modifier.fillMaxWidth()) {
                            Column(Modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(entry.title(), style = MaterialTheme.typography.titleMedium)
                                Text(entry.subtitle())
                                BpmSecondaryButton({ viewModel.detail(entry) }) {
                                    Text("Ver detalle")
                                }
                                if (
                                    entry is OperationsEntry.ProductEntry &&
                                        OperationsAccess.canCreate(
                                            state.role,
                                            OperationsResource.PRODUCTS,
                                        )
                                )
                                    BpmTextButton({ viewModel.configure(entry.value) }) {
                                        Text("Configurar criterio térmico")
                                    }
                            }
                        }
                    }
                    PageNavigation(
                        state.page,
                        state.previous.isNotEmpty(),
                        state.nextToken.isNotEmpty(),
                        state.loading,
                        viewModel::previous,
                        viewModel::next,
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationsPicker(state: OperationsState, vm: OperationsViewModel) {
    Text(
        "Seleccionar ${state.picker!!.title.lowercase()}",
        style = MaterialTheme.typography.titleLarge,
    )
    BpmSearchField(
        state.pickerQuery,
        vm::pickerQuery,
        "Buscar por nombre",
        loading = state.pickerLoading,
    )
    if (state.pickerRows.isEmpty() && !state.pickerLoading)
        Text("No hay coincidencias. Puedes cambiar la búsqueda.")
    state.pickerRows.forEach { entry ->
        BpmCard(Modifier.fillMaxWidth()) {
            Column(Modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.title())
                Text(entry.subtitle(), style = MaterialTheme.typography.bodySmall)
                val active = (entry as? OperationsEntry.PlanEntry)?.value?.active != false
                BpmSecondaryButton({ vm.select(entry) }, enabled = active && !state.pickerLoading) {
                    Text(if (active) "Seleccionar" else "Plan inactivo")
                }
            }
        }
    }
    PageNavigation(
        state.pickerPage,
        state.pickerPrevious.isNotEmpty(),
        state.pickerNext.isNotEmpty(),
        state.pickerLoading,
        vm::pickerPrevious,
        vm::pickerNext,
    )
    BpmTextButton(vm::closePicker) { Text("Volver al formulario") }
}
