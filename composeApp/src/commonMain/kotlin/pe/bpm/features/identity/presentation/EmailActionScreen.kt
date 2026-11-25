package pe.bpm.features.identity.presentation

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import pe.bpm.core.designsystem.*
import pe.bpm.features.identity.domain.NewPasswordRules
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done
import pe.bpm.resources.mascot_fix

/**
 * Página pública `/auth/accion` de BPM: reemplaza la página genérica en inglés de Firebase. Sin sesión ni navegación;
 * [onGoToLogin] recibe el correo (si se conoce) para precargarlo en el acceso.
 */
@Composable
fun EmailActionScreen(
    state: EmailActionState,
    onSubmitPassword: (String, String) -> Unit,
    onRetry: () -> Unit,
    onGoToLogin: (email: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Patrón de acceso: ruta pública, una columna centrada de 440 dp (todo el ancho en compacta).
    BpmAccessScreen(modifier) {
        Brand()
        when (state) {
            is EmailActionState.Working -> {
                val copy = EmailActionTexts.working(state.mode)
                ActionHeading(copy.title, copy.message)
                BpmSkeletonCard(lines = 3, label = copy.title)
            }
            is EmailActionState.EmailVerified -> ActionPanel(Res.drawable.mascot_done, "¡Listo! Tu correo está verificado",
                state.email?.let { "Ya puedes entrar a BPM con $it." } ?: "Ya puedes entrar a BPM con tu cuenta.") {
                BpmChunkyButton("Iniciar sesión", { onGoToLogin(state.email) }, Modifier.fillMaxWidth())
            }
            is EmailActionState.EmailRecovered -> ActionPanel(Res.drawable.mascot_done, "Recuperamos tu correo",
                (state.email?.let { "Tu cuenta volvió a usar $it. " } ?: "Tu cuenta volvió a su correo anterior. ") +
                    "Si no fuiste tú quien pidió el cambio, inicia sesión y cambia tu contraseña.") {
                BpmChunkyButton("Iniciar sesión", { onGoToLogin(state.email) }, Modifier.fillMaxWidth())
            }
            is EmailActionState.PasswordChanged -> ActionPanel(Res.drawable.mascot_done, "Contraseña actualizada",
                "Ya puedes entrar con ${state.email} y tu contraseña nueva.") {
                BpmChunkyButton("Iniciar sesión", { onGoToLogin(state.email) }, Modifier.fillMaxWidth())
            }
            is EmailActionState.NewPasswordForm -> NewPasswordForm(state, onSubmitPassword)
            is EmailActionState.Failed -> {
                val copy = EmailActionTexts.failure(state.mode, state.failure)
                ActionPanel(Res.drawable.mascot_fix, copy.title, copy.message) {
                    if (state.failure.retryable) BpmChunkyButton("Reintentar", onRetry, Modifier.fillMaxWidth())
                    BpmChunkyButton("Ir a iniciar sesión", { onGoToLogin(null) }, Modifier.fillMaxWidth(),
                        tone = if (state.failure.retryable) BpmChunkyTone.White else BpmChunkyTone.Action)
                }
            }
            EmailActionState.InvalidLink -> ActionPanel(Res.drawable.mascot_fix, EmailActionTexts.invalidLink.title, EmailActionTexts.invalidLink.message) {
                BpmChunkyButton("Ir a iniciar sesión", { onGoToLogin(null) }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Título anunciado como región activa: el lector de pantalla lee el resultado cuando cambia el estado. */
@Composable
private fun ActionHeading(title: String, message: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ActionPanel(image: DrawableResource, title: String, message: String, actions: @Composable ColumnScope.() -> Unit) {
    BpmCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
            Image(painterResource(image), contentDescription = null, modifier = Modifier.width(140.dp)) // medida: mascota del estado
            Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            actions()
        }
    }
}

@Composable
private fun NewPasswordForm(state: EmailActionState.NewPasswordForm, onSubmit: (String, String) -> Unit) {
    // Contraseñas efímeras: solo viven en este formulario.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val hidden: VisualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation()
    val navigation = rememberBpmFormNavigation(listOf("password", "confirmation"))
    val submit = { if (!state.submitting) onSubmit(password, confirmation) }
    ActionHeading("Crea tu nueva contraseña", "Para la cuenta ${state.email}.")
    BpmCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg)) {
            BpmTextField(password, { password = it }, "Contraseña nueva", enabled = !state.submitting, inputKind = BpmInputKind.Password,
                visualTransformation = hidden, rule = NewPasswordRules.rule, navigation = navigation["password"],
                modifier = Modifier.semantics { contentType = ContentType.NewPassword })
            BpmTextField(confirmation, { confirmation = it }, "Repite la contraseña", enabled = !state.submitting, inputKind = BpmInputKind.Password,
                visualTransformation = hidden, navigation = navigation["confirmation"], onSubmit = submit,
                modifier = Modifier.semantics { contentType = ContentType.NewPassword })
            BpmTextButton(onClick = { visible = !visible }, enabled = !state.submitting, contentPadding = PaddingValues(0.dp)) {
                Text(if (visible) "Ocultar contraseña" else "Mostrar contraseña")
            }
            Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                NewPasswordRules.checks(password, confirmation).forEach { check ->
                    val status = if (check.met) "Cumplido" else "Pendiente"
                    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$status: ${check.label}" },
                        horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        // Pendiente = círculo vacío (neutro, no un error); cumplido = visto. El estado también va en el texto accesible.
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            if (check.met) BpmIcon(BpmSymbol.Check, tint = MaterialTheme.colorScheme.primary)
                            else Box(Modifier.size(12.dp).border(2.dp, MaterialTheme.colorScheme.outline, CircleShape))
                        }
                        Text(check.label, style = MaterialTheme.typography.bodyMedium,
                            color = if (check.met) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Te recomendamos combinar letras, números y símbolos.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (state.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            BpmChunkyButton(if (state.submitting) "Guardando…" else "Guardar contraseña", submit, Modifier.fillMaxWidth(), enabled = !state.submitting)
        }
    }
}
