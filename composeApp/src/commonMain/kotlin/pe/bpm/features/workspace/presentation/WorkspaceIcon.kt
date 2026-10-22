package pe.bpm.features.workspace.presentation

import androidx.compose.runtime.Composable
import pe.bpm.core.designsystem.BpmIcon
import pe.bpm.core.designsystem.BpmSymbol

/** El destino aporta la etiqueta; el recurso visual se comparte con el resto de la app. */
@Composable internal fun WorkspaceIcon(destination: WorkspaceDestination) {
    BpmIcon(when (destination) {
        WorkspaceDestination.Home -> BpmSymbol.Home
        WorkspaceDestination.Learn -> BpmSymbol.Learn
        WorkspaceDestination.Operations, WorkspaceDestination.Supervision -> BpmSymbol.Control
        WorkspaceDestination.Team -> BpmSymbol.Team
        WorkspaceDestination.Company -> BpmSymbol.Company
        WorkspaceDestination.Account -> BpmSymbol.Account
    })
}
