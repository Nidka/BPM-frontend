package pe.bpm.core.domain

import kotlin.math.abs
import kotlin.math.round

/** Formatos puros compartidos. Cada funcionalidad declara obligatoriedad y límites del contrato. */
fun interface InputRule { fun error(value: String): String? }

object InputRules {
    fun text(min: Int, max: Int, maxBytes: Int? = null) = InputRule { value ->
        val text = value.trim()
        when {
            text.isEmpty() && min > 0 -> "Completa este campo."
            text.count { !it.isLowSurrogate() } < min || value.count { !it.isLowSurrogate() } > max -> "Usa entre $min y $max caracteres."
            value.any { it.isISOControl() } -> "Escribe el texto sin saltos de línea ni caracteres de control."
            maxBytes != null && value.encodeToByteArray().size > maxBytes -> "El texto es demasiado largo. Usa menos caracteres."
            else -> null
        }
    }

    fun personName(max: Int, required: Boolean = true) = InputRule { value ->
        text(if (required) 1 else 0, max).error(value) ?: when {
            value.isBlank() -> null
            !personName.matches(value.trim()) -> "Usa letras, espacios, apóstrofos o guiones; sin números."
            else -> null
        }
    }

    fun email(required: Boolean = true) = InputRule { value ->
        val email = value.trim()
        when {
            value.any { it.isISOControl() } -> "Ingresa el correo sin saltos de línea ni caracteres de control."
            value.encodeToByteArray().size > 320 -> "El correo es demasiado largo."
            email.isEmpty() && !required -> null
            !emailPattern.matches(email) ||
                email.substringBefore('@').let { it.startsWith('.') || it.endsWith('.') || ".." in it } -> "Ingresa un correo válido, como nombre@ejemplo.com."
            else -> null
        }
    }

    fun phone(required: Boolean = false) = InputRule { value ->
        val phone = value.trim()
        when {
            value.any { it.isISOControl() } -> "Escribe el teléfono sin saltos de línea ni caracteres de control."
            value.length > 30 -> "El teléfono es demasiado largo."
            phone.isEmpty() && !required -> null
            !phonePattern.matches(phone) || phone.count { it in '0'..'9' } !in 7..15 ->
                "Usa entre 7 y 15 dígitos; puedes incluir + al inicio, espacios, guiones y paréntesis."
            else -> null
        }
    }

    private val personName = Regex("^[\\p{L}](?:[\\p{L}\\p{M} '\u2019-]*[\\p{L}\\p{M}])?$")
    private val emailPattern = Regex("^[\\p{L}\\p{N}!#$%&'*+/=?^_`{|}~.-]+@[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?(?:\\.[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?)+$")
    private val phonePattern = Regex("^\\+?[0-9 ()-]+$")
}

/** Admite signo y separador decimal local; nunca interpreta exponentes, NaN o infinito. */
object DecimalInput : InputRule {
    private val pattern = Regex("^[+-]?(?:[0-9]+(?:[.,][0-9]{1,2})?|[.,][0-9]{1,2})$")
    fun isValid(value: Double?) = value != null && value.isFinite() && abs(value) < 100000000 &&
        abs(value * 100 - round(value * 100)) < 0.000001
    fun parse(value: String): Double? = value.takeUnless { it.any(Char::isISOControl) }?.trim()?.takeIf(pattern::matches)?.replace(',', '.')?.toDoubleOrNull()?.takeIf(::isValid)
    fun format(value: Double): String? {
        if (!isValid(value)) return null
        val scaled = round(value * 100).toLong()
        val magnitude = if (scaled < 0) -scaled else scaled
        val whole = "${if (scaled < 0) "-" else ""}${magnitude / 100}"
        val fraction = magnitude % 100
        return whole + if (fraction == 0L) "" else "." + fraction.toString().padStart(2, '0').trimEnd('0')
    }
    override fun error(value: String): String? = if (parse(value) != null) null else "Ingresa un número entre −99999999,99 y 99999999,99, con hasta dos decimales."
}
