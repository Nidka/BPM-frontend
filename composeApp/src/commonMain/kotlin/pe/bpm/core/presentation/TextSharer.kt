package pe.bpm.core.presentation

import androidx.compose.runtime.Composable

/** Resultado de copiar o compartir un texto; la plataforma no siempre puede confirmar el destino. */
enum class ShareResult { SHARED, COPIED, CANCELLED, FAILED }

/**
 * Entrega un texto breve a la plataforma. El texto solo vive mientras dura la operación: no se guarda
 * en disco ni se registra. Se usa, por ejemplo, para compartir el enlace de una invitación.
 *
 * - Android: hoja de compartir del sistema (`ACTION_SEND`); copiar marca el portapapeles como sensible.
 * - iOS: `UIActivityViewController`; copiar usa un portapapeles solo local que caduca.
 * - Web: `navigator.share` si existe y, si no, copia al portapapeles.
 * - JVM (desarrollo y pruebas): no comparte; devuelve `FAILED`.
 */
interface TextSharer {
    /** Abre la hoja nativa de compartir (en web, `navigator.share` o, sin él, copia). */
    fun share(title: String, text: String, onResult: (ShareResult) -> Unit)

    /** Copia el texto al portapapeles. */
    fun copy(text: String, onResult: (ShareResult) -> Unit)
}

@Composable
expect fun rememberTextSharer(): TextSharer
