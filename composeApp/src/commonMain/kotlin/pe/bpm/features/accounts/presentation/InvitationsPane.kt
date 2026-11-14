package pe.bpm.features.accounts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.displayDateTime
import pe.bpm.features.accounts.domain.*

/** Invitaciones por correo: invitar con rol y sedes, estado, reenviar (con espera de 1 minuto) y revocar. */
@Composable
internal fun InvitationsPane(viewModel: InvitationsViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Cuenta regresiva del reenvío: solo corre mientras hay una invitación en espera.
    val waiting = state.page.items.any { state.resendWait(it) > 0 }
    LaunchedEffect(waiting) { while (waiting) { delay(1000); viewModel.tick() } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.section)) {
        AccountMessages(state.failure, state.notice)
        if (state.form != null) InviteFormCard(state.form!!, state, viewModel)
        else RaisedButton("Invitar por correo", viewModel::openForm, Modifier.widthIn(max = 360.dp).fillMaxWidth(), enabled = !state.busy)
        state.revoking?.let { invitation ->
            ConfirmCard("¿Revocar la invitación de ${invitation.email}?", state.busy, "Sí, revocar", viewModel::confirmRevoke, viewModel::cancelRevoke) {
                Text("El enlace dejará de funcionar. Puedes invitar de nuevo a esa persona cuando quieras.")
            }
        }
        StatusFilter(listOf<Pair<InvitationStatus?, String>>(null to "Todas") + InvitationStatus.entries.map { it to it.label },
            state.status, !state.busy, viewModel::selectStatus)
        BpmSearchField(state.page.request.query, viewModel::search, "Buscar por correo", enabled = !state.busy, loading = state.page.loading)
        PagedBlock(state.page, empty = { EmptyState("Sin invitaciones", "No hay invitaciones con este filtro. Usa «Invitar por correo» para sumar personas.") },
            onRetry = viewModel::retry, onPrevious = viewModel::previous, onNext = viewModel::next) {
            BpmCardGrid(state.page.items, minCardWidth = 320.dp, maxColumns = 3) { invitation, cardModifier ->
                BpmCard(cardModifier) {
                    Text(invitation.email, fontWeight = FontWeight.Bold)
                    Eyebrow(invitation.status.label + " · " + invitation.role.label)
                    Text(if (invitation.role == MemberRole.ADMIN) "Todas las sedes" else locationNames(invitation.locationIds, state.locations), style = MaterialTheme.typography.bodySmall)
                    Text(statusLine(invitation), style = MaterialTheme.typography.bodySmall)
                    val wait = state.resendWait(invitation)
                    if (AccountsRules.canResend(invitation.status)) BpmSecondaryButton(onClick = { viewModel.resend(invitation) }, enabled = !state.busy && wait == 0L, modifier = Modifier.fillMaxWidth()) {
                        Text(if (wait > 0) "Reenviar en ${wait} s" else "Reenviar")
                    }
                    if (AccountsRules.canRevoke(invitation.status)) BpmTextButton(onClick = { viewModel.askRevoke(invitation) }, enabled = !state.busy) { Text("Revocar") }
                }
            }
        }
    }
}

private fun statusLine(invitation: Invitation) = when (invitation.status) {
    InvitationStatus.PENDING -> "Vence: ${displayDateTime(invitation.expiresAt)}. Enviada ${invitation.sendCount} ${if (invitation.sendCount == 1) "vez" else "veces"}."
    InvitationStatus.EXPIRED -> "Venció el ${displayDateTime(invitation.expiresAt)}. Reenvíala para renovar 7 días."
    InvitationStatus.ACCEPTED -> "La persona ya se unió al equipo."
    InvitationStatus.REVOKED -> "Ya no se puede usar."
}

@Composable private fun InviteFormCard(form: InviteForm, state: InvitationsState, viewModel: InvitationsViewModel) {
    BpmCard(Modifier.fillMaxWidth()) {
        Text("Invitar por correo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("La persona recibirá un enlace válido por 7 días. Debe iniciar sesión con ese mismo correo, ya verificado, para aceptarlo.", style = MaterialTheme.typography.bodySmall)
        BpmTextField(form.email, viewModel::editEmail, "Correo electrónico *", Modifier.fillMaxWidth(), error = form.fields["email"], enabled = !state.busy,
            inputKind = BpmInputKind.Email, rule = AccountsRules.email)
        RolePicker(form.role, !state.busy, viewModel::editRole, form.fields["role"])
        if (form.role.needsLocations) LocationPicker("Sedes asignadas", state.locations, state.locationsFailure, form.locationIds, !state.busy,
            viewModel::toggleLocation, form.fields["locationIds"])
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        RaisedButton(if (state.busy) "Enviando…" else "Enviar invitación", viewModel::submit, Modifier.fillMaxWidth(), enabled = !state.busy)
        BpmSecondaryButton(onClick = viewModel::cancelForm, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}
