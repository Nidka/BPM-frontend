package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Capas 2 y 3 del diseño adaptable: BpmScreen (márgenes, ancho máximo y título por patrón) y los seis patrones.
 * Las pantallas reciben contenido, no medidas: no usan BoxWithConstraints ni escriben márgenes. La clase de ancho
 * viene de LocalBpmWindowClass (la calcula BpmAppShell una sola vez).
 */

/** Los seis patrones que cubren todas las rutas de la app. */
enum class BpmPattern {
    /** Resumen del día y accesos: indicadores arriba, contenido principal y zona de apoyo. */
    Panel,
    /** Registros que se revisan de uno en uno: incidencias, revisiones, plantillas, equipo. */
    ListDetail,
    /** Catálogos de tarjetas parecidas: cursos, plantillas iniciales, logros, accesos. */
    Grid,
    /** Tareas de una sola pasada: listas de control, lección y evaluación, encuestas, recepción. */
    StepFlow,
    /** Crear y editar: plantillas, cursos, turnos, empresas, invitaciones, ajustes. */
    Form,
    /** Pantallas de un solo propósito: ingreso, elegir empresa, conexión, aceptar invitación. */
    Access,
}

/** Reglas de medida por patrón y clase de ancho (tabla del diseño). Son funciones puras, fáciles de probar. */
object BpmPatternRules {
    /** Ancho de la lista en lista–detalle cuando el detalle va al lado. */
    val listWidth: Dp = 360.dp
    /** Columna central de flujo por pasos y formulario. */
    val readingWidth: Dp = 720.dp
    /** Tarjeta centrada de acceso. */
    val accessWidth: Dp = 440.dp
    /** Ancho mínimo de una tarjeta en la rejilla automática de la clase expandida. */
    val gridMinCard: Dp = 300.dp

    /** Ancho máximo del contenido; [Dp.Unspecified] significa «todo el ancho disponible». */
    fun contentMax(pattern: BpmPattern, windowClass: BpmWindowClass, preview: Boolean = true): Dp = when (pattern) {
        BpmPattern.Panel, BpmPattern.ListDetail, BpmPattern.Grid -> BpmSpacing.contentMax
        BpmPattern.StepFlow -> if (windowClass == BpmWindowClass.Compact) Dp.Unspecified else readingWidth
        BpmPattern.Form -> when (windowClass) {
            BpmWindowClass.Compact -> Dp.Unspecified
            BpmWindowClass.Large -> if (preview) BpmSpacing.contentMax else readingWidth
            else -> readingWidth
        }
        BpmPattern.Access -> if (windowClass == BpmWindowClass.Compact) Dp.Unspecified else accessWidth
    }

    /** Columnas de indicadores del panel: 2×2 en compacta, una fila de 4 desde media. */
    fun indicatorColumns(windowClass: BpmWindowClass, count: Int = 4): Int =
        if (windowClass == BpmWindowClass.Compact) (if (count == 3) 3 else 2) else count.coerceIn(1, 4)

    /** Columnas fijas de la rejilla; `null` = automática con [gridMinCard] (clase expandida). */
    fun gridColumns(windowClass: BpmWindowClass): Int? = when (windowClass) {
        BpmWindowClass.Compact -> 1
        BpmWindowClass.Medium -> 2
        BpmWindowClass.Expanded -> null
        BpmWindowClass.Large -> 3
    }

    /** El detalle va junto a la lista desde la clase expandida; antes ocupa toda la pantalla. */
    fun detailBesideList(windowClass: BpmWindowClass): Boolean =
        windowClass == BpmWindowClass.Expanded || windowClass == BpmWindowClass.Large

    /** El panel reparte principal 2/3 y apoyo 1/3 solo en la clase grande. */
    fun panelSplit(windowClass: BpmWindowClass): Boolean = windowClass == BpmWindowClass.Large

    /** Campos cortos en dos columnas desde la clase media. */
    fun formTwoColumns(windowClass: BpmWindowClass): Boolean = windowClass != BpmWindowClass.Compact

    /** Vista previa junto al formulario solo en la clase grande. */
    fun formPreview(windowClass: BpmWindowClass): Boolean = windowClass == BpmWindowClass.Large
}

/**
 * Marco común de una pantalla: margen lateral por clase de ancho, tope de ancho del [pattern] y centrado.
 * Lo usan [BpmScreen] y el shell para las pantallas que aún no migraron, de modo que todas compartan margen.
 */
@Composable
fun BpmScreenFrame(
    pattern: BpmPattern,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val windowClass = currentWindowClass
    val margin = BpmSpacing.gutter(windowClass)
    val max = BpmPatternRules.contentMax(pattern, windowClass)
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = if (max == Dp.Unspecified) Dp.Unspecified else max + margin * 2).fillMaxWidth().padding(margin),
            verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl),
            content = content,
        )
    }
}

/**
 * Pantalla del sistema: marco del patrón + título único (`BpmPageHeader`). Las pantallas no ponen márgenes
 * propios ni miden la ventana.
 */
@Composable
fun BpmScreen(
    title: String,
    pattern: BpmPattern,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BpmScreenFrame(pattern, modifier) {
        BpmPageHeader(title, description = description, actions = actions)
        content()
    }
}

/**
 * Panel: [indicators] arriba (2×2 en compacta, fila de 4 desde media), [main] y una zona de [support] opcional.
 * En la clase grande el principal ocupa 2/3 y el apoyo 1/3; antes, el apoyo va debajo.
 */
