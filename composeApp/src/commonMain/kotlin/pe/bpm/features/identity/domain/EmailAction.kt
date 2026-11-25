package pe.bpm.features.identity.domain

import pe.bpm.core.domain.InputRule

/** Acciones de correo de Firebase que BPM atiende en su propia página (`/auth/accion`). */
enum class EmailActionMode(val firebaseName: String) {
    VerifyEmail("verifyEmail"), ResetPassword("resetPassword"), RecoverEmail("recoverEmail");

    companion object { fun fromFirebase(name: String?): EmailActionMode? = entries.firstOrNull { it.firebaseName == name } }
}

/** Enlace de acción ya validado. El código `oobCode` es de un solo uso: nunca se registra ni se muestra. */
data class EmailActionRequest(
    val mode: EmailActionMode,
    val oobCode: String,
    /** Destino de «Continuar» solo si pertenece a un origen de BPM; si no, es nulo. */
    val continueUrl: String? = null,
    val lang: String? = null,
)

/** Lectura del enlace que Firebase coloca en el correo: `?mode=…&oobCode=…&apiKey=…&continueUrl=…&lang=…`. */
object EmailActionLink {
    const val PATH = "/auth/accion"

    /** Orígenes a los que «Continuar» puede navegar. Cualquier otro destino se descarta para evitar redirecciones abiertas. */
    private val trustedHosts = setOf("appbpm.com", "www.appbpm.com", "localhost", "127.0.0.1")
    private val codePattern = Regex("[A-Za-z0-9_=.-]{1,512}")

    /** Verdadero si la ruta (o el hash, en navegación por `#`) corresponde a la página de acciones de correo. */
    fun isRoute(pathname: String, hash: String = ""): Boolean {
        fun matches(path: String) = path.trimEnd('/') == PATH
        return matches(pathname) || matches(hash.removePrefix("#").substringBefore('?').let { if (it.startsWith("/")) it else "/$it" })
    }

    /** Devuelve la solicitud o `null` si faltan datos, el modo no es compatible o el código no tiene forma válida. */
    fun parse(search: String, hash: String = ""): EmailActionRequest? {
        val hashQuery = hash.substringAfter('?', "")
        val params = parseQuery(search.removePrefix("?")).ifEmpty { parseQuery(hashQuery) }
        val mode = EmailActionMode.fromFirebase(params["mode"]) ?: return null
        val code = params["oobCode"]?.takeIf { codePattern.matches(it) } ?: return null
        return EmailActionRequest(mode, code, safeContinueUrl(params["continueUrl"]), params["lang"]?.takeIf { it.length in 2..16 && it.all { c -> c.isLetter() || c == '-' } })
    }

    /** Acepta solo `https://` (o http en loopback) hacia un host propio, sin credenciales. */
    fun safeContinueUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 2048 && it.none { c -> c.isISOControl() || c == '\\' } } ?: return null
        val match = Regex("^(https?)://([^/?#:@]+)(:\\d{1,5})?([/?#].*)?$", RegexOption.IGNORE_CASE).matchEntire(value) ?: return null
        val scheme = match.groupValues[1].lowercase()
        val host = match.groupValues[2].lowercase()
        if (host !in trustedHosts) return null
        val loopback = host == "localhost" || host == "127.0.0.1"
        if (scheme == "http" && !loopback) return null
        return value
    }

    /** Ruta del acceso de la app; con [email] válido lo lleva en `?correo=` para mostrarlo ya escrito. */
    fun loginDestination(email: String?): String {
        val valid = email?.trim()?.takeIf { it.isNotEmpty() && IdentityInputRules.email.error(it) == null } ?: return "/"
        return "/?correo=" + percentEncode(valid)
    }

    /** Correo recibido en `?correo=` si tiene forma válida; cualquier otro valor se ignora. */
    fun prefilledEmail(search: String): String? =
        parseQuery(search.removePrefix("?"))["correo"]?.trim()?.takeIf { it.isNotEmpty() && IdentityInputRules.email.error(it) == null }

    private fun percentEncode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val code = byte.toInt() and 0xFF
            val char = code.toChar()
            if (code < 128 && (char.isLetterOrDigit() || char in "-._~")) append(char)
            else append('%').append("0123456789ABCDEF"[code shr 4]).append("0123456789ABCDEF"[code and 15])
        }
    }

    internal fun parseQuery(query: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val name = percentDecode(pair.substringBefore('=')) ?: continue
            val value = percentDecode(pair.substringAfter('=', "")) ?: continue
            if (name !in result) result[name] = value
        }
        return result
    }

    private fun percentDecode(text: String): String? {
        if ('%' !in text && '+' !in text) return text
        val bytes = ArrayList<Byte>(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '+' -> { bytes += ' '.code.toByte(); index++ }
                char == '%' -> {
                    val code = text.substring(index + 1, minOf(index + 3, text.length)).takeIf { it.length == 2 }?.toIntOrNull(16) ?: return null
                    bytes += code.toByte(); index += 3
                }
                else -> { bytes += char.toString().encodeToByteArray().toList(); index++ }
            }
        }
        return bytes.toByteArray().decodeToString()
    }
}

