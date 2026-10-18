package pe.bpm.features.identity.domain

/** Etapas públicas del recorrido; no contienen datos de la cuenta ni detalles del proveedor. */
enum class IdentityStage {
    PUBLIC_CONFIGURATION, SIGN_IN, REGISTRATION, PROFILE_UPDATE, VERIFICATION_EMAIL,
    PASSWORD_RECOVERY, TOKEN_RENEWAL, PROFILE_READ, PROFILE_ENROLLMENT, SESSION,
    SESSION_ACTIVITY, SIGN_OUT,
}

data class IdentityDiagnostic(val stage: IdentityStage, val httpStatus: Int? = null, val reason: String)

val IdentityError.deniesSession: Boolean get() = code in setOf(
    "UNAUTHENTICATED", "SESSION_EXPIRED", "USER_INACTIVE", "USER_NOT_REGISTERED", "ACCESS_DENIED", "EMAIL_NOT_VERIFIED",
)
val IdentityError.hasPendingRegistration: Boolean get() = code in setOf("REGISTRATION_INCOMPLETE", "REGISTRATION_RATE_LIMITED")
val IdentityError.requiresDeliveryWait: Boolean get() = code in setOf("RATE_LIMITED", "REGISTRATION_RATE_LIMITED")
val IdentityError.requiresNewAuthentication: Boolean get() = deniesSession && code != "EMAIL_NOT_VERIFIED"
val IdentityError.permitsRestoreRetry: Boolean get() = !deniesSession && code != "INVALID_RESPONSE"
