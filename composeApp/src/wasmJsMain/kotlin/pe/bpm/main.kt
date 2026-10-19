package pe.bpm

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import androidx.navigation.ExperimentalBrowserHistoryApi
import androidx.navigation.bindToBrowserNavigation
import pe.bpm.app.App
import androidx.compose.runtime.remember
import pe.bpm.core.storage.BrowserDraftStorage
import kotlinx.browser.window

@OptIn(ExperimentalComposeUiApi::class, ExperimentalBrowserHistoryApi::class)
fun main() {
    ComposeViewport { val drafts = remember { BrowserDraftStorage() }; App(draftStorage = drafts, onNavHostReady = { it.bindToBrowserNavigation() }, onBackNavigation = { window.history.back() }) }
}
