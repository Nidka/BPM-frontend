package pe.bpm

import androidx.compose.ui.window.ComposeUIViewController
import pe.bpm.app.App
import pe.bpm.core.storage.AppleDraftStorage
import androidx.compose.runtime.remember
import pe.bpm.features.identity.data.KeychainSessionStore
import pe.bpm.features.identity.data.GoogleAuthProvider

fun MainViewController(googleAuthProvider: GoogleAuthProvider? = null) = ComposeUIViewController {
    val store = remember { KeychainSessionStore() }
    val drafts = remember { AppleDraftStorage() }
    App(draftStorage = drafts, sessionStore = store, googleAuthProvider = googleAuthProvider)
}
