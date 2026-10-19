package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Acción secundaria compartida; conserva jerarquía, foco y área táctil Material. */
@Composable
fun BpmSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled,
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = .6f) else MaterialTheme.colorScheme.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary), content = content)
    }
}

/** Acción de texto compartida; no compite visualmente con el envío principal. */
@Composable
fun BpmTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    content: @Composable RowScope.() -> Unit) {
    BpmFeedback(MaterialTheme.colorScheme.primary) {
        TextButton(onClick, modifier.heightIn(min = 48.dp), enabled,
            shape = RoundedCornerShape(12.dp), contentPadding = contentPadding,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary), content = content)
    }
}
