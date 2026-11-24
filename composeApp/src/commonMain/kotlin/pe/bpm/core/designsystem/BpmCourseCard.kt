package pe.bpm.core.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Paleta fija de la banda de curso: primario, texto, verde de éxito y un marrón cálido (todos oscuros: texto blanco legible). */
private val CourseBandColors = listOf(BpmTokens.primary, BpmTokens.text, BpmTokens.successText, Color(0xFF7A3A12))

/**
 * Tarjeta de curso: banda superior de 72 dp con el [code] sobre uno de cuatro colores ([colorIndex] elige el tono,
 * cíclico), [title], [meta] (duración, lecciones), barra de [progress] opcional (0 a 1) y un [status] opcional
 * (por ejemplo un [BpmStatusChip]). Toda la tarjeta es el botón; no lleva botón interior.
 */
@Composable
fun BpmCourseCard(
    code: String,
    title: String,
    meta: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colorIndex: Int = 0,
    progress: Float? = null,
    status: (@Composable () -> Unit)? = null,
) {
    val shape = RoundedCornerShape(24.dp)
    val border = MaterialTheme.colorScheme.outlineVariant
    val band = CourseBandColors[((colorIndex % CourseBandColors.size) + CourseBandColors.size) % CourseBandColors.size]
    // Mismo relieve y borde que BpmCard; el contenido va a sangre, por eso no se usa su relleno.
    Box(modifier.bpmRelief(border, shape, BpmSpacing.reliefDepth), propagateMinConstraints = true) {
        BpmFeedback(MaterialTheme.colorScheme.primary) {
            Surface(shape = shape, color = MaterialTheme.colorScheme.surface, border = BorderStroke(2.dp, border)) {
                Column(Modifier.fillMaxHeight().clickable(role = Role.Button, onClick = onClick)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 72.dp).background(band).padding(horizontal = BpmSpacing.lg), contentAlignment = Alignment.CenterStart) {
                        Text(code, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.fillMaxWidth().padding(BpmSpacing.lg), verticalArrangement = Arrangement.spacedBy(BpmSpacing.sm)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = bpmTitleLines(3), overflow = TextOverflow.Ellipsis)
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (progress != null) BpmProgressBar(progress)
                        if (status != null) status()
                    }
                }
            }
        }
    }
}
