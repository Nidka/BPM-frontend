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
fun AccountsManagementScreen(
    team: TeamViewModel, invitations: InvitationsViewModel, organization: OrganizationViewModel, currentUserId: String, modifier: Modifier = Modifier,
    /** Pestaña con la que abre: «team», «invitations» u «organization» («Invitar persona» y «Editar sede» ahorran un toque). */
    startTab: String = "team",
    /** Falso cuando el shell ya desplaza la pantalla (Equipo del administrador): anidar dos desplazamientos verticales cierra la app. */
    scrollable: Boolean = true,
    /** «Asignar curso» en la tarjeta de una persona (id y nombre); nulo oculta la acción. */
    onAssignCourse: ((String, String) -> Unit)? = null,
) {
    val teamState by team.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable(startTab) { mutableStateOf(when (startTab) { "invitations" -> AccountsTab.Invitations.name; "organization" -> AccountsTab.Organization.name; else -> AccountsTab.Team.name }) }
    Column(modifier.then(if (scrollable) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        if (!teamState.allowed) { EmptyState("Gestión de cuentas restringida", "Solo el administrador de la organización puede gestionar a su equipo."); return@Column }
        // Pestañas de ancho fijo (un tercio cada una): al elegir una, las demás no se mueven.
        BpmSegmentedTabs(AccountsTab.entries.map { BpmTabOption(it.name, it.label) }, tab, { tab = it })
        when (tab) {
            AccountsTab.Invitations.name -> InvitationsPane(invitations)
            AccountsTab.Organization.name -> OrganizationPane(organization)
            else -> TeamPane(team, currentUserId, onAssignCourse)
        }
    }
}

/** Mis dispositivos: lista de los propios con revocación. */
@Composable
fun DevicesScreen(viewModel: DevicesViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).bpmAppear(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        Text("Estos son los dispositivos de tus organizaciones que reciben avisos. Si no reconoces alguno, revócalo.")
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
        val accepted = state.accepted
        if (accepted != null) {
            BpmCard(Modifier.fillMaxWidth()) {
                Text("¡Listo! Te uniste a la organización", style = MaterialTheme.typography.titleLarge)
                Text("Tu rol es ${accepted.role.label}.")
                BpmChunkyButton("Continuar", onContinue, Modifier.fillMaxWidth())
            }
        } else BpmCard(Modifier.fillMaxWidth()) {
            Text("Aceptar una invitación", style = MaterialTheme.typography.titleLarge)
            Text("Inicia sesión con el correo que recibió la invitación y pega aquí el código o el enlace del correo.")
            BpmTextField(state.input, viewModel::edit, "Código o enlace de la invitación", Modifier.fillMaxWidth(), error = state.fieldError, enabled = !state.busy)
            AccountMessages(state.failure, null)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            BpmChunkyButton(if (state.busy) "Aceptando…" else "Aceptar invitación", viewModel::accept, Modifier.fillMaxWidth(), enabled = !state.busy && state.input.isNotBlank())
        }
    }
}
