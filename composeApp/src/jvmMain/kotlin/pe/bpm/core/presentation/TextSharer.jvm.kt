package pe.bpm.core.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** JVM es host de pruebas: no tiene hoja de compartir ni portapapeles que garantizar. */
@Composable
actual fun rememberTextSharer(): TextSharer = remember {
    object : TextSharer {
        override fun share(title: String, text: String, onResult: (ShareResult) -> Unit) = onResult(ShareResult.FAILED)

        override fun copy(text: String, onResult: (ShareResult) -> Unit) = onResult(ShareResult.FAILED)
    }
}
