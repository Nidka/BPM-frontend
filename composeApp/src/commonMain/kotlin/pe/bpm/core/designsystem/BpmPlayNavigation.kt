package pe.bpm.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.painterResource
import pe.bpm.resources.*

/** Destino de la navegación de juego: [label] (se muestra en mayúsculas), [icon], si está [selected] y su [onClick]. */
@Immutable
data class BpmPlayNavItem(val label: String, val icon: BpmSymbol, val selected: Boolean, val onClick: () -> Unit)

/** Ancho del menú lateral grande del estilo de juego. */
val BpmPlaySideMenuWidth: Dp = 230.dp

/**
 * Barra inferior del estilo de juego (`.nav`): [items] en columnas iguales sobre fondo blanco con borde superior de
 * 2 dp. Cada destino lleva un icono de 26 dp dentro de un recuadro de 52 × 38 dp y su etiqueta en mayúsculas (Nunito 900,
 * 11,5 sp); el seleccionado tiene el recuadro en teal suave con borde de 2 dp y la etiqueta en teal.
 */
@Composable
fun BpmPlayBottomBar(items: List<BpmPlayNavItem>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color.White)
            .drawBehind { drawRect(BpmPlayColors.line, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, BpmPlayBorder.toPx())) }
            .padding(start = 6.dp, top = 8.dp, end = 6.dp, bottom = 14.dp),
    ) {
        items.forEach { item ->
            val color = if (item.selected) BpmPlayColors.action.face else BpmPlayColors.navInactive
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).selectable(item.selected, role = Role.Tab, onClick = item.onClick),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(52.dp, 38.dp).then(
                        if (item.selected) Modifier.background(BpmPlayColors.action.soft, RoundedCornerShape(14.dp))
                            .border(BpmPlayBorder, BpmPlayColors.navBorder, RoundedCornerShape(14.dp)) else Modifier),
                    contentAlignment = Alignment.Center,
                ) { BpmIcon(item.icon, tint = color, modifier = Modifier.size(26.dp)) }
                Spacer(Modifier.height(2.dp))
                Text(item.label.uppercase(), color = color, style = BpmPlayType.navLabel, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * Menú lateral grande del estilo de juego (`.side`, 230 dp): marca [brand] arriba (logo de 38 dp y nombre en Baloo 2)
 * y los [items] en filas de 28 dp de icono con etiqueta en mayúsculas; el seleccionado va en teal sobre fondo suave
 * con borde de 2 dp. Borde derecho de 2 dp. Se usa desde la clase expandida.
 */
@Composable
fun BpmPlaySideMenu(brand: String, items: List<BpmPlayNavItem>, modifier: Modifier = Modifier) {
    Column(
        modifier.width(BpmPlaySideMenuWidth).fillMaxHeight().background(Color.White)
            .drawBehind { drawRect(BpmPlayColors.line, Offset(size.width - BpmPlayBorder.toPx(), 0f), androidx.compose.ui.geometry.Size(BpmPlayBorder.toPx(), size.height)) }
            .padding(horizontal = 14.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(Res.drawable.bpm_logo), contentDescription = null, modifier = Modifier.size(38.dp))
            Text(brand, style = BpmPlayType.h1.copy(fontSize = 24.sp), color = BpmPlayColors.action.face)
        }
        items.forEach { item ->
            val color = if (item.selected) BpmPlayColors.action.face else BpmPlayColors.muted
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).selectable(item.selected, role = Role.Tab, onClick = item.onClick)
                    .then(if (item.selected) Modifier.background(BpmPlayColors.action.soft) else Modifier)
                    .border(BpmPlayBorder, if (item.selected) BpmPlayColors.navBorder else Color.Transparent, RoundedCornerShape(14.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                BpmIcon(item.icon, tint = color, modifier = Modifier.size(28.dp))
                Text(item.label.uppercase(), color = color, style = BpmPlayType.sideLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
