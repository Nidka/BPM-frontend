package pe.bpm.features.accounts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import pe.bpm.core.presentation.ShareResult
import pe.bpm.core.presentation.rememberTextSharer
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
        state.share?.let { InvitationSharePanel(it, state.organizationName, viewModel::closeShare) }
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
            BpmGridLayout(state.page.items) { invitation, cardModifier ->
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

/**
 * Panel con el enlace (o el código) de la invitación recién enviada y las tres formas de compartirlo.
 * El valor vive solo en memoria: al cerrar el panel desaparece y, si se pierde, se reenvía la invitación.
 */
@Composable
internal fun InvitationSharePanel(share: ShareState, organizationName: String, onClose: () -> Unit) {
    val sharer = rememberTextSharer()
    val uriHandler = LocalUriHandler.current
    var feedback by remember(share) { mutableStateOf<String?>(null) }
    val isLink = share.share is InvitationShare.Link
    BpmCard(Modifier.fillMaxWidth()) {
        Text(if (share.resent) "Invitación reenviada" else "Invitación lista para compartir", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(if (isLink) "Enlace de invitación" else "Código de invitación", style = MaterialTheme.typography.labelLarge)
        Text(share.share.value, style = MaterialTheme.typography.bodyMedium)
        Text(InvitationSharing.notice(share.email), style = MaterialTheme.typography.bodySmall)
        if (!isLink) Text("En Mi cuenta, elige «Aceptar una invitación» y pega este código.", style = MaterialTheme.typography.bodySmall)
        BpmSecondaryButton(onClick = { sharer.copy(share.share.value) { feedback = if (it == ShareResult.COPIED) (if (isLink) "Enlace copiado." else "Código copiado.") else "No pudimos copiarlo. Selecciónalo y cópialo a mano." } },
            modifier = Modifier.fillMaxWidth()) { Text(if (isLink) "Copiar enlace" else "Copiar código") }
        BpmSecondaryButton(onClick = { runCatching { uriHandler.openUri(share.whatsappUrl(organizationName)) }.onFailure { feedback = "No pudimos abrir WhatsApp." } },
            modifier = Modifier.fillMaxWidth()) { Text("Enviar por WhatsApp") }
        BpmSecondaryButton(onClick = { sharer.share("Invitación a BPM", share.message(organizationName)) { feedback = when (it) {
            ShareResult.SHARED, ShareResult.CANCELLED -> null
            ShareResult.COPIED -> "Tu navegador no puede compartir; copiamos el mensaje."
            ShareResult.FAILED -> "No pudimos abrir la hoja de compartir."
        } } }, modifier = Modifier.fillMaxWidth()) { Text("Compartir") }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        Text("No guardamos el enlace en este dispositivo ni aparece en la lista. Si lo pierdes, reenvía la invitación.", style = MaterialTheme.typography.bodySmall)
        BpmTextButton(onClick = onClose) { Text("Listo, cerrar") }
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
