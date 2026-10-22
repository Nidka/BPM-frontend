package pe.bpm.core.designsystem

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction

/** Configura la entrada; el teclado es una ayuda y nunca sustituye la validación del dominio. */
enum class BpmInputKind { Text, PersonName, Email, Password, Phone, Decimal }

internal fun BpmInputKind.keyboardOptions() = when (this) {
    BpmInputKind.Text -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
    BpmInputKind.PersonName -> KeyboardOptions(capitalization = KeyboardCapitalization.Words, autoCorrectEnabled = false)
    BpmInputKind.Email -> KeyboardOptions(keyboardType = KeyboardType.Email, autoCorrectEnabled = false)
    BpmInputKind.Password -> KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false)
    BpmInputKind.Phone -> KeyboardOptions(keyboardType = KeyboardType.Phone, autoCorrectEnabled = false)
    BpmInputKind.Decimal -> KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false)
}

/** Compose 1.11 usa Unspecified como valor inicial, distinto de Default. */
internal fun KeyboardOptions.forBpmField(singleLine: Boolean, nextField: Boolean?): KeyboardOptions {
    val explicit = imeAction.takeUnless { it == ImeAction.Default || it == ImeAction.Unspecified }
    return copy(imeAction = explicit ?: nextField?.let { if (it) ImeAction.Next else ImeAction.Done }
        ?: if (singleLine) ImeAction.Done else ImeAction.Default)
}
