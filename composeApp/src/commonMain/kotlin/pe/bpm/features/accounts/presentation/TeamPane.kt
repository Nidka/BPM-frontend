package pe.bpm.features.accounts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.features.accounts.domain.*

/** Equipo: miembros activos/inactivos con edición de rol y sedes, baja, reactivación y transferencia. */
@Composable
internal fun TeamPane(viewModel: TeamViewModel, currentUserId: String) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        AccountMessages(state.failure, state.notice)
        state.editor?.let { MemberEditorCard(it, state, viewModel) }
        state.action?.let { ActionCard(it, state, viewModel) }
        StatusFilter(listOf(RecordStatus.ACTIVE to "Activas", RecordStatus.INACTIVE to "Inactivas"), state.status, !state.busy, viewModel::selectStatus)
        BpmSearchField(state.page.request.query, viewModel::search, "Buscar personas", enabled = !state.busy, loading = state.page.loading)
        PagedBlock(state.page, empty = {
            EmptyState(if (state.status == RecordStatus.ACTIVE) "Sin personas activas" else "Sin personas inactivas",
                "No hay personas para esta búsqueda. Invita a alguien desde la pestaña Invitaciones.")
        }, onRetry = viewModel::retry, onPrevious = viewModel::previous, onNext = viewModel::next) {
            BpmGridLayout(state.page.items) { member, cardModifier ->
                BpmCard(cardModifier) {
                    Text(member.name, style = BpmType.bodyStrong)
                    Text(member.email)
                    Eyebrow(member.role.label + if (member.status == RecordStatus.INACTIVE) " · Inactiva" else "")
                    Text(if (member.role == MemberRole.ADMIN) "Todas las sedes de esta organización" else locationNames(member.locationIds, state.locations),
                        style = MaterialTheme.typography.bodySmall)
                    val self = member.id == currentUserId
                    BpmSecondaryButton(onClick = { viewModel.edit(member) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Editar rol y sedes") }
                    if (member.status == RecordStatus.ACTIVE) {
                        if (member.role != MemberRole.ADMIN && !self) BpmSecondaryButton(onClick = { viewModel.askTransfer(member) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Transferir administración") }
                        BpmTextButton(onClick = { viewModel.askDeactivate(member) }, enabled = !state.busy) { Text("Desactivar") }
                    } else BpmSecondaryButton(onClick = { viewModel.askReactivate(member) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Reactivar") }
                }
            }
        }
    }
}

@Composable private fun MemberEditorCard(editor: MemberEditor, state: TeamState, viewModel: TeamViewModel) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Editar a ${editor.member.name}", style = MaterialTheme.typography.titleMedium)
        Text(editor.member.email, style = MaterialTheme.typography.bodySmall)
        RolePicker(editor.role, !state.busy, viewModel::editRole, editor.fields["role"])
        if (editor.role.needsLocations) LocationPicker("Sedes asignadas", state.locations, state.locationsFailure, editor.locationIds, !state.busy,
            viewModel::toggleLocation, editor.fields["locationIds"])
        else Text("Un administrador ve todas las sedes; no hace falta elegirlas.", style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedButton(if (state.busy) "Guardando…" else "Guardar cambios", viewModel::saveEditor, Modifier.fillMaxWidth(), enabled = !state.busy)
        BpmSecondaryButton(onClick = viewModel::cancelEdit, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}

@Composable private fun ActionCard(action: MemberAction, state: TeamState, viewModel: TeamViewModel) {
    when (action) {
        is MemberAction.Deactivate -> ConfirmCard("¿Desactivar a ${action.member.name}?", state.busy, "Sí, desactivar", viewModel::confirmAction, viewModel::cancelAction) {
            Text("La persona perderá el acceso a esta organización y sus dispositivos dejarán de recibir avisos. Sus registros se conservan y puedes reactivarla cuando quieras.")
        }
        is MemberAction.Reactivate -> ConfirmCard("¿Reactivar a ${action.member.name}?", state.busy, "Sí, reactivar", viewModel::confirmAction, viewModel::cancelAction) {
            Text("Recuperará el rol y las sedes que tenía. Si su rol exige sedes y ya no hay ninguna activa, edítala primero.")
        }
        is MemberAction.Transfer -> ConfirmCard("Transferir la administración a ${action.member.name}", state.busy, "Transferir administración",
            viewModel::confirmAction, viewModel::cancelAction) {
            Text("${action.member.name} pasará a ser administrador" + if (state.actorIsMember) " y tú dejarás de serlo. Elige el rol que tendrás después." else ".")
            if (state.actorIsMember) {
                RolePickerForActor(action, state, viewModel)
            }
            Text("Esta acción no se puede deshacer por tu cuenta: solo un administrador podrá devolverte el rol.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun RolePickerForActor(action: MemberAction.Transfer, state: TeamState, viewModel: TeamViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tu nuevo rol", style = MaterialTheme.typography.labelLarge)
        listOf(MemberRole.CHEF, MemberRole.WORKER).forEach { role ->
            BpmChoiceRow(role.label, action.actorRole == role, !state.busy) { viewModel.transferRole(role) }
        }
        action.fields["actorRole"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (action.actorRole?.needsLocations == true) LocationPicker("Tus sedes", state.locations, state.locationsFailure, action.actorLocationIds, !state.busy,
            viewModel::toggleTransferLocation, action.fields["actorLocationIds"])
    }
}