/** Motivo público de un fallo; nunca incluye códigos de Firebase ni cuerpos de respuesta. */
enum class EmailActionFailure {
    /** El enlace venció, ya se usó o está incompleto. */
    InvalidOrExpired,
    UserDisabled, WeakPassword, RateLimited, Network, Unavailable;

    /** Reintentar sirve solo cuando el problema es de conexión o del servicio, no del enlace. */
    val retryable: Boolean get() = this == Network || this == Unavailable || this == RateLimited
}

sealed interface EmailActionResult<out T> {
    data class Success<T>(val value: T) : EmailActionResult<T>
    data class Failure(val reason: EmailActionFailure) : EmailActionResult<Nothing>
}

/** Puerto hacia Firebase para las acciones de correo. Los correos devueltos sirven solo para informar a la persona. */
interface EmailActionRepository {
    suspend fun verifyEmail(oobCode: String): EmailActionResult<String?>
    suspend fun checkPasswordResetCode(oobCode: String): EmailActionResult<String>
    suspend fun confirmPasswordReset(oobCode: String, newPassword: String): EmailActionResult<Unit>
    suspend fun recoverEmail(oobCode: String): EmailActionResult<String?>
}

/** Reglas visibles de la contraseña nueva; la comprobación final la hace Firebase. */
object NewPasswordRules {
    const val MIN = 8
    const val MAX = 128
    data class Check(val label: String, val met: Boolean)

    val rule = InputRule { if (it.length in MIN..MAX) null else "Usa entre $MIN y $MAX caracteres." }

    fun checks(password: String, confirmation: String): List<Check> = listOf(
        Check("Entre $MIN y $MAX caracteres", password.length in MIN..MAX),
        Check("Las dos contraseñas coinciden", password.isNotEmpty() && password == confirmation),
    )

    fun error(password: String, confirmation: String): String? =
        rule.error(password) ?: if (password != confirmation) "Las contraseñas no coinciden." else null
}

class VerifyEmailLink(private val repository: EmailActionRepository) {
    suspend operator fun invoke(oobCode: String) = repository.verifyEmail(oobCode)
}

class CheckPasswordResetLink(private val repository: EmailActionRepository) {
    suspend operator fun invoke(oobCode: String) = repository.checkPasswordResetCode(oobCode)
}

class ConfirmPasswordReset(private val repository: EmailActionRepository) {
    suspend operator fun invoke(oobCode: String, password: String, confirmation: String): EmailActionResult<Unit> {
        if (NewPasswordRules.error(password, confirmation) != null) return EmailActionResult.Failure(EmailActionFailure.WeakPassword)
        return repository.confirmPasswordReset(oobCode, password)
    }
}

class RecoverEmailLink(private val repository: EmailActionRepository) {
    suspend operator fun invoke(oobCode: String) = repository.recoverEmail(oobCode)
}
