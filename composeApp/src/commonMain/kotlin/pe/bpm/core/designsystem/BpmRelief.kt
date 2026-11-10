package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * Relieve inferior del sistema de diseño (`box-shadow: 0 Npx 0`): una franja del color indicado
 * bajo el contenido. Ocupa `depth` dp de layout y no se mueve al pulsar el control interior.
 */
fun Modifier.bpmRelief(color: Color, shape: Shape, depth: Dp): Modifier =
    background(color, shape).padding(bottom = depth)
