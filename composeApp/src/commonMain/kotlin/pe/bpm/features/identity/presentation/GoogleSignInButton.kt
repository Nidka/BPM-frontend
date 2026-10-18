package pe.bpm.features.identity.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.painterResource
import pe.bpm.core.designsystem.BpmFeedback
import pe.bpm.resources.Res
import pe.bpm.resources.google_g
import pe.bpm.resources.google_sans

/** Acción del proveedor con sus recursos oficiales, sin teñir ni deformar el logotipo. */
@Composable
internal fun GoogleSignInButton(onClick: () -> Unit, enabled: Boolean) {
    val foreground = Color(0xFF1F1F1F)
    BpmFeedback(foreground) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            shape = RoundedCornerShape(15.dp),
            border = BorderStroke(1.dp, Color(0xFF747775).copy(alpha = if (enabled) 1f else 0.38f)),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = Color.White, contentColor = foreground,
                disabledContainerColor = Color.White, disabledContentColor = foreground.copy(alpha = 0.38f)),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Image(painterResource(Res.drawable.google_g), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text("Continuar con Google", fontFamily = FontFamily(Font(Res.font.google_sans, weight = FontWeight.Medium)),
                fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
        }
    }
}
