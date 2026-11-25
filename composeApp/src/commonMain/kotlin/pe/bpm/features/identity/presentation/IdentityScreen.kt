package pe.bpm.features.identity.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import pe.bpm.features.identity.domain.IdentityInputRules
import pe.bpm.core.domain.InputRule

@Composable
fun IdentityScreen(
    state: IdentityUiState, onLogin: (String, String) -> Unit, onLogout: () -> Unit,
    onVerify: () -> Unit, googleSupported: Boolean, onGoogle: () -> Unit,
    onMode: (AuthMode) -> Unit, onRegister: (String, String, String, String, String) -> Unit,
    onRecover: (String) -> Unit, onResend: () -> Unit, onConfirmEmail: () -> Unit,
    /** Correo ya escrito en el acceso (solo en «Entrar»), p. ej. después de crear una contraseña nueva desde el enlace del correo. */
    initialEmail: String = "",
) = key(state.mode) {
    // Cada recorrido posee sus campos, foco y validación visual; no se transfieren al cambiar de modo.
    var email by remember { mutableStateOf(if (state.mode == AuthMode.Login) initialEmail else "") }
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    // Contraseñas efímeras: solo viven en el formulario mientras se valida el intento.
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val autofill = LocalAutofillManager.current
    LaunchedEffect(state.busy, state.errors) {
        if (!state.busy && state.errors.any { it.code != "VALIDATION_ERROR" }) {
            autofill?.cancel()
            password = ""; confirmation = ""
        }
    }
    fun changeMode(mode: AuthMode) {
        if (mode == state.mode) return
        autofill?.cancel()
        password = ""; confirmation = ""
        onMode(mode)
    }
    val navigation = rememberBpmFormNavigation(when (state.mode) {
        AuthMode.Register -> listOf("firstName", "lastName", "email", "password", "confirmation")
        AuthMode.Login -> listOf("email", "password")
        else -> listOf("email")
    })
    val fields = state.errors.flatMap { it.fields.entries }.associate { it.key to it.value }
    LaunchedEffect(state.errors) {
        navigation.focusFirstError(fields.keys.map { if (it == "confirmPassword") "confirmation" else if (it == "displayName") "lastName" else it })
    }
    val profile = state.profile
    val needsVerificationEmail = state.verificationDeliveryFailed && !state.verificationEmailSent
    val sessionErrors = state.errors.filter { it.code == "SESSION_EXPIRED" || it.code == "UNAUTHENTICATED" }
    val title = when {
        profile != null -> if (state.errors.isEmpty()) "Tu cuenta BPM" else "Revisa tu conexión"
        state.mode == AuthMode.Register -> "Crea tu cuenta"
        state.mode == AuthMode.Recovery -> "Recupera el acceso"
        state.mode == AuthMode.Verification -> "Revisa tu correo"
        else -> "Bienvenido de nuevo"
    }
    BpmWindowBox(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding()) { windowClass, height ->
        // Patrón de acceso: tarjeta de 440 dp; con clase expandida o grande (desde 840 dp) se divide con el panel de bienvenida.
        val wide = (windowClass == BpmWindowClass.Expanded || windowClass == BpmWindowClass.Large) && height >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) IdentityWelcomePane(Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).padding(horizontal = if (wide) 48.dp else BpmSpacing.gutter(windowClass), vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = if (wide) Arrangement.Center else Arrangement.Top) {
        Column(Modifier.widthIn(max = 440.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) { // medida: tarjeta de ingreso (patrón Acceso)
            if (!wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Brand()
                Mascot(Modifier.size(80.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineLarge)
                Text(when {
                    profile != null -> "Consulta tu cuenta y tus permisos de acceso."
                    state.mode == AuthMode.Register -> "Completa tus datos. Después te pediremos confirmar tu correo."
                    state.mode == AuthMode.Recovery -> "Te ayudaremos a volver a tu cuenta."
                    state.mode == AuthMode.Verification -> "Te enviamos un enlace para confirmar que este correo es tuyo."
                    else -> "Accede de forma segura a tu espacio de trabajo."
                }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (sessionErrors.isNotEmpty()) BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
                Text("Tu sesión ha terminado", style = BpmType.bodyStrong)
                sessionErrors.forEach { Text(it.message,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                Text("Al volver a entrar con tu misma cuenta podrás recuperar los borradores conservados en este dispositivo.", style = MaterialTheme.typography.bodySmall)
            }
            if (profile == null && state.mode in listOf(AuthMode.Login, AuthMode.Register)) {
                BpmSegmentedTabs(listOf(BpmTabOption("login", "Entrar"), BpmTabOption("register", "Crear cuenta")),
                    if (state.mode == AuthMode.Login) "login" else "register",
                    { id -> if (!state.busy) changeMode(if (id == "login") AuthMode.Login else AuthMode.Register) })
            }
            // Enter en el último campo envía el formulario (igual que el botón); no hace nada mientras ya se está enviando.
            val submitForm: () -> Unit = {
                if (!state.busy) when (state.mode) {
                    AuthMode.Register -> onRegister(firstName, lastName, email, password, confirmation)
                    AuthMode.Recovery -> onRecover(email)
                    AuthMode.Login -> onLogin(email, password)
                    else -> Unit
                }
            }
            BpmCard(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (profile != null) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
                        Text(profile.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (profile.organizations.isEmpty()) Text("Cuenta activa. Todavía no tienes una organización asignada.")
                        profile.organizations.forEach { Text(it.name + " · " + it.role) }
                    } else if (state.mode == AuthMode.Verification) {
                        VerificationInstructions(state.verificationEmail, needsVerificationEmail, state.verificationEmailSent)
                    } else {
                        if (state.mode == AuthMode.Register) {
                            AuthField(firstName, { firstName = it }, "Nombres", fields["firstName"], !state.busy, inputKind = BpmInputKind.PersonName, rule = IdentityInputRules.personName, navigation = navigation["firstName"])
                            AuthField(lastName, { lastName = it }, "Apellidos", fields["lastName"] ?: fields["displayName"], !state.busy, inputKind = BpmInputKind.PersonName, rule = IdentityInputRules.personName, navigation = navigation["lastName"])
                        }
                        AuthField(email, { email = it }, "Correo electrónico", fields["email"], !state.busy, BpmInputKind.Email, rule = IdentityInputRules.email,
                            autofillType = (if (state.mode == AuthMode.Register) ContentType.NewUsername else ContentType.Username) + ContentType.EmailAddress, navigation = navigation["email"],
                            onSubmit = if (state.mode == AuthMode.Recovery) submitForm else null)
                        if (state.mode != AuthMode.Recovery) {
                            AuthField(password, { password = it }, "Contraseña", fields["password"], !state.busy, BpmInputKind.Password, !visible,
                                rule = if (state.mode == AuthMode.Register) IdentityInputRules.registrationPassword else IdentityInputRules.loginPassword,
                                autofillType = if (state.mode == AuthMode.Register) ContentType.NewPassword else ContentType.Password, navigation = navigation["password"],
                                onSubmit = if (state.mode == AuthMode.Login) submitForm else null)
                            if (state.mode == AuthMode.Register) {
                                Text("Usa entre 8 y 128 caracteres. Combina letras, números y símbolos.", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                AuthField(confirmation, { confirmation = it }, "Confirmar contraseña", fields["confirmPassword"], !state.busy, BpmInputKind.Password, !visible,
                                    rule = InputRule { if (it == password) null else "Las contraseñas no coinciden." },
                                    autofillType = ContentType.NewPassword, navigation = navigation["confirmation"], onSubmit = submitForm)
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
                        BpmChunkyButton("Cerrar sesión", { password = ""; confirmation = ""; email = ""; onLogout() }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
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
                            AuthButton(if (state.confirmingEmail) "Comprobando…" else "Ya lo verifiqué", !state.busy, onConfirmEmail)
                        if (state.canResendVerification) {
                            val resendLabel = when {
                                state.busy && !state.confirmingEmail -> "Enviando correo…"
                                needsVerificationEmail -> "Reintentar envío"
                                state.verificationEmailSent -> "Reenviar correo"
                                else -> "Enviar correo de verificación"
                            }
                            if (needsVerificationEmail) AuthButton(resendLabel, !state.busy && state.resendCooldownSeconds == 0, onResend)
                            else BpmChunkyButton(resendLabel, onResend, Modifier.fillMaxWidth().heightIn(min = 52.dp), tone = BpmChunkyTone.White, enabled = !state.busy && state.resendCooldownSeconds == 0)
                            if (state.resendCooldownSeconds > 0) Text("Puedes reenviar en ${state.resendCooldownSeconds} s.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.mode == AuthMode.Verification && needsVerificationEmail)
                            BpmTextButton(onClick = onConfirmEmail, enabled = !state.busy) { Text(if (state.confirmingEmail) "Comprobando…" else "Ya lo verifiqué") }
                        if (state.mode == AuthMode.Register && state.errors.any { it.code == "REGISTRATION_REJECTED" }) {
                            BpmChunkyButton("Ir a iniciar sesión", { changeMode(AuthMode.Login) }, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
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

/** Pasos para confirmar el correo: dirección usada, qué hacer y qué revisar si el mensaje no llega. */
@Composable
private fun VerificationInstructions(email: String?, needsEmail: Boolean, sent: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (sent && !needsEmail) "Enviamos el enlace a" else "Correo de tu cuenta", style = MaterialTheme.typography.labelLarge)
            email?.let { Text(it, style = BpmType.bodyStrong) }
        }
        if (needsEmail) {
            Text("Todavía no pudimos enviar el enlace. Pulsa «Reintentar envío»; cuando llegue, ábrelo y vuelve a esta pantalla.")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Qué hacer", style = MaterialTheme.typography.labelLarge)
                Text("1. Abre tu correo y busca el mensaje de BPM.")
                Text("2. Pulsa el enlace de verificación del mensaje.")
                Text("3. Vuelve aquí y pulsa «Ya lo verifiqué».")
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text("¿No lo ves?", style = MaterialTheme.typography.labelLarge)
                Text("Revisa spam, promociones o correo no deseado. El mensaje llega de $VERIFICATION_SENDER y puede tardar unos minutos.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Remitente con el que Firebase envía los correos de verificación; se muestra para que la persona pueda reconocerlo. */
internal const val VERIFICATION_SENDER = "noreply@app-bpm-af59c.firebaseapp.com"

@Composable
private fun AuthField(value: String, onValue: (String) -> Unit, label: String, error: String?, enabled: Boolean,
    inputKind: BpmInputKind = BpmInputKind.Text, hidden: Boolean = false,
    rule: InputRule? = null, autofillType: ContentType? = null, navigation: BpmFieldNavigation, onSubmit: (() -> Unit)? = null) {
    BpmTextField(value, onValue, label, enabled = enabled, error = error, navigation = navigation, onSubmit = onSubmit,
        modifier = if (autofillType != null) Modifier.semantics { contentType = autofillType } else Modifier,
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        inputKind = inputKind, rule = rule)
}

@Composable
private fun AuthButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    BpmChunkyButton(label, onClick, Modifier.fillMaxWidth(), enabled = enabled)
}
