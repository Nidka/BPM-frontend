package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
    /** Ancho mínimo de una tarjeta de rejilla (con separación de 16 dp): 1 columna < 616, 2 hasta 931, 3 desde 932. */
    val gridMinCard: Dp = 300.dp
    /** Columnas máximas de una rejilla de registros. */
    const val GRID_MAX_COLUMNS = 3
    /** Ancho mínimo del detalle cuando va junto a la lista: con lista y separación, el detalle va al lado desde 864 dp útiles. */
    val detailMinWidth: Dp = 480.dp
    /** Ancho del formulario desde el que dos campos cortos van lado a lado sin cortar etiquetas ni errores. */
    val pairMinWidth: Dp = 420.dp
    /** Ancho del panel de detalle desde el que los datos se muestran en dos columnas. */
    val detailPairMinWidth: Dp = 520.dp

    /*
     * Las reglas siguientes reciben el ANCHO ÚTIL (lo que mide el contenedor: ventana − navegación − márgenes),
     * no la clase de ventana: con el menú lateral de 240 dp una ventana de 900 dp deja 596 dp útiles y debe
     * comportarse como una pantalla compacta. La clase de ventana solo decide navegación y margen.
     */

    /** Columnas de la rejilla según el ancho útil: tantas tarjetas de [gridMinCard] como quepan, entre 1 y 3. */
    fun gridColumns(available: Dp): Int =
        ((available + BpmSpacing.grid) / (gridMinCard + BpmSpacing.grid)).toInt().coerceIn(1, GRID_MAX_COLUMNS)

    /** El detalle va junto a la lista solo si caben la lista (360), la separación (24) y un detalle legible (480). */
    fun detailBesideList(available: Dp): Boolean = available >= listWidth + BpmSpacing.xl + detailMinWidth

    /** Dos campos cortos lado a lado desde [pairMinWidth] de ancho del formulario. */
    fun shortFieldsSideBySide(available: Dp): Boolean = available >= pairMinWidth

    /** Columnas de datos de un detalle: 1 en paneles angostos, 2 desde [detailPairMinWidth]. */
    fun detailFieldColumns(available: Dp): Int = if (available >= detailPairMinWidth) 2 else 1

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

    /** Escala de letra desde la que las tarjetas de indicador de la clase compacta ya no caben en tres columnas. */
    const val LARGE_FONT_SCALE = 1.3f

    /**
     * Columnas de indicadores del panel: 2×2 en compacta, una fila de 4 desde media.
     * Con letra grande ([fontScale] ≥ [LARGE_FONT_SCALE]) la clase compacta baja a 2 columnas para que las etiquetas no se corten.
     */
    fun indicatorColumns(windowClass: BpmWindowClass, count: Int = 4, fontScale: Float = 1f): Int =
        if (windowClass == BpmWindowClass.Compact) (if (count == 3 && fontScale < LARGE_FONT_SCALE) 3 else 2) else count.coerceIn(1, 4)

    /** El panel reparte principal 2/3 y apoyo 1/3 solo en la clase grande (≥ 960 dp útiles con el menú). */
    fun panelSplit(windowClass: BpmWindowClass): Boolean = windowClass == BpmWindowClass.Large

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
            BpmCardGrid(indicators, fixedColumns = BpmPatternRules.indicatorColumns(windowClass, indicators.size, LocalDensity.current.fontScale)) { item, m -> item(m) }
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
 * `true` dentro del detalle de un [BpmListDetail] que va junto a la lista; `false` cuando el detalle ocupa la
 * pantalla (entonces [BpmDetailPane] muestra «Volver a la lista»).
 */
val LocalBpmDetailBeside = compositionLocalOf { false }

/**
 * Lista–detalle. Con [detail] nulo se ve la lista. Si el ancho útil alcanza para lista (360), separación (24) y
 * un detalle legible (480) —desde 864 dp—, el detalle va al lado ([emptyDetail] mientras no hay selección);
 * si no, un detalle abierto ocupa toda la pantalla con un tope de 720 dp y su «Volver».
 */
@Composable
fun BpmListDetail(
    modifier: Modifier = Modifier,
    detail: (@Composable () -> Unit)? = null,
    emptyDetail: (@Composable () -> Unit)? = null,
    list: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (BpmPatternRules.detailBesideList(maxWidth)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
                // La lista mide 360 dp: dentro de ella los componentes se comportan como en pantalla compacta (una columna).
                Column(Modifier.width(BpmPatternRules.listWidth)) { ProvideBpmWindowClass(BpmWindowClass.Compact) { list() } }
                Column(Modifier.weight(1f)) { CompositionLocalProvider(LocalBpmDetailBeside provides true) { (detail ?: emptyDetail)?.invoke() } }
            }
        } else if (detail != null) {
            Column(Modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth()) {
                CompositionLocalProvider(LocalBpmDetailBeside provides false) { detail() }
            }
        } else Column(Modifier.fillMaxWidth()) { list() }
    }
}

/**
 * Rejilla de tarjetas según el ancho útil del contenedor: 1 columna < 616 dp, 2 hasta 931 y 3 desde 932
 * (tarjeta mínima de 300 dp). Nunca se decide por la ventana: con el menú lateral el ancho útil es menor.
 */
@Composable
fun <T> BpmGridLayout(
    items: List<T>,
    modifier: Modifier = Modifier,
    content: @Composable (T, Modifier) -> Unit,
) {
    BpmCardGrid(items, modifier, minCardWidth = BpmPatternRules.gridMinCard, maxColumns = BpmPatternRules.GRID_MAX_COLUMNS, content = content)
}

/**
 * Flujo por pasos: una columna con el contenido y la barra de acción ([actionBar]) al final. En compacta ocupa
 * toda la pantalla; desde media, una columna de 720 dp alineada con el título.
 */
@Composable
fun BpmStepFlow(
    modifier: Modifier = Modifier,
    actionBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.lg), content = content)
        actionBar()
    }
}

/**
 * Formulario de creación o edición (specs/LAYOUT.md §4): una columna de hasta 720 dp alineada a la izquierda
 * bajo el título, con secciones ([BpmSection]), pares de campos cortos ([BpmShortFields]) y las acciones
 * ([BpmFormActions]) al final. En la clase grande la vista previa ([preview]) va a su derecha.
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
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl), content = content)
            actions()
        }
    }
    if (preview != null && BpmPatternRules.formPreview(currentWindowClass)) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.xl)) {
            form(Modifier.weight(1f).widthIn(max = BpmPatternRules.readingWidth))
            Column(Modifier.weight(1f)) { preview() }
        }
    } else form(modifier.widthIn(max = BpmPatternRules.readingWidth).fillMaxWidth())
}

/**
 * Dos campos cortos relacionados (desde/hasta, dirección/distrito): lado a lado si el contenedor mide al menos
 * 420 dp, apilados si no, para no cortar etiquetas ni mensajes de error.
 */
@Composable
fun BpmShortFields(
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (BpmPatternRules.shortFieldsSideBySide(maxWidth)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
                first(Modifier.weight(1f)); second(Modifier.weight(1f))
            }
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(BpmSpacing.md)) {
            first(Modifier.fillMaxWidth()); second(Modifier.fillMaxWidth())
        }
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
    BoxWithConstraints(modifier.fillMaxSize()) {
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
