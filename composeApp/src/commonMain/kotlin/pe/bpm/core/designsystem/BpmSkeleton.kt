package pe.bpm.core.designsystem

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Esqueletos de carga: en lugar de una barra indeterminada o un «Cargando…», el contenido se anuncia con bloques grises que
 * tienen la forma de lo que va a llegar (filas, tarjetas, indicadores, video), así la pantalla no «salta» al llenarse.
 * Una barra de progreso queda solo para un avance real (curso, meta, subida o descarga).
 */

/** Gris base del esqueleto y su brillo; sale del borde neutro del estilo de juego. */
private val SkeletonBase = Color(0xFFDCE4E4)
private val SkeletonShine = Color(0xFFEFF4F4)

/**
 * Progreso 0..1 del destello que recorre todos los esqueletos de la pantalla. Con «reducir movimiento» queda quieto
 * (0) y el esqueleto se ve como bloques planos.
 */
@Composable
private fun rememberShimmer(): Float {
    if (rememberReduceMotion()) return 0f
    val transition = rememberInfiniteTransition(label = "esqueleto")
    val progress by transition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "destello")
    return progress
}

/**
 * Bloque gris con destello: la pieza de todos los esqueletos. Dale tamaño con [modifier] (`Modifier.size`, `fillMaxWidth`)
 * y forma con [shape]. Es decorativo: la descripción de «Cargando» la lleva el contenedor ([BpmSkeletonRegion]).
 */
@Composable
fun BpmSkeleton(modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    val progress = rememberShimmer()
    Box(modifier.clip(shape).background(SkeletonBase).drawWithContent {
        drawContent()
        if (progress > 0f) {
            val width = size.width.coerceAtLeast(1f)
            // La franja de brillo cruza el bloque de izquierda a derecha y sale por el otro lado.
            val start = -width + progress * width * 2.4f
            drawRect(Brush.linearGradient(listOf(Color.Transparent, SkeletonShine, Color.Transparent),
                Offset(start, 0f), Offset(start + width, 0f)))
        }
    })
}

/** Línea de texto del esqueleto: [width] de ancho (o todo el ancho si es [Dp.Unspecified]) y [height] de alto. */
@Composable
fun BpmSkeletonLine(modifier: Modifier = Modifier, width: Dp = Dp.Unspecified, height: Dp = 14.dp) {
    BpmSkeleton(modifier.then(if (width == Dp.Unspecified) Modifier.fillMaxWidth() else Modifier.size(width, height)).height(height), RoundedCornerShape(6.dp))
}

/** Círculo del esqueleto (avatar, insignia, icono de una fila). */
@Composable
fun BpmSkeletonCircle(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    BpmSkeleton(modifier.size(size), CircleShape)
}

/**
 * Región que carga: agrupa los esqueletos y la anuncia a los lectores de pantalla como [label] (una vez, en lugar de leer
 * cada bloque). Todas las plantillas ([BpmSkeletonCard], [BpmSkeletonList]…) ya la incluyen.
 */
@Composable
fun BpmSkeletonRegion(modifier: Modifier = Modifier, label: String = "Cargando", content: @Composable () -> Unit) {
    Box(modifier.semantics(mergeDescendants = true) { contentDescription = label; liveRegion = LiveRegionMode.Polite }) { content() }
}

/** Fila de lista: círculo o icono a la izquierda, título y detalle, y una ficha corta a la derecha. */
@Composable
fun BpmSkeletonRow(modifier: Modifier = Modifier, leading: Boolean = true, trailing: Boolean = true) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading) BpmSkeleton(Modifier.size(44.dp), RoundedCornerShape(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BpmSkeletonLine(width = 180.dp, height = 16.dp)
            BpmSkeletonLine(width = 120.dp, height = 12.dp)
        }
        if (trailing) BpmSkeleton(Modifier.size(56.dp, 24.dp), CircleShape)
    }
}

/** Lista de [rows] filas dentro de una tarjeta de juego; es lo que sustituye al «Cargando…» de un listado. */
@Composable
fun BpmSkeletonList(modifier: Modifier = Modifier, rows: Int = 4, label: String = "Cargando", card: Boolean = true) {
    BpmSkeletonRegion(modifier.fillMaxWidth(), label) {
        val body: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { repeat(rows) { BpmSkeletonRow() } }
        }
        if (card) BpmPlayCard { body() } else body()
    }
}

/** Tarjeta con título, [lines] líneas de texto y, si [action], un botón: la forma de una ficha de detalle o de un aviso. */
@Composable
fun BpmSkeletonCard(modifier: Modifier = Modifier, lines: Int = 2, action: Boolean = false, label: String = "Cargando") {
    BpmSkeletonRegion(modifier.fillMaxWidth(), label) {
        BpmPlayCard {
            BpmSkeletonLine(width = 160.dp, height = 18.dp)
            repeat(lines) { index -> BpmSkeletonLine(width = if (index == lines - 1) 200.dp else Dp.Unspecified, height = 14.dp) }
            if (action) BpmSkeleton(Modifier.fillMaxWidth().height(48.dp), RoundedCornerShape(14.dp))
        }
    }
}

/** Fila de [count] indicadores (cifra grande y rótulo) como los de [BpmKpiTile]; en pantallas estrechas se reparten el ancho. */
@Composable
fun BpmSkeletonKpis(modifier: Modifier = Modifier, count: Int = 4, label: String = "Cargando indicadores") {
    BpmSkeletonRegion(modifier.fillMaxWidth(), label) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(count) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BpmSkeletonLine(width = 48.dp, height = 24.dp)
                    BpmSkeletonLine(height = 12.dp)
                }
            }
        }
    }
}

/** Reproductor de video 16:9 con el botón de reproducir en el centro. */
@Composable
fun BpmSkeletonVideo(modifier: Modifier = Modifier, label: String = "Preparando el video") {
    BpmSkeletonRegion(modifier.fillMaxWidth(), label) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
            BpmSkeleton(Modifier.fillMaxWidth().aspectRatio(16f / 9f), RoundedCornerShape(16.dp))
            BpmSkeleton(Modifier.size(56.dp), CircleShape)
        }
    }
}

/** Contenido de un panel de detalle: cabecera con título y ficha, párrafo y tres filas. */
@Composable
fun BpmSkeletonDetail(modifier: Modifier = Modifier, label: String = "Cargando el detalle") {
    BpmSkeletonRegion(modifier.fillMaxWidth(), label) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BpmSkeletonLine(width = 220.dp, height = 24.dp)
            BpmSkeleton(Modifier.size(88.dp, 24.dp), CircleShape)
            BpmSkeletonLine(height = 14.dp)
            BpmSkeletonLine(width = 260.dp, height = 14.dp)
            repeat(3) { BpmSkeletonRow(trailing = false) }
        }
    }
}

/** Pantalla de Hoy o Inicio: tira de indicadores y dos tarjetas de lista; sirve de esqueleto de panel completo. */
@Composable
fun BpmSkeletonDashboard(modifier: Modifier = Modifier, label: String = "Cargando") {
    BpmSkeletonRegion(modifier.fillMaxWidth(), label) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            BpmSkeletonKpis(label = label)
            BpmSkeletonList(rows = 3, label = label)
        }
    }
}
