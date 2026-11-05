package pe.bpm.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.BpmSecondaryButton
import pe.bpm.core.designsystem.BpmTextButton
import pe.bpm.features.alerts.domain.AlertLocation
import pe.bpm.features.workspace.presentation.WorkspaceState
import pe.bpm.features.workspace.presentation.WorkspaceViewModel

/**
 * El mismo selector paginado resuelve filtro y ámbito sin desplegar listas al editar otros campos.
 */
@Composable
internal fun AlertScopeSelector(
  workspace: WorkspaceState,
  actions: WorkspaceViewModel,
  selectedId: String?,
  selectedName: String,
  companyLabel: String,
  enabled: Boolean,
  onSelect: (AlertLocation?) -> Unit,
) {
  var choosing by remember { mutableStateOf(false) }
  val label =
    if (selectedId == null) companyLabel
    else
      selectedName.ifBlank {
        workspace.workspace?.locations?.firstOrNull { it.id == selectedId }?.name
          ?: "Sede seleccionada"
      }
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    if (!choosing)
      BpmSecondaryButton({ choosing = true }, enabled = enabled) { Text("Ámbito: $label") }
    else {
      BpmSecondaryButton(
        {
          onSelect(null)
          choosing = false
        },
        enabled = enabled,
      ) {
        Text(companyLabel)
      }
      WorkspaceSiteSelector(workspace, actions, selectedId, label, enabled) {
        onSelect(AlertLocation(it.id, it.name))
        choosing = false
      }
      BpmTextButton({ choosing = false }) { Text("Conservar ámbito actual") }
    }
  }
}
