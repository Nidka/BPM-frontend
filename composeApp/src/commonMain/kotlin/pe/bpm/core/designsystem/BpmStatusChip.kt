package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Píldora informativa de estado o vencimiento (no es interactiva). Texto de 14 sp en negrita (mínimo del RNF) y, si se indica,
 * un icono de 14 dp a la izquierda. El color sale del [tone]; el texto siempre dice el estado.
 */
@Composable
fun BpmStatusChip(
    text: String,
    tone: BpmTone = BpmTone.Info,
    modifier: Modifier = Modifier,
    symbol: BpmSymbol? = null,
) {
    val (background, content) = tone.colors()
    Row(
        modifier.clip(CircleShape).background(background).padding(horizontal = BpmSpacing.md, vertical = BpmSpacing.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xs),
    ) {
        if (symbol != null) BpmIcon(symbol, tint = content, modifier = Modifier.size(14.dp))
        Text(text, color = content, style = BpmType.labelStrong)
    }
}
