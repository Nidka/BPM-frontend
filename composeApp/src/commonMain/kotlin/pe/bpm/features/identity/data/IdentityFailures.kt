package pe.bpm.features.identity.data

import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.identity.domain.*

/** Conserva únicamente etapa, estado HTTP y motivo sanitizado; nunca cuerpos, URL ni credenciales. */
internal class IdentityDataException(val failure: IdentityResult.Failure) : Exception()

internal suspend fun <T> identityOperation(stage: IdentityStage = IdentityStage.SESSION, block: suspend () -> IdentityResult<T>): IdentityResult<T> = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: IdentityDataException) {
    error.failure
} catch (error: SessionAccessException) {
    identityFailure(if (error.expired) "UNAUTHENTICATED" else "AUTH_UNAVAILABLE", IdentityDiagnostic(stage, reason = "SESSION_ACCESS"))
} catch (_: HttpRequestTimeoutException) {
    identityFailure("TIMEOUT", IdentityDiagnostic(stage, reason = "TIMEOUT"))
} catch (_: SerializationException) {
    identityFailure("INVALID_RESPONSE", IdentityDiagnostic(stage, reason = "RESPONSE_FORMAT"))
} catch (_: JsonConvertException) {
    identityFailure("INVALID_RESPONSE", IdentityDiagnostic(stage, reason = "RESPONSE_FORMAT"))
} catch (_: Throwable) {
    // Wasm también puede representar fallos de fetch como Error; la cancelación se propaga antes.
    identityFailure("NETWORK_ERROR", IdentityDiagnostic(stage, reason = "CONNECTION"))
}

internal suspend fun <T> identityDataRequest(stage: IdentityStage, block: suspend () -> T): T =
    when (val result = identityOperation(stage) { IdentityResult.Success(block()) }) {
        is IdentityResult.Success -> result.value
        is IdentityResult.Failure -> throw IdentityDataException(result)
    }

internal fun identityFailure(code: String, diagnostic: IdentityDiagnostic? = null): IdentityResult.Failure {
    val message = when (code) {
        "INVALID_CREDENTIALS" -> "El correo o la contraseña no son correctos."
        "AUTH_NOT_CONFIGURED" -> "El inicio de sesión aún no está configurado. Contacta al administrador."
        "AUTH_DOMAIN_NOT_AUTHORIZED" -> "Este dominio no está autorizado en Firebase. Contacta al administrador."
        "EMAIL_NOT_VERIFIED" -> "Tu correo aún no está confirmado. Abre el enlace que recibiste y después pulsa «Ya confirmé mi correo»."
        "REGISTRATION_REJECTED" -> "No se pudo completar el registro con este correo. Si ya tienes una cuenta, inicia sesión; si no recuerdas la contraseña, puedes recuperarla."
        "REGISTRATION_UNAVAILABLE" -> "No pudimos completar el registro en este momento. Inténtalo más tarde."
        "EMAIL_DELIVERY_UNAVAILABLE" -> "No podemos enviar más correos por ahora. Inténtalo más tarde."
        "INVALID_EMAIL" -> "Revisa el correo electrónico e inténtalo de nuevo."
        "REGISTRATION_INCOMPLETE" -> "Tu cuenta se creó, pero no pudimos completar la verificación. Reenvía el enlace o inicia sesión para continuar; no necesitas registrarte otra vez."
        "REGISTRATION_CONFLICT" -> "Usa el método original de tu cuenta o contacta al administrador."
        "PASSWORD_POLICY" -> "Elige una contraseña más segura: combina letras, números y símbolos."
        "INVALID_PROFILE_NAME" -> "Revisa el nombre de tu perfil. Debe tener entre 2 y 100 caracteres."
        "USER_NOT_REGISTERED" -> "Tu cuenta no está habilitada en BPM. Contacta al administrador."
        "USER_INACTIVE" -> "Tu cuenta se encuentra inactiva. Contacta al administrador."
        "ACCESS_DENIED" -> "No tienes permiso para acceder. Contacta al administrador."
        "UNAUTHENTICATED" -> "Tu sesión terminó. Inicia sesión nuevamente."
        "SESSION_EXPIRED" -> "Tu sesión venció por tiempo o inactividad. Inicia sesión nuevamente."
        "RATE_LIMITED" -> "Demasiados intentos. Espera un momento antes de volver a intentar."
        "TIMEOUT" -> "La conexión tardó demasiado. Inténtalo nuevamente."
        "NETWORK_ERROR" -> "No pudimos conectar. Revisa tu conexión a internet."
        "INVALID_RESPONSE" -> "No pudimos verificar tu sesión. Inténtalo nuevamente."
        "GOOGLE_CANCELLED" -> "Se canceló el acceso con Google."
        "GOOGLE_IN_PROGRESS" -> "Ya hay un acceso con Google en curso. Completa o cierra la ventana antes de volver a intentar."
        "GOOGLE_NETWORK_ERROR" -> "Google no pudo completar la conexión. Revisa tu red y vuelve a intentar."
        "GOOGLE_UNAVAILABLE" -> "Google no pudo completar el acceso. Vuelve a intentarlo; si continúa, contacta al administrador."
        "GOOGLE_POPUP_BLOCKED" -> "Permite la ventana emergente para iniciar sesión con Google."
        "GOOGLE_NOT_READY" -> "Google se está preparando. Vuelve a intentarlo en un momento."
        "GOOGLE_NOT_SUPPORTED" -> "El acceso con Google todavía no está habilitado en esta plataforma."
        "ACCOUNT_CONFLICT" -> "Ingresa con el método original de tu cuenta. Su vinculación con Google requiere verificación."
        else -> "El servicio de autenticación no está disponible. Inténtalo nuevamente."
    }
    val fields = when (code) {
        "PASSWORD_POLICY" -> mapOf("password" to message)
        "INVALID_PROFILE_NAME" -> mapOf("displayName" to message)
        "INVALID_EMAIL" -> mapOf("email" to message)
        else -> emptyMap()
    }
    return IdentityResult.Failure(listOf(IdentityError(code, message, fields, diagnostic)))
}

