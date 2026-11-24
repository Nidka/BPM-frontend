package pe.bpm.core.designsystem

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desplazamiento vertical para una pantalla alojada en el anfitrión de pantallas secundarias (Ajustes, Ayuda, Reportes…), que
 * recorta en vez de desplazar: sin esto, lo que no cabe en 844 dp de alto (incluido «Cerrar sesión») no se podía alcanzar.
 * Úsalo solo en pantallas que no desplazan por sí mismas: dos desplazamientos anidados cierran la app.
 */
@Composable
fun Modifier.bpmScrollable(): Modifier = verticalScroll(rememberScrollState())
