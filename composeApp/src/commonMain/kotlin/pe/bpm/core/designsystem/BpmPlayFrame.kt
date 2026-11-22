package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Borde lateral y superior de las tarjetas del estilo de juego. */
internal val BpmPlayBorder: Dp = 2.dp

/** Alto del borde inferior grueso de filas y opciones (`border-bottom-width: 4px`). */
internal val BpmPlayBorderBottom: Dp = 4.dp

/**
 * Marco de juego: borde de 2 dp con el borde inferior más grueso ([bottom], 4 dp por defecto) y relleno [fill].
 * Se arma con dos fondos (el color del borde y, dentro, el relleno), así el borde inferior grueso conserva las
 * esquinas redondeadas. El contenido queda dentro del relleno.
 */
internal fun Modifier.bpmPlayFrame(
    border: Color, fill: Color, radius: Dp, bottom: Dp = BpmPlayBorderBottom, side: Dp = BpmPlayBorder,
): Modifier = this
    .background(border, RoundedCornerShape(radius))
    .padding(start = side, top = side, end = side, bottom = bottom)
    .background(fill, RoundedCornerShape((radius - side).coerceAtLeast(0.dp)))