@Composable
fun BpmPanelLayout(
    modifier: Modifier = Modifier,
    indicators: List<@Composable (Modifier) -> Unit> = emptyList(),
    support: (@Composable ColumnScope.() -> Unit)? = null,
    main: @Composable ColumnScope.() -> Unit,
) {
    val windowClass = currentWindowClass
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
        if (indicators.isNotEmpty())
            BpmCardGrid(indicators, fixedColumns = BpmPatternRules.indicatorColumns(windowClass, indicators.size)) { item, m -> item(m) }
        if (support != null && BpmPatternRules.panelSplit(windowClass)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
                Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl), content = main)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl), content = support)
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl), content = main)
            if (support != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl), content = support)
        }
    }
}

/**
 * Lista–detalle. Con [detail] nulo se ve la lista; en clases compacta y media un detalle abierto ocupa toda
 * la pantalla (quien llama ofrece «Volver»); desde la expandida la lista mide 360 dp y el detalle va al lado
 * ([emptyDetail] se muestra mientras no hay selección).
 */
@Composable
fun BpmListDetail(
    modifier: Modifier = Modifier,
    detail: (@Composable () -> Unit)? = null,
    emptyDetail: (@Composable () -> Unit)? = null,
    list: @Composable () -> Unit,
) {
    if (BpmPatternRules.detailBesideList(currentWindowClass)) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
            // La lista mide 360 dp: dentro de ella los componentes se comportan como en pantalla compacta (una columna).
            Column(Modifier.width(BpmPatternRules.listWidth)) { ProvideBpmWindowClass(BpmWindowClass.Compact) { list() } }
            Column(Modifier.weight(1f)) { (detail ?: emptyDetail)?.invoke() }
        }
    } else Column(modifier.fillMaxWidth()) { if (detail != null) detail() else list() }
}

/**
 * Rejilla de tarjetas: 1 columna en compacta, 2 en media, automática (mínimo 300 dp) en expandida y 3 en grande.
 */
@Composable
fun <T> BpmGridLayout(
    items: List<T>,
    modifier: Modifier = Modifier,
    content: @Composable (T, Modifier) -> Unit,
) {
    val fixed = BpmPatternRules.gridColumns(currentWindowClass)
    BpmCardGrid(items, modifier, minCardWidth = BpmPatternRules.gridMinCard, maxColumns = 4, fixedColumns = fixed, content = content)
}

/**
 * Flujo por pasos: una columna con el contenido y la barra de acción ([actionBar]) al final. En compacta ocupa
 * toda la pantalla; desde media, una columna centrada de 720 dp (el tope lo pone [BpmScreen]).
 */
@Composable
fun BpmStepFlow(
    modifier: Modifier = Modifier,
    actionBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg), content = content)
        actionBar()
    }
}

/**
 * Formulario: una columna; los campos cortos se agrupan con [BpmShortFields] (dos columnas desde media);
 * en la clase grande el formulario mide hasta 720 dp y la vista previa ([preview]) va a su derecha.
 */
@Composable
fun BpmFormLayout(
    modifier: Modifier = Modifier,
    preview: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val form: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg), content = content)
            actions()
        }
    }
    if (preview != null && BpmPatternRules.formPreview(currentWindowClass)) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
            form(Modifier.weight(1f).widthIn(max = BpmPatternRules.readingWidth))
            Column(Modifier.weight(1f)) { preview() }
        }
    } else form(modifier.fillMaxWidth())
}

/** Dos campos cortos: en dos columnas desde la clase media, apilados en compacta. */
@Composable
fun BpmShortFields(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (BpmPatternRules.formTwoColumns(currentWindowClass)) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
            first(Modifier.weight(1f)); second(Modifier.weight(1f))
        }
    } else Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
        first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth())
    }
}

/** Acceso: una tarjeta a todo el ancho en compacta y centrada de 440 dp desde media. */
@Composable
fun BpmCenteredCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val windowClass = currentWindowClass
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val cap = BpmPatternRules.contentMax(BpmPattern.Access, windowClass)
        BpmCard(Modifier.widthIn(max = cap).fillMaxWidth(), content = content)
    }
}


/**
 * Pantalla a página completa fuera del shell (estado del servicio, verificación pública, cambios pendientes):
 * mide su propio ancho una vez, publica la clase y centra una columna con el ancho de su [pattern]
 * (440 dp de acceso por defecto; todo el ancho en compacta). Con [scrollable] falso la columna ocupa todo el
 * alto para alojar una lista perezosa con `weight(1f)`.
 */
@Composable
fun BpmAccessScreen(
    modifier: Modifier = Modifier,
    pattern: BpmPattern = BpmPattern.Access,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
        val windowClass = BpmWindowClass.fromWidth(maxWidth)
        ProvideBpmWindowClass(windowClass, maxHeight) {
            val max = BpmPatternRules.contentMax(pattern, windowClass, preview = false)
            val margin = BpmSpacing.gutter(windowClass)
            Box(Modifier.fillMaxSize().then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = if (max == Dp.Unspecified) Dp.Unspecified else max + margin * 2)
                        .then(if (scrollable) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
                        .padding(horizontal = margin, vertical = BpmSpacing.xl),
                    verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg), content = content,
                )
            }
        }
    }
}
