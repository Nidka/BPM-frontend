package pe.bpm.features.accounts.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import pe.bpm.core.domain.InputRules

/** Reglas puras del contrato de cuentas; el backend sigue siendo la autoridad. */
object AccountsRules {
    val legalName = InputRules.text(2, 200)
    val tradeName = InputRules.text(0, 200)
    val taxId = InputRules.text(0, 20)
    val contactName = InputRules.personName(160, required = false)
    val contactEmail = InputRules.email(required = false)
    val contactPhone = InputRules.phone()
    val locationName = InputRules.text(2, 160)
    val address = InputRules.text(3, 300)
    val district = InputRules.text(0, 120)
    val email = InputRules.email()
    const val MAX_LOCATIONS = 100

    /** Espera mínima entre dos envíos de una misma invitación. */
    val resendWait: Duration = 1.minutes
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")
    private val zonePattern = Regex("^[A-Za-z][A-Za-z0-9_+-]*(?:/[A-Za-z0-9_+-]+){0,2}$")

    fun organization(input: AccountOrganization): Map<String, String> = buildMap {
        legalName.error(input.legalName)?.let { put("legalName", it) }
        tradeName.error(input.tradeName)?.let { put("tradeName", it) }
        taxId.error(input.taxId)?.let { put("taxId", it) }
        contactName.error(input.contactName)?.let { put("contactName", it) }
        contactEmail.error(input.contactEmail)?.let { put("contactEmail", it) }
        contactPhone.error(input.contactPhone)?.let { put("contactPhone", it) }
    }

    fun location(input: AccountLocation): Map<String, String> = buildMap {
        locationName.error(input.name)?.let { put("name", it) }
        address.error(input.address)?.let { put("address", it) }
        district.error(input.district)?.let { put("district", it) }
        contactPhone.error(input.contactPhone)?.let { put("contactPhone", it) }
        if (input.timezone.length > 100 || !zonePattern.matches(input.timezone.trim())) put("timezone", "Escribe una zona horaria válida, por ejemplo America/Lima.")
    }

    /** CHEF y WORKER requieren al menos una sede; todos admiten un máximo de 100 sin duplicados. */
    fun locationsFor(role: MemberRole, locationIds: Collection<String>): String? = when {
        locationIds.size > MAX_LOCATIONS || locationIds.toSet().size != locationIds.size -> "Selecciona hasta $MAX_LOCATIONS sedes distintas."
        role.needsLocations && locationIds.isEmpty() -> "Selecciona al menos una sede."
        else -> null
    }

    fun invitation(email: String, role: MemberRole, locationIds: Collection<String>): Map<String, String> = buildMap {
        this@AccountsRules.email.error(email)?.let { put("email", it) }
        locationsFor(role, locationIds)?.let { put("locationIds", it) }
    }

    fun transfer(actorIsMember: Boolean, actorRole: MemberRole?, actorLocationIds: Collection<String>): Map<String, String> = buildMap {
        if (!actorIsMember) return@buildMap
        if (actorRole == null || actorRole == MemberRole.ADMIN) put("actorRole", "Elige el rol que tendrás después de transferir.")
        else locationsFor(actorRole, actorLocationIds)?.let { put("actorLocationIds", it) }
    }

    /** El token de invitación tiene 43 caracteres base64url; se normaliza el texto pegado. */
    fun invitationToken(raw: String): String? = raw.trim().takeIf(tokenPattern::matches)

    /**
     * Enlace de aceptación devuelto al crear o reenviar: https (http solo en localhost para desarrollo), sin
     * espacios ni credenciales y con un único `token` de 43 caracteres base64url.
     */
    fun acceptLink(url: String): Boolean {
        if (url.length > 2048 || url.any { it <= ' ' || it == '' } || '#' in url) return false
        val secure = url.startsWith("https://")
        val local = listOf("http://localhost", "http://127.0.0.1").any { url.startsWith(it) }
        if (!secure && !local) return false
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?')
        if ('@' in authority) return false
        val query = url.substringAfter('?', "")
        val params = query.split('&')
        return params.size == 1 && params[0].startsWith("token=") && invitationToken(params[0].removePrefix("token=")) != null
    }

    /** Extrae el token de un enlace pegado (`...?token=...`) o del propio token; nulo si no es válido. */
    fun tokenFromPaste(raw: String): String? {
        val text = raw.trim()
        invitationToken(text)?.let { return it }
        val query = text.substringAfter('?', "").substringBefore('#')
        val value = query.split('&').firstOrNull { it.startsWith("token=") }?.removePrefix("token=")
        return value?.let(::invitationToken)
    }

    /** Segundos que faltan para poder reenviar; 0 si ya se puede (o si la fecha no es legible). */
    fun resendWaitSeconds(lastSentAt: String, now: Instant): Long {
        val sent = runCatching { Instant.parse(lastSentAt) }.getOrNull() ?: return 0
        val remaining = sent + resendWait - now
        return if (remaining.isPositive()) (remaining.inWholeMilliseconds + 999) / 1000 else 0
    }

    fun canResend(status: InvitationStatus) = status == InvitationStatus.PENDING || status == InvitationStatus.EXPIRED
    fun canRevoke(status: InvitationStatus) = status == InvitationStatus.PENDING || status == InvitationStatus.EXPIRED
}
