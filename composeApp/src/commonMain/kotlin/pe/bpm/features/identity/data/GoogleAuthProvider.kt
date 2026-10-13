package pe.bpm.features.identity.data

// Puerto técnico del adaptador: nunca exponer tokens en el estado de presentación.
interface GoogleAuthProvider {
    val supported: Boolean
    suspend fun signIn(): GoogleAuthResult
    fun clear()
}

class GoogleCredential(val idToken: String, val uid: String, val displayName: String = "Usuario BPM")

sealed interface GoogleAuthResult {
    class Success(val credential: GoogleCredential) : GoogleAuthResult
    data class Failure(val code: String) : GoogleAuthResult
}

expect fun platformGoogleAuthProvider(): GoogleAuthProvider

class UnsupportedGoogleAuthProvider : GoogleAuthProvider {
    override val supported = false
    override suspend fun signIn() = GoogleAuthResult.Failure("GOOGLE_NOT_SUPPORTED")
    override fun clear() = Unit
}
