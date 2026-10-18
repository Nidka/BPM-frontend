package pe.bpm

import androidx.compose.ui.window.ComposeUIViewController
import pe.bpm.app.App
import androidx.compose.runtime.remember
import pe.bpm.features.identity.data.KeychainSessionStore

fun MainViewController() = ComposeUIViewController {
    val store = remember { KeychainSessionStore() }
    App(sessionStore = store)
}
