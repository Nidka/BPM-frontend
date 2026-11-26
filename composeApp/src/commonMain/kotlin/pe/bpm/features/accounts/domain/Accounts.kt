package pe.bpm.features.accounts.domain

/** Tipo de establecimiento (RF-49). El código viaja a la API; la etiqueta se muestra a la persona. */
enum class BusinessType(val label: String) {
    RESTAURANT("Restaurante"), CAFETERIA("Cafetín"), CATERING("Catering"), OTHER_FOOD_SERVICE("Otro servicio de alimentos");

    companion object {
        /** `RELATED_SERVICE` es el valor anterior; se interpreta como «Otro servicio de alimentos». */
        fun fromCode(code: String): BusinessType? = if (code == "RELATED_SERVICE") OTHER_FOOD_SERVICE else entries.firstOrNull { it.name == code }
    }
}

/** Estado de una fila administrable (miembro o sede). */
enum class RecordStatus { ACTIVE, INACTIVE }

enum class MemberRole(val label: String) {
    ADMIN("Administrador"), CHEF("Jefe de cocina"), WORKER("Personal");

    /** Solo CHEF y WORKER necesitan sedes asignadas; ADMIN ve todas. */
    val needsLocations get() = this != ADMIN
}

/** Organización editable; [rowVersion] es el valor leído que se reenvía para detectar conflictos. */
data class AccountOrganization(
    val id: String, val legalName: String, val tradeName: String = "", val taxId: String = "",
    val businessType: BusinessType, val contactName: String = "", val contactPhone: String = "",
    val contactEmail: String = "", val rowVersion: Long,
)

data class AccountLocation(
    val id: String, val name: String, val address: String, val district: String = "", val contactPhone: String = "",
    val timezone: String, val status: RecordStatus, val rowVersion: Long,
)

data class AccountMember(
    val id: String, val name: String, val email: String, val role: MemberRole, val locationIds: List<String>,
    val status: RecordStatus, val rowVersion: Long,
)

/** `EXPIRED` es el estado efectivo de una invitación pendiente vencida. */
enum class InvitationStatus(val label: String) {
    PENDING("Pendiente"), ACCEPTED("Aceptada"), REVOKED("Revocada"), EXPIRED("Vencida")
}

data class Invitation(
    val id: String, val email: String, val role: MemberRole, val locationIds: List<String>, val status: InvitationStatus,
    val expiresAt: String, val lastSentAt: String, val createdAt: String, val sendCount: Int, val rowVersion: Long,
)

/**
 * Forma de compartir una invitación recién creada o reenviada. Existe solo en memoria: no se guarda
 * en disco, no aparece en la lista y, si se pierde, se reenvía la invitación para obtener otra.
 */
sealed interface InvitationShare {
    /** Texto que se copia o se comparte: el enlace o el código. */
    val value: String

    /** Enlace completo (`.../cuenta/invitacion?token=...`) devuelto por el servidor. */
    data class Link(val url: String) : InvitationShare { override val value get() = url }

    /** Código de 43 caracteres cuando el servidor no tiene URL de aceptación configurada. */
    data class Code(val token: String) : InvitationShare { override val value get() = token }
}

/** Invitación creada o reenviada junto con su enlace o código; la lista de invitaciones nunca los trae. */
data class SharedInvitation(val invitation: Invitation, val share: InvitationShare)

data class AcceptedInvitation(val organizationId: String, val role: MemberRole, val locationIds: List<String>)

enum class DevicePlatform(val label: String) { ANDROID("Android"), IOS("iPhone o iPad"), WEB("Navegador web") }

/** Dispositivo propio; nunca incluye el token de notificaciones ni la instalación. */
data class AccountDevice(
    val id: String, val organizationId: String, val organizationName: String, val platform: DevicePlatform,
    val status: RecordStatus, val lastSeenAt: String?, val createdAt: String,
)

/** Resultado de transferir la administración. [formerAdmin] es nulo cuando quien transfiere es SUPER_ADMIN no miembro. */
data class AdminTransfer(val newAdmin: AccountMember, val formerAdmin: AccountMember?)

/** Motivos públicos y sanitizados; nunca llevan mensajes del servidor. */
enum class AccountFailure {
    SESSION, DENIED, NOT_FOUND, VERSION_CONFLICT, LAST_ADMIN, MEMBER_ALREADY_EXISTS, MEMBER_INACTIVE, MEMBER_NOT_AVAILABLE,
    ALREADY_PENDING, INVITATION_NOT_FOUND, INVITATION_EXPIRED, INVITATION_USED, INVITATION_REVOKED, INVITATION_NOT_PENDING,
    EMAIL_MISMATCH, RESEND_TOO_SOON, CONFLICT, VALIDATION, INVALID_REQUEST, INVALID_PAGINATION, RATE_LIMITED,
    INVALID_RESPONSE, CONNECTION, UNAVAILABLE,
}

sealed interface AccountsResult<out T> {
    data class Success<T>(val value: T) : AccountsResult<T>
    data class Failure(val reason: AccountFailure, val fields: Map<String, String> = emptyMap()) : AccountsResult<Nothing>
}

/** Lo que se sabe de una invitación antes de iniciar sesión: empresa, rol y correo al que llegó. */
data class InvitationPreview(val organizationName: String, val role: MemberRole, val email: String)
