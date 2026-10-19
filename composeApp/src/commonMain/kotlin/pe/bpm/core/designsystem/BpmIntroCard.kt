package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Presentación de sección: la mascota acompaña el título sin desplazar el contenido móvil. */
@Composable
fun BpmIntroCard(title: String, description: String, learning: Boolean = false,
    actions: @Composable ColumnScope.() -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val expanded = maxWidth >= 600.dp
        BpmCard(Modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (expanded) Text(description)
                }
                Mascot(Modifier.size(if (expanded) 128.dp else 64.dp), learning)
            }
            if (!expanded) Text(description)
            actions()
        }
    }
}
