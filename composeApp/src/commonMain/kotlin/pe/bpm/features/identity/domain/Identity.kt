package pe.bpm.features.identity.domain

data class Profile(
    val id: String,
    val email: String,
    val displayName: String,
    val organizations: List<Organization>,
)

data class Organization(val id: String, val name: String, val role: String)

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
    suspend fun currentProfile(): IdentityResult<Profile>
    fun signOut()
}

class SignIn(private val repository: IdentityRepository) {
    suspend operator fun invoke(email: String, password: String): IdentityResult<Profile> {
        val normalizedEmail = email.trim()
        val fields = buildMap {
            if (normalizedEmail.length > 320 || !normalizedEmail.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) {
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

class SignOut(private val repository: IdentityRepository) {
    operator fun invoke() = repository.signOut()
}
