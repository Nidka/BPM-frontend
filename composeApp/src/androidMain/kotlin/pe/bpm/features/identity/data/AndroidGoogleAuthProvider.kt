package pe.bpm.features.identity.data

import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.util.Base64
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.*
import java.security.SecureRandom

/** Abre el selector oficial; Firebase y BPM confirman la identidad fuera de este adaptador. */
class AndroidGoogleAuthProvider(context: Context, private val webClientId: String) : GoogleAuthProvider, AutoCloseable {
    private val application = context.applicationContext
    private val foreground = MutableContextWrapper(application)
    private val manager = CredentialManager.create(application)
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Deferred<GoogleAuthResult>? = null
    private var cleanup: Job? = null
    override val supported = true

    fun attach(activity: Activity) { foreground.baseContext = activity }
    fun detach(activity: Activity) {
        if (foreground.baseContext === activity) {
            pending?.cancel()
            foreground.baseContext = application
        }
    }

    override suspend fun signIn(): GoogleAuthResult = withContext(Dispatchers.Main.immediate) {
        if (pending != null) return@withContext GoogleAuthResult.Failure("GOOGLE_IN_PROGRESS")
        coroutineScope {
            val operation = async(start = CoroutineStart.LAZY) {
                try {
                    if (!webClientId.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com")))
                        return@async GoogleAuthResult.Failure("GOOGLE_NOT_CONFIGURED")
                    cleanup?.join()
                    currentCoroutineContext().ensureActive()
                    val activity = foreground.baseContext as? Activity
                    if (activity == null || activity.isFinishing || activity.isDestroyed)
                        return@async GoogleAuthResult.Failure("GOOGLE_NOT_READY")
                    val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                    val nonce = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
                    val option = GetSignInWithGoogleOption.Builder(webClientId).setNonce(nonce).build()
                    val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                    val credential = manager.getCredential(foreground, request).credential
                    if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                        GoogleAuthResult.Failure("INVALID_RESPONSE")
                    else GoogleAuthResult.Federated(GoogleIdTokenCredential.createFrom(credential.data).idToken, nonce)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: GetCredentialCancellationException) { GoogleAuthResult.Failure("GOOGLE_CANCELLED") }
                catch (_: NoCredentialException) { GoogleAuthResult.Failure("GOOGLE_NO_CREDENTIAL") }
                catch (_: GetCredentialProviderConfigurationException) { GoogleAuthResult.Failure("GOOGLE_NO_CREDENTIAL") }
                catch (_: GetCredentialException) { GoogleAuthResult.Failure("GOOGLE_UNAVAILABLE") }
                catch (_: Exception) { GoogleAuthResult.Failure("INVALID_RESPONSE") }
            }
            pending = operation
            try { operation.await() }
            catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                GoogleAuthResult.Failure("GOOGLE_CANCELLED")
            } finally { if (pending === operation) pending = null }
        }
    }

    override fun clear() {
        pending?.cancel()
        val previous = cleanup
        cleanup = cleanupScope.launch {
            previous?.join()
            try { manager.clearCredentialState(ClearCredentialStateRequest()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* El cierre local de BPM no depende de Google Play. */ }
        }
    }
    override fun close() {
        pending?.cancel()
        cleanupScope.cancel()
        foreground.baseContext = application
    }
}
