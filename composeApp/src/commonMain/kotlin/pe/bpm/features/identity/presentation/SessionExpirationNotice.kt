package pe.bpm.features.identity.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*

/** Capa modal del árbol principal: conserva el acceso web al cerrar, sin crear otra ventana Compose. */
@Composable
fun SessionExpirationNotice(status: SessionStatus, onContinue: () -> Unit, onLogout: () -> Unit) {
    val warning = status.warning ?: return
    val primary = remember { FocusRequester() }
    val secondary = remember { FocusRequester() }
    var primaryFocused by remember { mutableStateOf(true) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focusManager.clearFocus(force = true); keyboard?.hide(); primary.requestFocus() }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
        .pointerInput(Unit) { detectTapGestures {} }
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                if (warning.reason == SessionExpiryReason.INACTIVITY && primaryFocused && !status.continuing) secondary.requestFocus()
                else primary.requestFocus()
                true
            } else false
        }, contentAlignment = Alignment.Center) {
        Surface(Modifier.padding(16.dp).widthIn(max = 520.dp).fillMaxWidth().heightIn(max = maxHeight - 32.dp)
            .semantics { paneTitle = "Aviso de sesión"; dialog() }, shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface, tonalElevation = 6.dp) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BpmIcon(BpmSymbol.Clock)
                Text("Tu sesión está por vencer", style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Text(if (warning.reason == SessionExpiryReason.INACTIVITY)
                    "¿Sigues ahí? Tu sesión se cerrará por inactividad en ${remainingTime(warning.secondsRemaining)}."
                else "Tu sesión alcanzará su duración máxima en ${remainingTime(warning.secondsRemaining)}. Necesitarás iniciar sesión de nuevo.")
                Text("Los borradores conservados en este dispositivo estarán disponibles al volver a entrar con tu misma cuenta y permisos.",
                    style = MaterialTheme.typography.bodySmall)
                status.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (status.continuing) LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BpmSecondaryButton(onClick = if (warning.reason == SessionExpiryReason.INACTIVITY) onContinue else onLogout,
                        enabled = !status.continuing, modifier = Modifier.focusRequester(primary).onFocusChanged { primaryFocused = it.isFocused }) {
                        Text(if (status.continuing) "Comprobando…" else if (warning.reason == SessionExpiryReason.INACTIVITY) "Seguir conectado" else "Volver a iniciar sesión")
                    }
                    if (warning.reason == SessionExpiryReason.INACTIVITY)
                        BpmTextButton(onClick = onLogout, enabled = !status.continuing, modifier = Modifier.focusRequester(secondary)) { Text("Cerrar sesión") }
                }
            }
        }
    }
}

private fun remainingTime(seconds: Long): String = if (seconds < 60) "$seconds segundos" else "${seconds / 60} min ${seconds % 60} s"
