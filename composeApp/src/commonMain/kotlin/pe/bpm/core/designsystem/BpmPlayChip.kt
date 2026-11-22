package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Ficha pequeña del estilo de juego (`.chip`): texto en mayúsculas sobre el fondo suave del [tone] (verde «2 de 3»,
 * dorado para la meta, teal para la sede). Mide lo que mide su texto; no es un control (para elegir, usa [BpmChoiceTile]).
 */
@Composable
fun BpmPlayChip(text: String, tone: BpmPlayTone, modifier: Modifier = Modifier, uppercase: Boolean = true) {
    val shade = BpmPlayColors.shade(tone)
    Text(
        if (uppercase) text.uppercase() else text,
        modifier.background(shade.soft, CircleShape).padding(horizontal = 10.dp, vertical = 4.dp),
        color = if (tone == BpmPlayTone.Gold) BpmPlayColors.goldText else shade.shadow, style = BpmPlayType.label, maxLines = 1,
    )
}
