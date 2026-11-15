package pe.bpm.features.accounts.domain

/**
 * Textos y enlaces para compartir una invitación. Son funciones puras: no abren aplicaciones ni
 * guardan nada; la pantalla entrega el resultado a la plataforma.
 */
object InvitationSharing {
    private const val WHATSAPP = "https://wa.me/?text="
    private val hex = "0123456789ABCDEF"

    /** Aviso visible junto al enlace o código. */
    fun notice(email: String): String =
        "El enlace solo funciona para la cuenta con el correo $email, vale 7 días y se usa una vez"

    /** Mensaje breve en español para enviar a la persona invitada. */
    fun message(organizationName: String, email: String, share: InvitationShare): String {
        val company = organizationName.trim().ifBlank { "nuestra empresa" }
        return when (share) {
            is InvitationShare.Link -> "Te invito a unirte a $company en BPM. Crea tu cuenta con $email y abre: ${share.url}"
            is InvitationShare.Code -> "Te invito a unirte a $company en BPM. Crea tu cuenta con $email y, en Mi cuenta, " +
                "elige «Aceptar una invitación» y pega este código: ${share.token}"
        }
    }

    /** URL de WhatsApp con el texto codificado en porcentaje (UTF-8, solo caracteres no reservados sin codificar). */
    fun whatsappUrl(text: String): String = WHATSAPP + percentEncode(text)

    fun percentEncode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val value = byte.toInt() and 0xFF
            val char = value.toChar()
            if (value < 0x80 && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~")) append(char)
            else append('%').append(hex[value shr 4]).append(hex[value and 0x0F])
        }
    }
}
