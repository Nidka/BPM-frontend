package pe.bpm.features.accounts.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import pe.bpm.core.presentation.ShareResult
import pe.bpm.core.presentation.rememberTextSharer
import androidx.compose.ui.Modifier
import androidx.compose.runtime.saveable.rememberSaveable
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
        else BpmChunkyButton("Invitar por correo", viewModel::openForm, Modifier.bpmPrimaryButtonWidth(), enabled = !state.busy)
        state.revoking?.let { invitation ->
            BpmConfirmPanel("¿Revocar la invitación de ${invitation.email}?", "Sí, revocar", viewModel::confirmRevoke, viewModel::cancelRevoke, busy = state.busy) {
                Text("El enlace dejará de funcionar. Puedes invitar de nuevo a esa persona cuando quieras.")
            }
        }
        // Mientras se invita (formulario o enlace listo) la lista de invitaciones no compite por la atención: vuelve al cerrar.
        if (state.form == null && state.share == null) BpmListToolbar(
            filters = { BpmFilterChips(listOf<Pair<InvitationStatus?, String>>(null to "Todas") + InvitationStatus.entries.map { it to it.label },
                state.status, !state.busy, viewModel::selectStatus) },
            search = { m -> BpmSearchField(state.page.request.query, viewModel::search, "Buscar por correo", m, enabled = !state.busy, loading = state.page.loading) },
        )
        if (state.form == null && state.share == null) PagedBlock(state.page, empty = { EmptyState("Sin invitaciones", "No hay invitaciones con este filtro. Usa «Invitar por correo» para sumar personas.") },
            onRetry = viewModel::retry, onPrevious = viewModel::previous, onNext = viewModel::next) {
            BpmGridLayout(state.page.items) { invitation, cardModifier ->
                val wait = state.resendWait(invitation)
                BpmRecordCard(invitation.email, cardModifier,
                    status = BpmRecordStatus(invitation.status.label, invitationTone(invitation.status)),
                    subtitle = invitation.role.label,
                    details = listOf(if (invitation.role == MemberRole.ADMIN) "Todas las sedes" else locationNames(invitation.locationIds, state.locations), statusLine(invitation)),
                    actionLabel = if (AccountsRules.canResend(invitation.status)) (if (wait > 0) "Reenviar en ${wait} s" else "Reenviar") else null,
                    onAction = { if (wait == 0L) viewModel.resend(invitation) },
                    textActions = if (AccountsRules.canRevoke(invitation.status)) listOf("Revocar" to { viewModel.askRevoke(invitation) }) else emptyList(),
                    enabled = !state.busy)
            }
        }
    }
}

private fun invitationTone(status: InvitationStatus) = when (status) {
    InvitationStatus.PENDING -> BpmTone.Info
    InvitationStatus.ACCEPTED -> BpmTone.Success
    InvitationStatus.EXPIRED -> BpmTone.Warning
    InvitationStatus.REVOKED -> BpmTone.Neutral
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
        Text(if (share.resent) "Invitación reenviada" else "Invitación lista para compartir", style = MaterialTheme.typography.titleMedium)
        Text(if (isLink) "Enlace de invitación" else "Código de invitación", style = MaterialTheme.typography.labelLarge)
        Text(share.share.value, style = MaterialTheme.typography.bodyMedium)
        Text(InvitationSharing.notice(share.email), style = MaterialTheme.typography.bodySmall)
        if (!isLink) Text("En Mi cuenta, elige «Aceptar una invitación» y pega este código.", style = MaterialTheme.typography.bodySmall)
        BpmChunkyButton(if (isLink) "Copiar enlace" else "Copiar código", { sharer.copy(share.share.value) { feedback = if (it == ShareResult.COPIED) (if (isLink) "Enlace copiado." else "Código copiado.") else "No pudimos copiarlo. Selecciónalo y cópialo a mano." } }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        BpmChunkyButton("Enviar por WhatsApp", { runCatching { uriHandler.openUri(share.whatsappUrl(organizationName)) }.onFailure { feedback = "No pudimos abrir WhatsApp." } }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        BpmChunkyButton("Compartir", { sharer.share("Invitación a BPM", share.message(organizationName)) { feedback = when (it) {
            ShareResult.SHARED, ShareResult.CANCELLED -> null
            ShareResult.COPIED -> "Tu navegador no puede compartir; copiamos el mensaje."
            ShareResult.FAILED -> "No pudimos abrir la hoja de compartir."
        } } }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
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
    // Formulario por pasos (specs/LAYOUT.md §4.7): una decisión por paso; «Sedes» solo aparece si el rol las necesita.
    var step by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(form.fields) {
        BpmStepFormRules.firstStepWithError(listOf("email" in form.fields, "role" in form.fields, "locationIds" in form.fields))?.let { step = it }
    }
    // Con una sola sede no hay nada que elegir: queda marcada (un toque menos y sin botón bloqueado).
    LaunchedEffect(state.locations.singleOrNull()?.id, form.role) {
        val only = state.locations.singleOrNull()
        if (only != null && form.role.needsLocations && form.locationIds.isEmpty()) viewModel.toggleLocation(only.id)
    }
    val steps = buildList {
        add(BpmFormStep("¿A quién invitas?",
            description = "La persona recibirá un enlace válido por 7 días. Debe iniciar sesión con ese mismo correo, ya verificado, para aceptarlo.",
            canContinue = form.email.isNotBlank(), blockedHint = "Escribe el correo de la persona para continuar.") {
            BpmTextField(form.email, viewModel::editEmail, "Correo electrónico *", Modifier.fillMaxWidth(), error = form.fields["email"], enabled = !state.busy,
                inputKind = BpmInputKind.Email, rule = AccountsRules.email)
        })
        add(BpmFormStep("¿Qué podrá hacer?", description = "El rol define qué secciones verá en la app.") {
            RolePicker(form.role, !state.busy, viewModel::editRole, form.fields["role"])
        })
        if (form.role.needsLocations) add(BpmFormStep("¿En qué sedes trabajará?", description = "Marca todas las sedes donde trabajará.",
            canContinue = form.locationIds.isNotEmpty(), blockedHint = "Elige al menos una sede para continuar.") {
            LocationPicker("Sedes asignadas", state.locations, state.locationsFailure, form.locationIds, !state.busy,
                viewModel::toggleLocation, form.fields["locationIds"])
        })
    }
    BpmStepForm(steps, step, { step = it }, submitLabel = "Enviar invitación", onSubmit = viewModel::submit,
        busy = state.busy, busyLabel = "Enviando…", onCancel = viewModel::cancelForm)
}
