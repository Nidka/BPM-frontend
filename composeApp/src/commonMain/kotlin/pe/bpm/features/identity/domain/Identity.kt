package pe.bpm.features.identity.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

data class Profile(
    val id: String,
    val email: String,
    val displayName: String,
    val organizations: List<Organization>,
    val platformRole: String = "USER",
)

data class Organization(val id: String, val name: String, val role: String)

data class SessionWindow(val absoluteExpiresAt: Long, val idleExpiresAt: Long) {
    val expiresAt: Long get() = minOf(absoluteExpiresAt, idleExpiresAt)
}

data class IdentityError(
    val code: String,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
)

sealed interface IdentityResult<out T> {
    data class Success<T>(val value: T) : IdentityResult<T>
    data class Failure(val errors: List<IdentityError>) : IdentityResult<Nothing>
}

interface IdentityRepository {
    suspend fun signIn(email: String, password: String): IdentityResult<Profile>
    suspend fun signInWithGoogle(): IdentityResult<Profile>
    suspend fun currentProfile(): IdentityResult<Profile>
    suspend fun register(name: String, email: String, password: String): IdentityResult<Unit>
    suspend fun resetPassword(email: String): IdentityResult<Unit>
    suspend fun resendVerification(): IdentityResult<Unit>
    suspend fun restoreSession(): IdentityResult<Profile> = currentProfile()
    fun sessionInvalidations(): Flow<Unit> = emptyFlow()
    fun sessionWindow(): Flow<SessionWindow?> = flowOf(null)
    suspend fun recordActivity(): IdentityResult<Unit> = IdentityResult.Success(Unit)
    suspend fun endSession(): IdentityResult<Unit> { signOut(); return IdentityResult.Success(Unit) }
    fun signOut()
}

private fun validEmail(email: String) = email.length <= 320 && email.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))

class Register(private val repository: IdentityRepository) {
    suspend operator fun invoke(name: String, email: String, password: String, confirmation: String): IdentityResult<Unit> {
        val fields = buildMap {
            if (name.trim().length !in 2..100 || name.any { it.code < 32 || it.code in 127..159 })
                put("displayName", "Ingresa un nombre de entre 2 y 100 caracteres.")
            if (!validEmail(email.trim())) put("email", "Ingresa un correo válido.")
            if (password.length !in 8..128) put("password", "Usa entre 8 y 128 caracteres.")
            if (password != confirmation) put("confirmPassword", "Las contraseñas no coinciden.")
        }
        if (fields.isNotEmpty()) return IdentityResult.Failure(listOf(IdentityError("VALIDATION_ERROR", "Revisa los campos del formulario.", fields)))
        return repository.register(name.trim(), email.trim(), password)
    }
}

class ResetPassword(private val repository: IdentityRepository) {
    suspend operator fun invoke(email: String): IdentityResult<Unit> {
        if (!validEmail(email.trim())) return IdentityResult.Failure(listOf(IdentityError("VALIDATION_ERROR", "Revisa el correo.", mapOf("email" to "Ingresa un correo válido."))))
        return repository.resetPassword(email.trim())
    }
}

class ResendVerification(private val repository: IdentityRepository) {
    suspend operator fun invoke(): IdentityResult<Unit> = repository.resendVerification()
}

class SignIn(private val repository: IdentityRepository) {
    suspend operator fun invoke(email: String, password: String): IdentityResult<Profile> {
        val normalizedEmail = email.trim()
        val fields = buildMap {
            if (!validEmail(normalizedEmail)) {
                put("email", "Ingresa un correo válido.")
            }
            // No transformar contraseñas ni imponer una política distinta a la del proveedor al iniciar sesión.
            if (password.isEmpty()) put("password", "Ingresa tu contraseña.")
            else if (password.length > 4096) put("password", "La contraseña es demasiado larga.")
        }
        if (fields.isNotEmpty()) return IdentityResult.Failure(listOf(
            IdentityError("VALIDATION_ERROR", "Revisa los campos del formulario.", fields)))
        return repository.signIn(normalizedEmail, password)
    }
}

class GetCurrentProfile(private val repository: IdentityRepository) {
    suspend operator fun invoke(): IdentityResult<Profile> = repository.currentProfile()
}

class SignInWithGoogle(private val repository: IdentityRepository) {
    suspend operator fun invoke(): IdentityResult<Profile> = repository.signInWithGoogle()
}

class SignOut(private val repository: IdentityRepository) {
    operator fun invoke() = repository.signOut()
}

class RestoreSession(private val repository: IdentityRepository) {
    suspend operator fun invoke() = repository.restoreSession()
}

class ObserveSessionInvalidations(private val repository: IdentityRepository) {
    operator fun invoke(): Flow<Unit> = repository.sessionInvalidations()
}

class ObserveSessionWindow(private val repository: IdentityRepository) {
    operator fun invoke() = repository.sessionWindow()
}
class RecordSessionActivity(private val repository: IdentityRepository) {
    suspend operator fun invoke() = repository.recordActivity()
}
class EndSession(private val repository: IdentityRepository) {
    suspend operator fun invoke() = repository.endSession()
}
