package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Ancho de la columna de lectura de las pantallas de juego (pregunta, idea, celebración): 640 dp con sus márgenes. */
private val StageWidth = 640.dp

/**
 * Escenario de una pantalla de juego a pantalla completa (lección, pregunta, evaluación, celebración): una barra
 * superior opcional, el contenido con desplazamiento y un pie fijo con la acción. Sustituye a la cabecera del shell
 * en el modo enfoque del personal.
 *
 * - La barra superior aparece si hay [onClose] (X de 48 dp, [closeDescription] para el lector de pantalla),
 *   [progress] (barra gruesa de [progressTone] y un [counter] opcional como «3/8»; [progressDescription] lo lee) o
 *   [steps] (puntos de paso de un flujo corto: [step] de [steps], con [BpmStepDots]).
 * - El contenido se centra en una columna de 640 dp; con [centered] se centra también en vertical (idea, celebración).
 *   [scrollKey] lleva el desplazamiento al inicio cuando cambia (otro paso, otra pregunta).
 * - El [footer] va pegado abajo con borde superior; [footerColor] lo tiñe (franja de respuesta) y [footerPadded] en
 *   falso deja el relleno al contenido (la franja de respuesta trae el suyo).
 */
@Composable
fun BpmPlayStage(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onClose: (() -> Unit)? = null,
    progressTone: BpmPlayTone = BpmPlayTone.Correct,
    counter: String? = null,
    progressDescription: String? = null,
    steps: Int = 0,
    step: Int = 0,
    closeDescription: String = "Salir",
    centered: Boolean = false,
    scrollKey: Any? = null,
    footerColor: Color = Color.White,
    footerPadded: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(Color.White)) {
        if (onClose != null || progress != null || steps > 0) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(Modifier.widthIn(max = StageWidth).fillMaxWidth().padding(start = 8.dp, top = 8.dp, end = 16.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onClose != null) Box(Modifier.size(BpmSpacing.touch).clip(CircleShape).semantics { contentDescription = closeDescription }
                    .clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                    BpmIcon(BpmSymbol.Close, tint = BpmPlayColors.lockText, modifier = Modifier.size(26.dp))
                }
                if (steps > 0) BpmStepDots(steps, step, Modifier.weight(1f))
                else if (progress != null) BpmChunkyProgress(progress, Modifier.weight(1f), progressTone, progressDescription)
                else Box(Modifier.weight(1f))
                if (counter != null) Text(counter, style = BpmPlayType.label, color = BpmPlayColors.muted, modifier = Modifier.clearAndSetSemantics { })
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewport = maxHeight
            val scroll = rememberScrollState()
            LaunchedEffect(scrollKey) { scroll.scrollTo(0) }
            Box(Modifier.fillMaxWidth().heightIn(min = viewport).verticalScroll(scroll), contentAlignment = if (centered) Alignment.Center else Alignment.TopCenter) {
                Column(Modifier.widthIn(max = StageWidth).fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start, content = content)
            }
            // Aviso de que hay más contenido debajo (p. ej. la última alternativa tras un pie alto): un velo blanco que no capta toques.
            if (scroll.canScrollForward) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(32.dp) // medida: velo de 32 dp
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.White))))
        }
        if (footer != null) Box(Modifier.fillMaxWidth().background(footerColor).drawBehind {
            if (footerColor == Color.White) drawRect(BpmPlayColors.line, size = Size(size.width, BpmPlayBorder.toPx()))
        }, contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = StageWidth).fillMaxWidth().then(if (footerPadded) Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 18.dp) else Modifier),
                verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}

/**
 * Tarjeta de juego (`.card`): es la misma [BpmCard] compacta del sistema (borde de 2 dp con 4 dp abajo, radio 18). Con
 * [dashed] el borde es discontinuo y plano (la constancia). Mide lo que mide su contenido.
 */
