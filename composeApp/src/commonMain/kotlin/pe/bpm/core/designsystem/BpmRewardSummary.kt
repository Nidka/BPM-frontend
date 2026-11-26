package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Resumen de recompensas: puntos de experiencia grandes («120 puntos»), insignias ganadas («2 de 8 insignias») y,
 * si se conoce, la próxima insignia con su pista. Con [onOpen] añade el botón de texto «Ver mis logros».
 */
@Composable
fun BpmRewardSummary(
    experiencePoints: Int,
    earnedBadges: Int,
    totalBadges: Int,
    modifier: Modifier = Modifier,
    nextBadgeTitle: String? = null,
    nextBadgeHint: String? = null,
    onOpen: (() -> Unit)? = null,
) {
    BpmCard(modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
        Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(BpmTokens.warningSurface), contentAlignment = Alignment.Center) {
                BpmIcon(BpmSymbol.Award, tint = BpmTokens.warningText, modifier = Modifier.size(22.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Text("Tus logros", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("$experiencePoints puntos", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"))
            }
        }
        Text("$earnedBadges de $totalBadges insignias", style = BpmType.bodyMediumStrong)
        if (nextBadgeTitle != null) {
            Column(verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Text("Próxima insignia", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(nextBadgeTitle, style = MaterialTheme.typography.titleMedium)
                nextBadgeHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (onOpen != null) BpmTextButton(onOpen) { Text("Ver mis logros") }
    }
}
