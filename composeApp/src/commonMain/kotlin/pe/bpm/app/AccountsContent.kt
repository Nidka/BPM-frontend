package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.features.accounts.presentation.*

/**
 * La raíz entrega el contexto autorizado y las pantallas abren y cierran su estado con él: al salir se
 * descartan listas, formularios y tokens en memoria. El backend vuelve a validar el tenant y el rol.
 */
@Composable
internal fun AccountsManagementContent(userId: String, organizationId: String, canManage: Boolean, actorIsMember: Boolean,
    team: TeamViewModel, invitations: InvitationsViewModel, organization: OrganizationViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(userId, organizationId, canManage, actorIsMember) {
        team.open(organizationId, canManage, actorIsMember)
        invitations.open(organizationId, canManage)
        organization.open(organizationId, canManage)
    }
    DisposableEffect(userId, organizationId) { onDispose { team.reset(); invitations.reset(); organization.reset() } }
    AccountsManagementScreen(team, invitations, organization, userId, modifier)
}

@Composable
internal fun DevicesContent(userId: String, devices: DevicesViewModel, modifier: Modifier = Modifier) {
    LaunchedEffect(userId) { devices.open() }
    DisposableEffect(userId) { onDispose { devices.reset() } }
    DevicesScreen(devices, modifier)
}

/** [token] llega del enlace del correo; solo precarga el campo, la persona decide cuándo aceptar. */
@Composable
internal fun InvitationAcceptContent(userId: String, token: String?, accept: InvitationAcceptViewModel, onAccepted: () -> Unit, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val state by accept.state.collectAsStateWithLifecycle()
    LaunchedEffect(userId, token) { accept.openWithLink(token) }
    LaunchedEffect(state.accepted) { if (state.accepted != null) onAccepted() }
    DisposableEffect(userId) { onDispose { accept.reset() } }
    InvitationAcceptScreen(accept, onContinue, modifier)
}
