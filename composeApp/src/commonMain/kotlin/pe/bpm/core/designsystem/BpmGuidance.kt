package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Cuadro de color con un icono: identifica de un vistazo la categoría o el estado de una fila o tarjeta. */
@Composable
fun BpmIconTile(symbol: BpmSymbol, tone: BpmPlayTone, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val shade = BpmPlayColors.shade(tone)
    Box(modifier.size(size).background(shade.soft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        BpmIcon(symbol, tint = if (tone == BpmPlayTone.Gold) BpmPlayColors.goldText else shade.shadow, modifier = Modifier.size(size / 2))
    }
}

/**
 * «Qué hago ahora»: tarjeta de color con icono, un título claro, los pasos numerados en lenguaje simple y, debajo,
 * las acciones ([actions]: un botón principal y, si hace falta, uno secundario). Una por pantalla: es lo primero que se lee.
 */
@Composable
fun BpmGuideCard(
    symbol: BpmSymbol,
    tone: BpmPlayTone,
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    message: String? = null,
    steps: List<String> = emptyList(),
    actions: (@Composable () -> Unit)? = null,
) {
    val shade = BpmPlayColors.shade(tone)
    val ink = if (tone == BpmPlayTone.Gold) BpmPlayColors.goldText else shade.shadow
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier.fillMaxWidth().background(shade.soft, shape).border(BpmPlayBorder, shade.face.copy(alpha = .35f), shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(shade.face, CircleShape), contentAlignment = Alignment.Center) {
                BpmIcon(symbol, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                eyebrow?.let { Text(it.uppercase(), style = BpmPlayType.kicker, color = ink) }
                Text(title, style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            }
        }
        message?.let { Text(it, style = BpmPlayType.sub, color = BpmPlayColors.ink) }
        if (steps.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(24.dp).background(androidx.compose.ui.graphics.Color.White, CircleShape).border(BpmPlayBorder, shade.face, CircleShape), contentAlignment = Alignment.Center) {
                        Text("${index + 1}", style = BpmPlayType.label, color = ink)
                    }
                    Text(step, Modifier.weight(1f), style = BpmPlayType.sub, color = BpmPlayColors.ink)
                }
            }
        }
        actions?.let { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
    }
}

/** Fila de filtros de una sola línea con desplazamiento lateral: ocupa 48 dp en el teléfono en lugar de cuatro filas de fichas. */
@Composable
fun BpmChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

/** Esqueleto con la forma real de una tarjeta de registro del teléfono: icono, dos líneas, ficha de estado y pie. */
@Composable
fun BpmSkeletonRecordCards(count: Int = 3, modifier: Modifier = Modifier, label: String = "Cargando") {
    BpmSkeletonRegion(modifier.fillMaxWidth(), label) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(count) {
                BpmPlayCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        BpmSkeleton(Modifier.size(44.dp), RoundedCornerShape(14.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            BpmSkeletonLine(width = 170.dp, height = 16.dp)
                            BpmSkeletonLine(height = 12.dp)
                            BpmSkeletonLine(width = 110.dp, height = 12.dp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BpmSkeleton(Modifier.size(84.dp, 24.dp), CircleShape)
                        BpmSkeleton(Modifier.size(64.dp, 24.dp), CircleShape)
                    }
                }
            }
        }
    }
}
