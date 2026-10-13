@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package pe.bpm.features.identity.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

@JsFun("(resolve, reject) => { const bridge = window.bpmGoogleAuth; if (!bridge) { reject('auth/unavailable'); return; } bridge.signIn().then(resolve).catch(error => reject(error.code || 'auth/unavailable')); }")
private external fun startGoogle(resolve: (String) -> Unit, reject: (String) -> Unit)

@JsFun("() => { window.bpmGoogleAuth?.clear(); }")
private external fun clearGoogle()

actual fun platformGoogleAuthProvider(): GoogleAuthProvider = BrowserGoogleAuthProvider()

private class BrowserGoogleAuthProvider : GoogleAuthProvider {
    override val supported = true
    override suspend fun signIn(): GoogleAuthResult = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { clearGoogle() }
        startGoogle(resolve = { raw ->
            if (continuation.isActive) {
                val result = try {
                    val credential = Json.decodeFromString<GoogleCredentialDto>(raw)
                    GoogleAuthResult.Success(GoogleCredential(credential.idToken, credential.uid, credential.displayName))
                } catch (_: Exception) { GoogleAuthResult.Failure("INVALID_RESPONSE") }
                continuation.resume(result)
            }
        }, reject = { code ->
            if (continuation.isActive) continuation.resume(GoogleAuthResult.Failure(when (code) {
                "auth/popup-closed-by-user", "auth/cancelled" -> "GOOGLE_CANCELLED"
                "auth/cancelled-popup-request", "auth/in-progress" -> "GOOGLE_IN_PROGRESS"
                "auth/popup-blocked" -> "GOOGLE_POPUP_BLOCKED"
                "auth/account-exists-with-different-credential" -> "ACCOUNT_CONFLICT"
                "auth/unauthorized-domain" -> "AUTH_DOMAIN_NOT_AUTHORIZED"
                "auth/operation-not-allowed", "auth/not-configured", "auth/invalid-api-key" -> "AUTH_NOT_CONFIGURED"
                "auth/not-ready" -> "GOOGLE_NOT_READY"
                "auth/network-request-failed" -> "GOOGLE_NETWORK_ERROR"
                "auth/too-many-requests" -> "RATE_LIMITED"
                "auth/user-disabled" -> "INVALID_CREDENTIALS"
                else -> "GOOGLE_UNAVAILABLE"
            }))
        })
    }
    override fun clear() = clearGoogle()
}

@Serializable private class GoogleCredentialDto(val idToken: String, val uid: String, val displayName: String = "Usuario BPM")
