package pe.bpm.features.identity.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import pe.bpm.core.designsystem.*
import pe.bpm.core.presentation.ANDROID_APK_URL

/** Invitación a instalar la app Android desde el ingreso web: botón al APK firmado y una guía corta plegable. */
@Composable
internal fun AndroidDownloadCard(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    var showSteps by remember { mutableStateOf(false) }
    BpmSectionCard("Descarga la app para Android", BpmSymbol.Download, modifier, tone = BpmPlayTone.Correct) {
        Text("Úsala en tu celular, incluso sin internet.", style = MaterialTheme.typography.bodyMedium)
        BpmChunkyButton("Descargar app Android", { runCatching { uriHandler.openUri(ANDROID_APK_URL) } },
            Modifier.fillMaxWidth().heightIn(min = 52.dp))
        Text("Si Android te pregunta, permite instalar apps de esta fuente.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BpmTextButton(onClick = { showSteps = !showSteps }) { Text(if (showSteps) "Ocultar pasos" else "¿Cómo se instala?") }
        if (showSteps) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Pulsa «Descargar app Android» y espera que termine.")
            Text("2. Abre el archivo BPM.apk desde tus descargas.")
            Text("3. Pulsa «Instalar» y luego «Abrir». Entra con tu cuenta de siempre.")
        }
    }
}
