package pe.bpm.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * Estado vacío del estilo de juego: el tomate ([image], 120 dp), un [title] (Baloo 2) y un [message] que dice el siguiente
 * paso, centrados en una tarjeta. [action] es opcional (un botón grueso, p. ej. «Ir a Aprender»). La ilustración es
 * decorativa: el título y el mensaje llevan el significado. Mide lo que mide su contenido.
 */
@Composable
fun BpmPlayEmpty(
    image: DrawableResource,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    BpmPlayCard(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(image), contentDescription = null, modifier = Modifier.width(120.dp))
            Text(title, style = BpmPlayType.h1.copy(fontSize = 24.sp), color = BpmPlayColors.ink, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(message, style = BpmPlayType.sub, color = BpmPlayColors.muted, textAlign = TextAlign.Center)
            if (action != null) action()
        }
    }
}

/**
 * Aviso de «sin conexión» en tarjeta dorada con el icono de wifi tachado (`.card` amarilla de la maqueta). Se anuncia
 * como región activa para que el lector de pantalla lo lea al aparecer. El texto dice qué pasa con lo que haga la persona
 * («se guarda en el teléfono y se envía después»), nunca un éxito simulado.
 */
@Composable
fun BpmPlayNotice(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.fillMaxWidth().border(BpmPlayBorder, BpmPlayColors.goldBorder, shape).background(BpmPlayColors.gold.soft, shape).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        BpmIcon(BpmSymbol.WifiOff, tint = BpmPlayColors.goldText, modifier = Modifier.size(24.dp))
        Text(text, style = BpmPlayType.sub, color = BpmPlayColors.ink, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
    }
}
