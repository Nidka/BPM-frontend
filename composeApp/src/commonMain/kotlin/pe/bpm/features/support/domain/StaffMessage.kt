package pe.bpm.features.support.domain

/** «Avisar a mi jefe»: opciones rápidas en lenguaje simple. */
enum class StaffMessageKind(val label: String) { SUPPLY("Falta insumo"), EQUIPMENT("Equipo malogrado"), HELP("Necesito ayuda"), OTHER("Otro") }

/** Contacto de WhatsApp del jefe de la sede (o del administrador); null si nadie dejó su número. */
data class SupervisorContact(val name: String, val role: String, val whatsapp: String)

interface TeamRepository {
    suspend fun sendStaffMessage(org: String, id: String, locationId: String, kind: StaffMessageKind, text: String, key: String): SupportResult<Unit>
    suspend fun supervisorContact(org: String, locationId: String): SupportResult<SupervisorContact?>
    suspend fun saveMyWhatsapp(raw: String?): SupportResult<String?>
}

object StaffMessageRules {
    const val TEXT_MAX = 500

    /** Texto que se prellena en WhatsApp para el jefe. */
    fun whatsappText(contactName: String, myName: String, site: String, kind: StaffMessageKind?, text: String): String = buildString {
        append("Hola")
        contactName.trim().split(" ").firstOrNull()?.takeIf { it.isNotBlank() }?.let { append(" ").append(it) }
        append(", soy ").append(myName.trim().ifBlank { "del equipo" })
        if (site.isNotBlank()) append(" (").append(site.trim()).append(")")
        append(".")
        if (kind != null && kind != StaffMessageKind.OTHER) append(" ").append(kind.label).append(".")
        if (text.isNotBlank()) append(" ").append(text.trim())
    }

    /** Valida el WhatsApp propio: vacío (sin número) o un celular (9 dígitos) o con código de país (8 a 15 dígitos). */
    fun myWhatsappError(raw: String): String? {
        val digits = raw.trim().removePrefix("+").filterNot { it in " -()." }
        if (digits.isEmpty()) return null
        return if (digits.all { it.isDigit() } && digits.length in 8..15 && digits[0] != '0') null else "Escribe tu celular, por ejemplo 987654321."
    }
}
