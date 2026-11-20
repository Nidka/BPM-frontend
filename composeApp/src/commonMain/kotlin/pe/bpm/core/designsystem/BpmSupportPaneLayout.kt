package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Ancho del panel de apoyo según la clase de ancho: ninguno en compacta (el panel baja a la misma columna),
 * 260 dp en media y 300 dp en expandida y grande.
 */
fun supportPaneWidth(windowClass: BpmWindowClass): Dp? = when (windowClass) {
    BpmWindowClass.Compact -> null
    BpmWindowClass.Medium -> 260.dp
    BpmWindowClass.Expanded, BpmWindowClass.Large -> 300.dp
}

/**
 * Contenido principal con un panel de apoyo al lado. En clase compacta todo va en una columna: primero [main] y
 * luego [support] (o al revés con [supportFirstOnCompact]). Desde la clase media, [main] ocupa el ancho restante
 * y [support] un ancho fijo ([supportPaneWidth]), alineados arriba y separados por [BpmSpacing.grid].
 */
@Composable
fun BpmSupportPaneLayout(
    modifier: Modifier = Modifier,
    supportFirstOnCompact: Boolean = false,
    support: @Composable ColumnScope.() -> Unit,
    main: @Composable ColumnScope.() -> Unit,
) {
    val paneWidth = supportPaneWidth(currentWindowClass)
    if (paneWidth == null) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid)) {
            if (supportFirstOnCompact) { support(); main() } else { main(); support() }
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.grid), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid), content = main)
            Column(Modifier.width(paneWidth), verticalArrangement = Arrangement.spacedBy(BpmSpacing.grid), content = support)
        }
    }
}
