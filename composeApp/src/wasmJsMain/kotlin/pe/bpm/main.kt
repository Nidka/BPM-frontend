package pe.bpm

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import pe.bpm.app.App
import pe.bpm.app.PublicCertificateVerification
import androidx.compose.runtime.remember
import pe.bpm.core.storage.BrowserDraftStorage
import kotlinx.browser.window

/** Ruta pública de verificación de constancias; el código son 24 caracteres base32. */
private val verificationPath = Regex("^/verificar-cumplimiento/([A-Za-z2-7]{24})/?$")

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    val code = verificationPath.matchEntire(window.location.pathname)?.groupValues?.get(1)?.uppercase()
    ComposeViewport {
        if (code != null) PublicCertificateVerification(code)
        else { val drafts = remember { BrowserDraftStorage() }; val offline = remember { pe.bpm.core.offline.BrowserOffline.platform() }; App(draftStorage = drafts, offline = offline, onNavHostReady = { it.bindToBrowserNavigation() }, onBackNavigation = { window.history.back() }) }
    }
}
