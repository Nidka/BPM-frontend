package pe.bpm

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import pe.bpm.app.App
import pe.bpm.app.PublicCertificateVerification
import pe.bpm.app.PublicEmailAction
import pe.bpm.features.identity.domain.EmailActionLink
import pe.bpm.features.identity.domain.EmailActionMode
import androidx.compose.runtime.remember
import pe.bpm.core.storage.BrowserDraftStorage
import kotlinx.browser.document
import kotlinx.browser.window

/** Ruta pública de verificación de constancias; el código son 24 caracteres base32. */
private val verificationPath = Regex("^/verificar-cumplimiento/([A-Za-z2-7]{24})/?$")

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    val location = window.location
    // Enlaces de correo de Firebase (verificar correo, restablecer contraseña): https://appbpm.com/auth/accion?mode=…&oobCode=…
    if (EmailActionLink.isRoute(location.pathname, location.hash)) {
        val request = EmailActionLink.parse(location.search, location.hash)
        document.title = when (request?.mode) {
            EmailActionMode.VerifyEmail -> "Verifica tu correo · BPM"
            EmailActionMode.ResetPassword -> "Crea tu nueva contraseña · BPM"
            EmailActionMode.RecoverEmail -> "Recupera tu correo · BPM"
            null -> "Enlace de correo · BPM"
        }
        ComposeViewport { PublicEmailAction(request) { destination -> window.location.assign(destination) } }
        return
    }
    // Correo precargado en el acceso (viene de «Iniciar sesión» tras crear una contraseña nueva); se retira de la dirección al leerlo.
    val initialEmail = EmailActionLink.prefilledEmail(location.search).orEmpty()
    if (location.search.contains("correo=")) window.history.replaceState(null, "", location.pathname + location.hash)
    val code = verificationPath.matchEntire(window.location.pathname)?.groupValues?.get(1)?.uppercase()
    ComposeViewport {
        if (code != null) PublicCertificateVerification(code)
        else { val drafts = remember { BrowserDraftStorage() }; val offline = remember { pe.bpm.core.offline.BrowserOffline.platform() }; App(initialEmail = initialEmail, draftStorage = drafts, offline = offline, onNavHostReady = { it.bindToBrowserNavigation() }, onBackNavigation = { window.history.back() }) }
    }
}
