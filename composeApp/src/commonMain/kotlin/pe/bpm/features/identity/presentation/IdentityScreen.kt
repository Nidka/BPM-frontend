package pe.bpm.features.identity.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp

@Composable
fun IdentityScreen(
    state: IdentityUiState, onLogin: (String, String) -> Unit, onLogout: () -> Unit,
    onVerify: () -> Unit, onConnection: () -> Unit, googleSupported: Boolean, onGoogle: () -> Unit,
    onMode: (AuthMode) -> Unit, onRegister: (String, String, String, String) -> Unit,
    onRecover: (String) -> Unit, onResend: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    // Contraseñas efímeras; no se conservan al cambiar de pantalla o enviar.
    var password by remember(state.mode) { mutableStateOf("") }
    var confirmation by remember(state.mode) { mutableStateOf("") }
    var visible by remember(state.mode) { mutableStateOf(false) }
    val fields = state.errors.flatMap { it.fields.entries }.associate { it.key to it.value }
    val profile = state.profile
    val title = when {
        profile != null -> if (state.errors.isEmpty()) "Tu cuenta BPM" else "Revisa tu conexión"
        state.mode == AuthMode.Register -> "Crea tu cuenta"
        state.mode == AuthMode.Recovery -> "Recupera el acceso"
        state.mode == AuthMode.Verification -> "Revisa tu correo"
        else -> "Bienvenido de nuevo"
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(68.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("BPM", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(when {
                    profile != null -> "Tu identidad está conectada con el backend de BPM."
                    state.mode == AuthMode.Register -> "Un solo acceso para tu experiencia BPM."
                    state.mode == AuthMode.Recovery -> "Te ayudaremos a volver a tu cuenta."
                    state.mode == AuthMode.Verification -> "Confirma tu email antes de continuar."
                    else -> "Accede de forma segura a tu espacio de trabajo."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (profile == null && state.mode in listOf(AuthMode.Login, AuthMode.Register)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(4.dp)) {
                    listOf(AuthMode.Login to "Entrar", AuthMode.Register to "Crear cuenta").forEach { (mode, label) ->
                        Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp),
                            color = if (state.mode == mode) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.outlineVariant,
                            shadowElevation = if (state.mode == mode) 2.dp else 0.dp) {
                            TextButton(onClick = { onMode(mode) }, enabled = !state.busy) { Text(label, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
            }
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (profile != null) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(profile.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (profile.organizations.isEmpty()) Text("Cuenta activa. Todavía no tienes una organización asignada.")
                        profile.organizations.forEach { Text(it.name + " · " + it.role) }
                    } else if (state.mode != AuthMode.Verification) {
                        if (state.mode == AuthMode.Register) AuthField(name, { name = it }, "Nombre completo", fields["displayName"], !state.busy)
                        AuthField(email, { email = it }, "Correo electrónico", fields["email"], !state.busy, KeyboardType.Email)
                        if (state.mode != AuthMode.Recovery) {
                            AuthField(password, { password = it }, "Contraseña", fields["password"], !state.busy, KeyboardType.Password, !visible)
                            if (state.mode == AuthMode.Register) {
                                Text("Entre 8 y 128 caracteres. Firebase puede exigir una política adicional.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AuthField(confirmation, { confirmation = it }, "Confirmar contraseña", fields["confirmPassword"], !state.busy, KeyboardType.Password, !visible)
                            }
                            TextButton(onClick = { visible = !visible }, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) {
                                Text(if (visible) "Ocultar contraseña" else "Mostrar contraseña")
                            }
                        }
                    }
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                    state.errors.forEach {
                        Text(it.message, color = MaterialTheme.colorScheme.error)
                        Text("Código: ${it.code}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (profile != null) {
                        AuthButton("Verificar sesión", !state.busy, onVerify)
                        OutlinedButton(onClick = { password = ""; confirmation = ""; email = ""; onLogout() }, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
                    } else {
                        if (state.mode != AuthMode.Verification) AuthButton(when (state.mode) {
                            AuthMode.Register -> "Crear cuenta"; AuthMode.Recovery -> "Enviar instrucciones"; else -> "Iniciar sesión"
                        }, !state.busy) {
                            val submitted = password; val confirmed = confirmation
                            password = ""; confirmation = ""
                            when (state.mode) {
                                AuthMode.Register -> onRegister(name, email, submitted, confirmed)
                                AuthMode.Recovery -> onRecover(email)
                                else -> onLogin(email, submitted)
                            }
                        }
                        if (state.canResendVerification) OutlinedButton(onClick = onResend, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                            Text("Reenviar verificación")
                        }
                        if (state.mode == AuthMode.Login) TextButton(onClick = { onMode(AuthMode.Recovery) }, enabled = !state.busy) { Text("¿Olvidaste tu contraseña?") }
                        if (state.mode in listOf(AuthMode.Recovery, AuthMode.Verification)) TextButton(onClick = { onMode(AuthMode.Login) }, enabled = !state.busy) { Text("Volver a iniciar sesión") }
                    }
                }
            }
            if (profile == null && googleSupported && state.mode in listOf(AuthMode.Login, AuthMode.Register)) {
                Text("O continúa con", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                OutlinedButton(onClick = { password = ""; confirmation = ""; onGoogle() }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) { Text("Continuar con Google", fontWeight = FontWeight.SemiBold) }
            }
            Text("Tus permisos de organización los administra tu equipo. Crear una cuenta no concede acceso a datos de otros locales.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onConnection, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Comprobar conexión") }
            Text("By: Kadin, Evelyn y Piero", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun AuthField(value: String, onValue: (String) -> Unit, label: String, error: String?, enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, hidden: Boolean = false) {
    OutlinedTextField(value, onValue, modifier = Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, enabled = enabled, shape = RoundedCornerShape(14.dp), isError = error != null,
        supportingText = error?.let { { Text(it) } },
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false))
}

@Composable
private fun AuthButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(15.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
    }
}
