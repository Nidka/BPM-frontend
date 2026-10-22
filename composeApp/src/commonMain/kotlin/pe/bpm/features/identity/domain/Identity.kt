package pe.bpm.features.identity.domain

import kotlinx.coroutines.flow.Flow

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
    val diagnostic: IdentityDiagnostic? = null,
)

sealed interface IdentityResult<out T> {
    data class Success<T>(val value: T) : IdentityResult<T>
    data class Failure(val errors: List<IdentityError>) : IdentityResult<Nothing>
}

interface AccountAuthentication {
    suspend fun signIn(email: String, password: String): IdentityResult<Profile>
    suspend fun signInWithGoogle(): IdentityResult<Profile>
}

interface ProfileAccess {
    suspend fun currentProfile(): IdentityResult<Profile>
}

interface AccountRegistration {
    suspend fun register(name: String, email: String, password: String): IdentityResult<Unit>
    suspend fun resetPassword(email: String): IdentityResult<Unit>
    suspend fun resendVerification(): IdentityResult<Unit>
    suspend fun confirmEmailVerification(): IdentityResult<Profile>
}

interface SessionLifecycle {
    suspend fun restoreSession(): IdentityResult<Profile>
    fun sessionInvalidations(): Flow<IdentityError>
    fun sessionWindow(): Flow<SessionWindow?>
    suspend fun recordActivity(): IdentityResult<Unit>
    suspend fun endSession(): IdentityResult<Unit>
    fun signOut()
}

/** El adaptador implementa las capacidades; cada caso de uso depende solo de la que necesita. */
interface IdentityRepository : AccountAuthentication, AccountRegistration, ProfileAccess, SessionLifecycle

class Register(private val repository: AccountRegistration) {
    suspend operator fun invoke(firstName: String, lastName: String, email: String, password: String, confirmation: String): IdentityResult<Unit> {
        val names = firstName.trim().replace(Regex(" +"), " ")
        val surnames = lastName.trim().replace(Regex(" +"), " ")
        val displayName = "$names $surnames"
        val fields = buildMap {
            IdentityInputRules.personName.error(firstName)?.let { put("firstName", it) }
            IdentityInputRules.personName.error(lastName)?.let { put("lastName", it) }
            if (displayName.count { !it.isLowSurrogate() } > 100) put("lastName", "Tus nombres y apellidos juntos deben tener como máximo 100 caracteres.")
            IdentityInputRules.email.error(email)?.let { put("email", it) }
            IdentityInputRules.registrationPassword.error(password)?.let { put("password", it) }
            if (password != confirmation) put("confirmPassword", "Las contraseñas no coinciden.")
        }
        if (fields.isNotEmpty()) return IdentityResult.Failure(listOf(IdentityError("VALIDATION_ERROR", "Revisa los campos del formulario.", fields)))
        return repository.register(displayName, email.trim(), password)
    }
}

class ResetPassword(private val repository: AccountRegistration) {
    suspend operator fun invoke(email: String): IdentityResult<Unit> {
        IdentityInputRules.email.error(email)?.let { return IdentityResult.Failure(listOf(IdentityError("VALIDATION_ERROR", "Revisa el correo.", mapOf("email" to it)))) }
        return repository.resetPassword(email.trim())
    }
}

class ResendVerification(private val repository: AccountRegistration) {
    suspend operator fun invoke(): IdentityResult<Unit> = repository.resendVerification()
}

class ConfirmEmailVerification(private val repository: AccountRegistration) {
    suspend operator fun invoke(): IdentityResult<Profile> = repository.confirmEmailVerification()
}

class SignIn(private val repository: AccountAuthentication) {
    suspend operator fun invoke(email: String, password: String): IdentityResult<Profile> {
        val normalizedEmail = email.trim()
        val fields = buildMap {
            IdentityInputRules.email.error(email)?.let { put("email", it) }
            IdentityInputRules.loginPassword.error(password)?.let { put("password", it) }
        }
        if (fields.isNotEmpty()) return IdentityResult.Failure(listOf(
            IdentityError("VALIDATION_ERROR", "Revisa los campos del formulario.", fields)))
        return repository.signIn(normalizedEmail, password)
    }
}

class GetCurrentProfile(private val repository: ProfileAccess) {
    suspend operator fun invoke(): IdentityResult<Profile> = repository.currentProfile()
}

class SignInWithGoogle(private val repository: AccountAuthentication) {
    suspend operator fun invoke(): IdentityResult<Profile> = repository.signInWithGoogle()
}

class SignOut(private val repository: SessionLifecycle) {
    operator fun invoke() = repository.signOut()
}

class RestoreSession(private val repository: SessionLifecycle) {
    suspend operator fun invoke() = repository.restoreSession()
}

class ObserveSessionInvalidations(private val repository: SessionLifecycle) {
    operator fun invoke(): Flow<IdentityError> = repository.sessionInvalidations()
}

class ObserveSessionWindow(private val repository: SessionLifecycle) {
    operator fun invoke() = repository.sessionWindow()
}
class RecordSessionActivity(private val repository: SessionLifecycle) {
    suspend operator fun invoke() = repository.recordActivity()
}
class EndSession(private val repository: SessionLifecycle) {
    suspend operator fun invoke() = repository.endSession()
}
