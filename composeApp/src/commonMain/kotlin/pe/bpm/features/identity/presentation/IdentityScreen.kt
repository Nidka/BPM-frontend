package pe.bpm.features.identity.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun IdentityScreen(
    state: IdentityUiState,
    onLogin: (String, String) -> Unit,
    onLogout: () -> Unit,
    onVerify: () -> Unit,
    onConnection: () -> Unit,
) {
    // Nunca rememberSaveable para contraseñas. Se vacían al enviar y al salir.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val fields = state.errors.flatMap { it.fields.entries }.associate { it.key to it.value }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("BPM / AULA", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            val profile = state.profile
            Text(if (profile == null) "Bienvenido a BPM"
                else if (state.errors.isNotEmpty()) "Sesión no revalidada" else "Sesión verificada",
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(if (profile == null) "Ingresa con tu cuenta habilitada por el administrador."
                else if (state.errors.isNotEmpty()) "No pudimos confirmar nuevamente tu sesión. Puedes volver a intentar."
                else "Firebase y el backend confirmaron tu acceso a la plataforma.")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (profile == null) {
                        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Correo") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
                            isError = fields.containsKey("email"),
                            supportingText = fields["email"]?.let { message -> { Text(message) } },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Contraseña") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.busy,
                            isError = fields.containsKey("password"),
                            supportingText = fields["password"]?.let { message -> { Text(message) } },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    } else {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(profile.email)
                        if (profile.organizations.isEmpty()) Text("Tu cuenta está habilitada, sin organizaciones asignadas todavía.")
                        profile.organizations.forEach { Text("${it.name} · ${it.role}") }
                    }
                    state.errors.forEach { error ->
                        Text(error.message, color = MaterialTheme.colorScheme.error)
                        Text(error.code, style = MaterialTheme.typography.labelSmall)
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (profile == null) {
                        Button(onClick = {
                            val submittedPassword = password
                            password = ""
                            onLogin(email, submittedPassword)
                        }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) {
                            Text(if (state.busy) "Verificando…" else "Iniciar sesión")
                        }
                    } else {
                        Button(onClick = onVerify, enabled = !state.busy) { Text("Verificar sesión con la API") }
                        OutlinedButton(onClick = { password = ""; email = ""; onLogout() }) { Text("Cerrar sesión") }
                    }
                }
            }
            TextButton(onClick = onConnection) { Text("Comprobar conexión y despliegue") }
            Text("By: Kadin, Evelyn y Piero", style = MaterialTheme.typography.labelSmall)
        }
    }
}
