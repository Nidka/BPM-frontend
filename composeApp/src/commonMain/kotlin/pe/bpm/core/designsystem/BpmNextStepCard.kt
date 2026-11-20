package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Tarjeta de «siguiente paso»: superficie de color primario con texto blanco, un título, un apoyo opcional,
 * una barra de avance opcional ([progress] de 0 a 1) y un botón blanco con relieve. La mascota de aprendizaje
 * aparece a la derecha solo desde la clase media ([showMascot]). Una por pantalla: es la acción principal.
 */
@Composable
fun BpmNextStepCard(
    eyebrow: String,
    title: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    progress: Float? = null,
    showMascot: Boolean = true,
    enabled: Boolean = true,
) {
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val wide = currentWindowClass != BpmWindowClass.Compact
    BpmCard(modifier.fillMaxWidth(), tint = MaterialTheme.colorScheme.primary) {
        Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
                Text(eyebrow, style = BpmType.labelStrong, color = onPrimary.copy(alpha = .85f))
                Text(title, style = MaterialTheme.typography.titleLarge, color = onPrimary)
                if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = onPrimary.copy(alpha = .9f))
                if (progress != null) BpmProgressBar(progress, color = onPrimary, trackColor = onPrimary.copy(alpha = .3f))
                // Botón blanco con relieve oscuro: mismo lenguaje que RaisedButton, invertido sobre el fondo primario.
                Box(Modifier.bpmPrimaryButtonWidth().bpmRelief(BpmTokens.primaryRelief, RoundedCornerShape(16.dp), BpmSpacing.reliefDepth)) {
                    BpmFeedback(MaterialTheme.colorScheme.primary) {
                        Button(
                            onClick = onAction, enabled = enabled,
                            modifier = Modifier.fillMaxWidth().heightIn(min = BpmSpacing.button),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = onPrimary, contentColor = MaterialTheme.colorScheme.primary),
                        ) { Text(actionLabel) }
                    }
                }
            }
            if (showMascot && wide) Mascot(Modifier.size(112.dp), learning = true)
        }
    }
}
