package pe.bpm.features.support.presentation

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*

/** Ajustes › «Contacto de soporte» (solo SUPER_ADMIN): correo y WhatsApp opcional que ve todo el mundo en Ayuda. */
@Composable
fun SupportSettingsSection(
    state: SupportState,
    onEmail: (String) -> Unit,
    onWhatsapp: (String) -> Unit,
    onSave: () -> Unit,
    onDismissNotice: () -> Unit = {},
) {
    BpmSettingsSection("Contacto de soporte", description = "Lo ven todas las personas en Ayuda, en «Contactar soporte».") {
        BpmTextField(state.email, onEmail, "Correo de soporte", Modifier.fillMaxWidth(), error = state.emailError,
            enabled = !state.saving, inputKind = BpmInputKind.Email, placeholder = "soporte@empresa.com")
        BpmTextField(state.whatsapp, onWhatsapp, "WhatsApp (opcional)", Modifier.fillMaxWidth(), error = state.whatsappError,
            enabled = !state.saving, inputKind = BpmInputKind.Phone, placeholder = "51987654321",
            supportingText = "Con código de país. Déjalo vacío si no quieres mostrar WhatsApp.")
        if (!state.loaded && !state.loading) Text("No pudimos leer el contacto guardado. Puedes escribirlo y guardar.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        BpmChunkyButton(if (state.saving) "Guardando…" else "Guardar contacto", onSave, Modifier.fillMaxWidth(), enabled = !state.saving)
        state.notice?.let { BpmInlineNotice(it, onDismiss = onDismissNotice, warning = state.noticeIsError) }
    }
}
