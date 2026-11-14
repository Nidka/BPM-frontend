package pe.bpm.features.accounts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.accounts.domain.*

private enum class AccountsTab(val label: String) { Team("Equipo"), Invitations("Invitaciones"), Organization("Organización y sedes") }

/** Gestión de cuentas para ADMIN/SUPER_ADMIN; el backend vuelve a autorizar cada operación. */
@Composable
fun AccountsManagementScreen(team: TeamViewModel, invitations: InvitationsViewModel, organization: OrganizationViewModel, currentUserId: String, modifier: Modifier = Modifier) {
    val teamState by team.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AccountsTab.Team.name) }
    Column(modifier.fillMaxSize().widthIn(max = 960.dp).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (!teamState.allowed) { EmptyState("Gestión de cuentas restringida", "Solo el administrador de la organización puede gestionar a su equipo."); return@Column }
        StatusFilter(AccountsTab.entries.map { it.name to it.label }, tab, true) { tab = it }
        when (tab) {
            AccountsTab.Invitations.name -> InvitationsPane(invitations)
            AccountsTab.Organization.name -> OrganizationPane(organization)
            else -> TeamPane(team, currentUserId)
        }
    }
}

/** Mis dispositivos: lista de los propios con revocación. */
@Composable
fun DevicesScreen(viewModel: DevicesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().widthIn(max = 960.dp).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Text("Estos son los dispositivos de tus organizaciones que reciben avisos. Si no reconoces alguno, revócalo.")
        AccountMessages(state.failure, state.notice)
        state.revoking?.let { device ->
            ConfirmCard("¿Revocar ${device.platform.label}?", state.busy, "Sí, revocar", viewModel::confirmRevoke, viewModel::cancelRevoke) {
                Text("Dejará de recibir avisos de ${device.organizationName.ifBlank { "tu organización" }}. Podrás activarlo de nuevo iniciando sesión en ese dispositivo.")
            }
        }
        PagedBlock(state.page, empty = { EmptyState("Sin dispositivos", "No tienes dispositivos activos con avisos.") },
            onRetry = viewModel::retry, onPrevious = viewModel::previous, onNext = viewModel::next) {
            BpmCardGrid(state.page.items, minCardWidth = 320.dp, maxColumns = 3) { device, cardModifier ->
                BpmCard(cardModifier) {
                    Text(device.platform.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(device.organizationName.ifBlank { "Organización" })
                    Text(device.lastSeenAt?.let { "Última actividad: ${displayDateTime(it)}" } ?: "Sin actividad registrada", style = MaterialTheme.typography.bodySmall)
                    Text("Registrado: ${displayDateTime(device.createdAt)}", style = MaterialTheme.typography.bodySmall)
                    BpmSecondaryButton(onClick = { viewModel.askRevoke(device) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Revocar") }
                }
            }
        }
    }
}

/** Aceptar una invitación: el token se pega aquí o llega desde el enlace del correo. */
@Composable
fun InvitationAcceptScreen(viewModel: InvitationAcceptViewModel, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().widthIn(max = 640.dp).verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        val accepted = state.accepted
        if (accepted != null) {
            BpmCard(Modifier.fillMaxWidth()) {
                Text("¡Listo! Te uniste a la organización", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Tu rol es ${accepted.role.label}.")
                RaisedButton("Continuar", onContinue, Modifier.fillMaxWidth())
            }
        } else BpmCard(Modifier.fillMaxWidth()) {
            Text("Aceptar una invitación", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Inicia sesión con el correo que recibió la invitación y pega aquí el código o el enlace del correo.")
            BpmTextField(state.input, viewModel::edit, "Código o enlace de la invitación", Modifier.fillMaxWidth(), error = state.fieldError, enabled = !state.busy)
            AccountMessages(state.failure, null)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            RaisedButton(if (state.busy) "Aceptando…" else "Aceptar invitación", viewModel::accept, Modifier.fillMaxWidth(), enabled = !state.busy && state.input.isNotBlank())
        }
    }
}
