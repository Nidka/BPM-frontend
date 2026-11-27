package pe.bpm.features.support.domain

/** Contacto de soporte de la plataforma (Ayuda). [whatsapp] son solo dígitos con código de país, o null si no hay. */
data class SupportContact(val email: String, val whatsapp: String? = null)

enum class SupportFailure { SESSION, DENIED, INVALID, UNAVAILABLE, CONNECTION, INVALID_RESPONSE }

sealed interface SupportResult<out T> {
    data class Success<T>(val value: T) : SupportResult<T>
    data class Failure(val reason: SupportFailure, val fields: Map<String, String> = emptyMap()) : SupportResult<Nothing>
}

interface SupportRepository {
    suspend fun contact(): SupportResult<SupportContact>
    /** Solo SUPER_ADMIN; el servidor lo vuelve a validar. */
    suspend fun save(email: String, whatsapp: String?): SupportResult<SupportContact>
}

/** Reglas y enlaces del contacto de soporte. Funciones puras: no abren aplicaciones. */
object SupportRules {
    const val DEFAULT_EMAIL = "proyectobpmupn@gmail.com"
    const val SUBJECT = "Soporte BPM"
    private val emailPattern = Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")

    /** Mensaje de error del correo, o null si es válido (mismas reglas que el servidor, en corto). */
    fun emailError(email: String): String? {
        val value = email.trim()
        return if (value.length in 3..254 && emailPattern.matches(value)) null else "Escribe un correo válido, por ejemplo soporte@empresa.com."
    }

    /** Deja solo los dígitos del número (sin +, espacios ni guiones). */
    fun whatsappDigits(raw: String): String = raw.filter { it.isDigit() }

    /** Mensaje de error del WhatsApp (opcional), o null si está vacío o es válido. */
    fun whatsappError(raw: String): String? {
        if (raw.isBlank()) return null
        val cleaned = raw.trim().filterNot { it in " -+()" }
        return if (cleaned.length in 8..15 && cleaned.all { it.isDigit() }) null else "Escribe el número con código de país, por ejemplo 51987654321."
    }

    /**
     * Texto que se prellena al escribir a soporte: rol, organización, versión y plataforma, para que soporte entienda el caso.
     * Nunca incluye tokens, contraseñas ni identificadores internos.
     */
    fun messageBody(role: String, organization: String?, version: String, platform: String): String = buildString {
        appendLine("Hola, necesito ayuda con la app BPM.")
        appendLine()
        appendLine("¿Qué pasó?: ")
        appendLine()
        appendLine("Rol: $role")
        organization?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("Organización: $it") }
        append("App: versión $version ($platform)")
    }

    fun mailtoUrl(email: String, body: String): String = "mailto:${email.trim()}?subject=${percentEncode(SUBJECT)}&body=${percentEncode(body)}"

    fun whatsappUrl(number: String, body: String): String = "https://wa.me/${whatsappDigits(number)}?text=${percentEncode(body)}"

    /** Codificación por porcentaje UTF-8 (RFC 3986): solo los caracteres no reservados quedan sin codificar. */
    fun percentEncode(text: String): String = buildString {
        val hex = "0123456789ABCDEF"
        for (byte in text.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (value < 0x80 && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~")) append(char)
            else append('%').append(hex[value shr 4]).append(hex[value and 0x0F])
        }
    }
}
