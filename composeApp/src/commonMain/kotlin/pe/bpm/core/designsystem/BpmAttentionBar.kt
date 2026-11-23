package pe.bpm.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import pe.bpm.resources.Res
import pe.bpm.resources.mascot_done

/** Un motivo de atención de [BpmAttentionBar]: texto corto («3 controles vencidos») y, si lleva a algo, su acción. */
class BpmAttentionItem(val text: String, val onClick: (() -> Unit)? = null)

/** Texto de los motivos sobre el fondo rojo suave (contraste ≥ 4,5:1). */
private val AttentionInk = Color(0xFF7A2219)
/** Borde de la franja de atención. */
private val AttentionBorder = Color(0xFFF2B3AA)

/**
 * Franja «Requiere atención» (`.attn` de la maqueta): la primera fila del inicio de Jefe y Administrador. Con
 * [items] muestra el [title] en rojo con su icono y cada motivo a continuación (los que tienen [BpmAttentionItem.onClick]
 * abren su pantalla y miden 48 dp de alto); salta de línea si no cabe. Sin motivos pasa a «Todo al día» en verde con el tomate
 * y, si se indica, un [calmText] que dice qué sigue. Se anuncia como región activa para el lector de pantalla.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BpmAttentionBar(
    items: List<BpmAttentionItem>,
    modifier: Modifier = Modifier,
    title: String = "Requiere atención",
    calmTitle: String = "Todo al día",
    calmText: String? = null,
) {
    val calm = items.isEmpty()
    val shade = if (calm) BpmPlayColors.correct else BpmPlayColors.error
    val border = if (calm) BpmPlayColors.correctBorder else AttentionBorder
    val fill = if (calm) BpmPlayColors.correct.soft else BpmPlayColors.error.soft
    val titleColor = if (calm) BpmPlayColors.correct.shadow else Color(0xFFAD3027)
    Row(
        modifier.fillMaxWidth().bpmPlayFrame(border, fill, 16.dp).padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (calm) Image(painterResource(Res.drawable.mascot_done), contentDescription = null, modifier = Modifier.width(44.dp))
        FlowRow(
            Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.Start),
            verticalArrangement = Arrangement.Top, itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.heightIn(min = BpmSpacing.touch), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BpmIcon(if (calm) BpmSymbol.Check else BpmSymbol.Alert, tint = shade.shadow, modifier = Modifier.size(20.dp))
                Text(if (calm) calmTitle else title, style = BpmPlayType.h2.copy(fontSize = 15.sp), color = titleColor)
            }
            if (calm) {
                if (calmText != null) Row(Modifier.heightIn(min = BpmSpacing.touch), verticalAlignment = Alignment.CenterVertically) {
                    Text(calmText, style = BpmPlayType.bubble, color = BpmPlayColors.ink)
                }
            } else items.forEach { item ->
                val base = Modifier.heightIn(min = BpmSpacing.touch)
                Row(
                    if (item.onClick != null) base.clickable(role = Role.Button, onClick = item.onClick) else base,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.text, style = BpmPlayType.bubble, color = AttentionInk,
                        textDecoration = if (item.onClick != null) TextDecoration.Underline else null)
                }
            }
        }
    }
}