@Composable
fun BpmPlayCard(modifier: Modifier = Modifier, dashed: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (!dashed) { BpmCard(modifier.fillMaxWidth(), variant = BpmCardVariant.Compact, content = content); return }
    Column(modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(18.dp)).drawBehind {
        val stroke = BpmPlayBorder.toPx()
        drawRoundRect(BpmPlayColors.navBorder, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke), cornerRadius = CornerRadius(18.dp.toPx()),
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 6.dp.toPx()))))
    }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/**
 * Cuerpo de una pantalla de Aprender con la medida de la maqueta: una columna central de 640 dp (centrada en
 * clase compacta y media) o de 600 dp (expandida y grande) y, si cabe, un panel derecho de 330 dp ([rail]: racha,
 * meta e insignia). [content] recibe `true` cuando el panel se muestra, para no repetir lo que ya está en él.
 * Sin [rail] o sin espacio, la columna se centra sola.
 *
 * Con [alignStart] (pantallas de gestión del administrador, que llevan título a la izquierda) la columna sale del
 * margen izquierdo y toma el ancho que sobra hasta 720 dp, y el panel queda a su derecha: título, columna y panel
 * comparten el mismo borde izquierdo en lugar de centrarse como grupo.
 */
@Composable
fun BpmPlayColumns(
    modifier: Modifier = Modifier,
    rail: (@Composable ColumnScope.() -> Unit)? = null,
    alignStart: Boolean = false,
    content: @Composable ColumnScope.(railVisible: Boolean) -> Unit,
) {
    val expanded = currentWindowClass == BpmWindowClass.Expanded || currentWindowClass == BpmWindowClass.Large
    val column = if (alignStart) 720.dp else if (expanded) 600.dp else BpmPatternRules.mediumColumn
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val showRail = rail != null && expanded && maxWidth >= (if (alignStart) 600.dp else column) + 24.dp + 330.dp
        if (showRail && alignStart) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f, fill = false).widthIn(max = column), verticalArrangement = Arrangement.spacedBy(16.dp)) { content(true) }
            Column(Modifier.width(330.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = rail!!)
        } else if (showRail) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
            Column(Modifier.width(column), verticalArrangement = Arrangement.spacedBy(16.dp)) { content(true) }
            Column(Modifier.width(330.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = rail!!)
        } else Box(Modifier.fillMaxWidth(), contentAlignment = if (alignStart) Alignment.TopStart else Alignment.TopCenter) {
            Column(Modifier.widthIn(max = column).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) { content(false) }
        }
    }
}

/** Acción de teclado de una pantalla de preguntas: elegir la opción [index] (tecla 1–9 → 0–8) o confirmar (Enter). */
sealed interface BpmPlayKey {
    data class Choose(val index: Int) : BpmPlayKey
    data object Confirm : BpmPlayKey
}

/** Traduce una tecla a su acción: 1–9 (fila de números o teclado numérico) eligen y Enter confirma; otras, ninguna. */
fun bpmPlayKey(key: Key): BpmPlayKey? = when (key) {
    Key.One, Key.NumPad1 -> BpmPlayKey.Choose(0)
    Key.Two, Key.NumPad2 -> BpmPlayKey.Choose(1)
    Key.Three, Key.NumPad3 -> BpmPlayKey.Choose(2)
    Key.Four, Key.NumPad4 -> BpmPlayKey.Choose(3)
    Key.Five, Key.NumPad5 -> BpmPlayKey.Choose(4)
    Key.Six, Key.NumPad6 -> BpmPlayKey.Choose(5)
    Key.Seven, Key.NumPad7 -> BpmPlayKey.Choose(6)
    Key.Eight, Key.NumPad8 -> BpmPlayKey.Choose(7)
    Key.Nine, Key.NumPad9 -> BpmPlayKey.Choose(8)
    Key.Enter, Key.NumPadEnter -> BpmPlayKey.Confirm
    else -> null
}

/**
 * Atajos de teclado de la web para preguntas: 1–9 llaman a [onChoose] con la opción (si existe entre [optionCount]) y
 * Enter a [onConfirm] («Comprobar», «Continuar»). Pide el foco cuando cambia [focusKey] (otra pregunta o paso) para que
 * las teclas lleguen aunque no se haya tocado nada. En móvil no tiene efecto.
 */
@Composable
fun Modifier.bpmPlayKeys(optionCount: Int, onChoose: (Int) -> Unit, onConfirm: () -> Unit, focusKey: Any? = null): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(focusKey) { runCatching { requester.requestFocus() } }
    return this.focusRequester(requester).focusable().onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (val action = bpmPlayKey(event.key)) {
            is BpmPlayKey.Choose -> if (action.index < optionCount) { onChoose(action.index); true } else false
            BpmPlayKey.Confirm -> { onConfirm(); true }
            null -> false
        }
    }
}
