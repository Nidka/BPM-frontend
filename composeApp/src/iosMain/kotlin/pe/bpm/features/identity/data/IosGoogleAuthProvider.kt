package pe.bpm.features.identity.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** Puerto del SDK Swift: el host aporta la presentación y nunca decide permisos de BPM. */
interface IosGoogleSignInDriver {
    val configured: Boolean
    fun start(onResult: (GoogleAuthResult) -> Unit)
    fun clear()
}

/** Convierte el callback nativo a una operación cancelable para el repositorio compartido. */
class IosGoogleAuthProvider(private val driver: IosGoogleSignInDriver) : GoogleAuthProvider {
    override val supported: Boolean get() = driver.configured

    override suspend fun signIn(): GoogleAuthResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { driver.clear() }
            try {
                driver.start { result -> if (continuation.isActive) continuation.resume(result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resume(GoogleAuthResult.Failure("GOOGLE_UNAVAILABLE"))
            }
        }
    }

    override fun clear() = driver.clear()
}
