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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        // El shell no pone cabecera a las pantallas alojadas (`secondaryEditor`): cada una lleva la suya.
        BpmPageHeader("Cuentas y equipo")
        if (!teamState.allowed) { EmptyState("Gestión de cuentas restringida", "Solo el administrador de la organización puede gestionar a su equipo."); return@Column }
        BpmFilterChips(AccountsTab.entries.map { it.name to it.label }, tab) { tab = it }
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
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        // El shell no pone cabecera a las pantallas alojadas (`secondaryEditor`): cada una lleva la suya.
        BpmPageHeader("Mis dispositivos", description = "Estos son los dispositivos de tus organizaciones que reciben avisos. Si no reconoces alguno, revócalo.")
        AccountMessages(state.failure, state.notice)
        state.revoking?.let { device ->
            BpmConfirmPanel("¿Revocar ${device.platform.label}?", "Sí, revocar", viewModel::confirmRevoke, viewModel::cancelRevoke, busy = state.busy) {
                Text("Dejará de recibir avisos de ${device.organizationName.ifBlank { "tu organización" }}. Podrás activarlo de nuevo iniciando sesión en ese dispositivo.")
            }
        }
        PagedBlock(state.page, empty = { EmptyState("Sin dispositivos", "No tienes dispositivos activos con avisos.") },
            onRetry = viewModel::retry, onPrevious = viewModel::previous, onNext = viewModel::next) {
            BpmGridLayout(state.page.items) { device, cardModifier ->
                BpmRecordCard(device.platform.label, cardModifier, subtitle = device.organizationName.ifBlank { "Organización" },
                    details = listOf(device.lastSeenAt?.let { "Última actividad: ${displayDateTime(it)}" } ?: "Sin actividad registrada",
                        "Registrado: ${displayDateTime(device.createdAt)}"),
                    textActions = listOf("Revocar" to { viewModel.askRevoke(device) }), enabled = !state.busy)
            }
        }
    }
}

/** Aceptar una invitación: el token se pega aquí o llega desde el enlace del correo. */
@Composable
fun InvitationAcceptScreen(viewModel: InvitationAcceptViewModel, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        // El shell no pone cabecera a las pantallas alojadas (`secondaryEditor`): el título va fuera de la tarjeta.
        BpmPageHeader("Aceptar invitación")
        val accepted = state.accepted
        if (accepted != null) {
            BpmCard(Modifier.fillMaxWidth()) {
                Text("¡Listo! Te uniste a la organización", style = MaterialTheme.typography.titleLarge)
                Text("Tu rol es ${accepted.role.label}.")
                RaisedButton("Continuar", onContinue, Modifier.fillMaxWidth())
            }
        } else BpmCard(Modifier.fillMaxWidth()) {
            Text("Inicia sesión con el correo que recibió la invitación y pega aquí el código o el enlace del correo.")
            BpmTextField(state.input, viewModel::edit, "Código o enlace de la invitación", Modifier.fillMaxWidth(), error = state.fieldError, enabled = !state.busy)
            AccountMessages(state.failure, null)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            RaisedButton(if (state.busy) "Aceptando…" else "Aceptar invitación", viewModel::accept, Modifier.fillMaxWidth(), enabled = !state.busy && state.input.isNotBlank())
        }
    }
}
