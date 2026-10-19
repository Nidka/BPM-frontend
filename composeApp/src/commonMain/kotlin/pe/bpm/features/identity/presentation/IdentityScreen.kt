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
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.features.identity.domain.hasPendingRegistration

@Composable
fun IdentityScreen(
    state: IdentityUiState, onLogin: (String, String) -> Unit, onLogout: () -> Unit,
    onVerify: () -> Unit, googleSupported: Boolean, onGoogle: () -> Unit,
    onMode: (AuthMode) -> Unit, onRegister: (String, String, String, String, String) -> Unit,
    onRecover: (String) -> Unit, onResend: () -> Unit, onConfirmEmail: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(state.mode) { scroll.scrollTo(0) }
    // Contraseñas efímeras: solo viven en el formulario mientras se valida el intento.
    var password by remember(state.mode) { mutableStateOf("") }
    var confirmation by remember(state.mode) { mutableStateOf("") }
    var visible by remember(state.mode) { mutableStateOf(false) }
    val autofill = LocalAutofillManager.current
    LaunchedEffect(state.busy, state.errors) {
        if (!state.busy && state.errors.isNotEmpty()) {
            autofill?.cancel()
            password = ""; confirmation = ""
        }
    }
    fun changeMode(mode: AuthMode) { autofill?.cancel(); password = ""; confirmation = ""; onMode(mode) }
    val navigation = rememberBpmFormNavigation(when (state.mode) {
        AuthMode.Register -> listOf("firstName", "lastName", "email", "password", "confirmation")
        AuthMode.Login -> listOf("email", "password")
        else -> listOf("email")
    })
    val fields = state.errors.flatMap { it.fields.entries }.associate { it.key to it.value }
    val profile = state.profile
    val needsVerificationEmail = state.verificationDeliveryFailed && !state.verificationEmailSent
    val sessionErrors = state.errors.filter { it.code == "SESSION_EXPIRED" || it.code == "UNAUTHENTICATED" }
    val title = when {
        profile != null -> if (state.errors.isEmpty()) "Tu cuenta BPM" else "Revisa tu conexión"
        state.mode == AuthMode.Register -> "Crea tu cuenta"
        state.mode == AuthMode.Recovery -> "Recupera el acceso"
        state.mode == AuthMode.Verification -> "Confirma tu correo"
        else -> "Bienvenido de nuevo"
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) {
        val wide = maxWidth >= 900.dp && maxHeight >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) IdentityWelcomePane(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(horizontal = if (wide) 48.dp else 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = if (wide) Arrangement.Center else Arrangement.Top) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (!wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Brand()
                Mascot(Modifier.size(80.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(when {
                    profile != null -> "Consulta tu cuenta y tus permisos de acceso."
                    state.mode == AuthMode.Register -> "Completa tus datos. Después te pediremos confirmar tu correo."
                    state.mode == AuthMode.Recovery -> "Te ayudaremos a volver a tu cuenta."
                    state.mode == AuthMode.Verification -> "Solo falta confirmar tu correo para continuar."
                    else -> "Accede de forma segura a tu espacio de trabajo."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sessionErrors.isNotEmpty()) BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Text("Tu sesión ha terminado", fontWeight = FontWeight.Bold)
                sessionErrors.forEach { Text(it.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            }
            if (profile == null && state.mode in listOf(AuthMode.Login, AuthMode.Register)) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(4.dp)) {
                    listOf(AuthMode.Login to "Entrar", AuthMode.Register to "Crear cuenta").forEach { (mode, label) ->
                        Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(11.dp),
                            color = if (state.mode == mode) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.outlineVariant,
                            shadowElevation = if (state.mode == mode) 2.dp else 0.dp) {
                            BpmTextButton(onClick = { changeMode(mode) }, enabled = !state.busy) { Text(label, fontWeight = FontWeight.SemiBold) }
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
                    } else if (state.mode == AuthMode.Verification) {
                        Text("Correo de tu cuenta", style = MaterialTheme.typography.labelLarge)
                        state.verificationEmail?.let { Text(it, fontWeight = FontWeight.SemiBold) }
                        if (needsVerificationEmail) {
                            Text("Solicita el enlace con «Reintentar envío». Cuando lo recibas, ábrelo y vuelve a esta pantalla.")
                        } else {
                            Text("1. Abre el correo de BPM y pulsa el enlace de verificación.")
                            Text("2. Vuelve a esta pantalla y pulsa «Ya confirmé mi correo».")
                            Text("Si no lo encuentras, revisa la carpeta de spam.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        if (state.mode == AuthMode.Register) {
                            AuthField(firstName, { firstName = it }, "Nombres", fields["firstName"], !state.busy, capitalization = KeyboardCapitalization.Words, navigation = navigation["firstName"])
                            AuthField(lastName, { lastName = it }, "Apellidos", fields["lastName"] ?: fields["displayName"], !state.busy, capitalization = KeyboardCapitalization.Words, navigation = navigation["lastName"])
                        }
                        AuthField(email, { email = it }, "Correo electrónico", fields["email"], !state.busy, KeyboardType.Email,
                            autofillType = (if (state.mode == AuthMode.Register) ContentType.NewUsername else ContentType.Username) + ContentType.EmailAddress, navigation = navigation["email"])
                        if (state.mode != AuthMode.Recovery) {
                            AuthField(password, { password = it }, "Contraseña", fields["password"], !state.busy, KeyboardType.Password, !visible,
                                autofillType = if (state.mode == AuthMode.Register) ContentType.NewPassword else ContentType.Password, navigation = navigation["password"])
                            if (state.mode == AuthMode.Register) {
                                Text("Usa entre 8 y 128 caracteres. Combina letras, números y símbolos.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AuthField(confirmation, { confirmation = it }, "Confirmar contraseña", fields["confirmPassword"], !state.busy, KeyboardType.Password, !visible,
                                    autofillType = ContentType.NewPassword, navigation = navigation["confirmation"])
                            }
                            BpmTextButton(onClick = { visible = !visible }, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) {
                                Text(if (visible) "Ocultar contraseña" else "Mostrar contraseña")
                            }
                        }
                    }
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    state.errors.filterNot { it in sessionErrors }.forEach {
                        Text(it.message, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (profile != null) {
                        AuthButton("Verificar sesión", !state.busy, onVerify)
                        BpmSecondaryButton(onClick = { password = ""; confirmation = ""; email = ""; onLogout() }, modifier = Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
                    } else {
                        if (state.mode != AuthMode.Verification) AuthButton(when (state.mode) {
                            AuthMode.Register -> "Crear cuenta"; AuthMode.Recovery -> "Enviar instrucciones"; else -> "Iniciar sesión"
                        }, !state.busy) {
                            val submitted = password; val confirmed = confirmation
                            // Autofill captura los campos al completar la navegación. No se borran antes del resultado.
                            when (state.mode) {
                                AuthMode.Register -> onRegister(firstName, lastName, email, submitted, confirmed)
                                AuthMode.Recovery -> onRecover(email)
                                else -> onLogin(email, submitted)
                            }
                        }
                        if (state.mode == AuthMode.Verification && !needsVerificationEmail)
                            AuthButton(if (state.confirmingEmail) "Comprobando…" else "Ya confirmé mi correo", !state.busy, onConfirmEmail)
                        if (state.canResendVerification) {
                            val resendLabel = when {
                                state.busy && !state.confirmingEmail -> "Enviando correo…"
                                needsVerificationEmail -> "Reintentar envío"
                                state.verificationEmailSent -> "Reenviar correo"
                                else -> "Enviar correo de verificación"
                            }
                            if (needsVerificationEmail) AuthButton(resendLabel, !state.busy && state.resendCooldownSeconds == 0, onResend)
                            else BpmSecondaryButton(onClick = onResend, enabled = !state.busy && state.resendCooldownSeconds == 0,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(resendLabel) }
                            if (state.resendCooldownSeconds > 0) Text("Puedes reenviar en ${state.resendCooldownSeconds} s.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.mode == AuthMode.Verification && needsVerificationEmail)
                            BpmTextButton(onClick = onConfirmEmail, enabled = !state.busy) { Text(if (state.confirmingEmail) "Comprobando…" else "Ya confirmé mi correo") }
                        if (state.mode == AuthMode.Register && state.errors.any { it.code == "REGISTRATION_REJECTED" }) {
                            BpmSecondaryButton(onClick = { changeMode(AuthMode.Login) }, modifier = Modifier.fillMaxWidth()) { Text("Ir a iniciar sesión") }
                            BpmTextButton(onClick = { changeMode(AuthMode.Recovery) }) { Text("Recuperar contraseña") }
                        }
                        if (state.mode == AuthMode.Login) BpmTextButton(onClick = { changeMode(AuthMode.Recovery) }, enabled = !state.busy) { Text("¿Olvidaste tu contraseña?") }
                        if (state.mode in listOf(AuthMode.Recovery, AuthMode.Verification)) BpmTextButton(onClick = { changeMode(AuthMode.Login) }, enabled = !state.busy) { Text("Volver a iniciar sesión") }
                    }
                }
            }
            if (profile == null && googleSupported && state.mode in listOf(AuthMode.Login, AuthMode.Register)) {
                Text("O continúa con", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                GoogleSignInButton(onClick = { autofill?.cancel(); password = ""; confirmation = ""; onGoogle() }, enabled = !state.busy)
            }
        }
        }
        }
    }
}

@Composable
private fun AuthField(value: String, onValue: (String) -> Unit, label: String, error: String?, enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text, hidden: Boolean = false,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None, autofillType: ContentType? = null, navigation: BpmFieldNavigation) {
    BpmTextField(value, onValue, label, enabled = enabled, error = error, navigation = navigation,
        modifier = if (autofillType != null) Modifier.semantics { contentType = autofillType } else Modifier,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, capitalization = capitalization, autoCorrectEnabled = false))
}

@Composable
private fun AuthButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    RaisedButton(label, onClick, modifier = Modifier.fillMaxWidth(), enabled = enabled)
}
