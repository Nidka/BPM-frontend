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
internal fun TeamPane(viewModel: TeamViewModel, currentUserId: String, onAssignCourse: ((String, String) -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        AccountMessages(state.failure, state.notice)
        state.editor?.let { MemberEditorCard(it, state, viewModel) }
        state.action?.let { ActionCard(it, state, viewModel) }
        BpmListToolbar(
            filters = { BpmFilterChips(listOf(RecordStatus.ACTIVE to "Activas", RecordStatus.INACTIVE to "Inactivas"), state.status, !state.busy, viewModel::selectStatus) },
            search = { m -> BpmSearchField(state.page.request.query, viewModel::search, "Buscar personas", m, enabled = !state.busy, loading = state.page.loading) },
        )
        PagedBlock(state.page, empty = {
            EmptyState(if (state.status == RecordStatus.ACTIVE) "Sin personas activas" else "Sin personas inactivas",
                "No hay personas para esta búsqueda. Invita a alguien desde la pestaña Invitaciones.")
        }, onRetry = viewModel::retry, onPrevious = viewModel::previous, onNext = viewModel::next) {
            BpmGridLayout(state.page.items) { member, cardModifier ->
                val self = member.id == currentUserId
                val active = member.status == RecordStatus.ACTIVE
                BpmRecordCard(member.name, cardModifier,
                    status = if (active) BpmRecordStatus(member.role.label) else BpmRecordStatus("Inactiva", BpmTone.Neutral),
                    subtitle = member.email,
                    details = listOf(if (member.role == MemberRole.ADMIN) "Todas las sedes de esta organización" else locationNames(member.locationIds, state.locations)),
                    actionLabel = if (active) "Editar rol y sedes" else "Reactivar",
                    onAction = { if (active) viewModel.edit(member) else viewModel.askReactivate(member) },
                    textActions = if (!active) emptyList() else buildList {
                        if (member.role != MemberRole.ADMIN && !self) add("Transferir administración" to { viewModel.askTransfer(member) })
                        onAssignCourse?.let { assign -> add("Asignar curso" to { assign(member.id, member.name) }) }
                        add("Desactivar" to { viewModel.askDeactivate(member) })
                    },
                    enabled = !state.busy)
            }
        }
    }
}

@Composable private fun MemberEditorCard(editor: MemberEditor, state: TeamState, viewModel: TeamViewModel) {
    BpmFormScaffold("Editar a ${editor.member.name}", description = editor.member.email, actions = { BpmFormActions("Guardar cambios", viewModel::saveEditor, busy = state.busy, onCancel = viewModel::cancelEdit) }) {
            RolePicker(editor.role, !state.busy, viewModel::editRole, editor.fields["role"])
            if (editor.role.needsLocations) LocationPicker("Sedes asignadas", state.locations, state.locationsFailure, editor.locationIds, !state.busy,
                viewModel::toggleLocation, editor.fields["locationIds"])
            else Text("Un administrador ve todas las sedes; no hace falta elegirlas.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun ActionCard(action: MemberAction, state: TeamState, viewModel: TeamViewModel) {
    when (action) {
        is MemberAction.Deactivate -> BpmConfirmPanel("¿Desactivar a ${action.member.name}?", "Sí, desactivar", viewModel::confirmAction, viewModel::cancelAction, busy = state.busy) {
            Text("La persona perderá el acceso a esta organización y sus dispositivos dejarán de recibir avisos. Sus registros se conservan y puedes reactivarla cuando quieras.")
        }
        is MemberAction.Reactivate -> BpmConfirmPanel("¿Reactivar a ${action.member.name}?", "Sí, reactivar", viewModel::confirmAction, viewModel::cancelAction, busy = state.busy) {
            Text("Recuperará el rol y las sedes que tenía. Si su rol exige sedes y ya no hay ninguna activa, edítala primero.")
        }
        is MemberAction.Transfer -> BpmConfirmPanel("Transferir la administración a ${action.member.name}", "Transferir administración", viewModel::confirmAction, viewModel::cancelAction, busy = state.busy) {
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
