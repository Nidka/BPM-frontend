package pe.bpm.app

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import pe.bpm.core.designsystem.BpmTheme
import pe.bpm.core.designsystem.LocalUserActivity
import pe.bpm.core.designsystem.trackUserActivity
import pe.bpm.features.identity.presentation.SessionExpirationNotice
import pe.bpm.features.identity.presentation.SessionStatus

/** Compone actividad y aviso una sola vez, sin desplazar ni desmontar los formularios abiertos. */
@Composable internal fun SessionContent(status: SessionStatus, onActivity: () -> Unit,
    onContinue: () -> Unit, onLogout: () -> Unit, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUserActivity provides onActivity) {
        BpmTheme {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().safeDrawingPadding()
                    .then(if (status.warning != null) Modifier.clearAndSetSemantics {} else Modifier)
                    .trackUserActivity(if (status.warning == null) onActivity else { {} })) {
                    content()
                }
                SessionExpirationNotice(status, onContinue, onLogout)
            }
        }
    }
}
