package pe.bpm.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import pe.bpm.core.designsystem.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Banco visual: dibuja listados, formularios por pasos y detalles a los anchos de ventana de specs/LAYOUT.md §7,
 * con la navegación real (riel 80 / menú 240) y los márgenes por clase, y guarda PNG en build/visual para
 * revisarlos. También prueba el cursor y el clic sobre la tarjeta completa.
 */
class VisualCheckTest {
    private val out = File("build/visual").apply { mkdirs() }
    private val widths = listOf(360, 600, 840, 1000, 1280)

    /** Simula el shell: navegación lateral según la clase de ventana y el margen de la clase. */
    @Composable private fun Shell(window: Int, content: @Composable ColumnScope.() -> Unit) {
        val windowClass = BpmWindowClass.fromWidth(window.dp)
        val nav = BpmNavigationMode.of(windowClass, 900.dp)
        BpmTheme {
            ProvideBpmWindowClass(windowClass, 900.dp) {
                Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (nav.width > 0.dp) Box(Modifier.width(nav.width).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant))
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(BpmSpacing.gutter(windowClass)),
                        verticalArrangement = Arrangement.spacedBy(BpmSpacing.xl), content = content)
                }
            }
        }
    }

    private fun shot(name: String, window: Int, height: Int = 900, interact: (ImageComposeScene) -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
        ImageComposeScene(window, height, Density(1f)) { Shell(window) { content() } }.use { scene ->
            // Avanza el reloj para que terminen las animaciones de entrada (bpmAppear) antes de la captura.
            scene.render(0)
            scene.render(2_000_000_000)
            interact(scene)
            scene.render(2_500_000_000)
            val image = scene.render(3_000_000_000)
            File(out, "$name-$window.png").writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    private val people = listOf(
        Triple("Ana Pérez Rojas", "ana.perez@restaurante.pe", "Responsable / chef"),
        Triple("Luis Gómez", "luis.gomez@restaurante.pe", "Colaborador"),
        Triple("María Fernanda Quispe Huamán", "maria.quispe@restaurante.pe", "Colaborador"),
        Triple("Jorge Salas", "jorge.salas@restaurante.pe", "Administrador"),
    )

    @Composable private fun TeamList(onOpen: (String) -> Unit = {}) {
        BpmPageHeader("Equipo", description = "Personas con acceso a esta organización.", actions = {
            RaisedButton("Invitar persona", {}, Modifier.bpmPrimaryButtonWidth())
        })
        BpmListToolbar(
            filters = { BpmFilterChips(listOf(0 to "Activas", 1 to "Inactivas"), 0) {} },
            search = { m -> BpmSearchField("", {}, "Buscar personas", m) },
        )
        BpmPagedList(loading = false, failure = null, isEmpty = false, pageNumber = 1, canPrevious = false, canNext = true,
            onRetry = {}, onPrevious = {}, onNext = {}, empty = {}) {
            BpmGridLayout(people) { (name, email, role), m ->
                BpmRecordCard(name, m, status = BpmRecordStatus(role), subtitle = email, details = listOf("Cocina principal, Sede Miraflores"),
                    onOpen = { onOpen(name) }, actionLabel = "Editar rol y sedes",
                    textActions = listOf("Transferir administración" to {}, "Desactivar" to {}))
            }
        }
    }

    @Test fun listados() = widths.forEach { shot("listado", it) { TeamList() } }

    @Test fun formularioPorPasos() = widths.forEach { w ->
        shot("pasos", w) {
            var step by remember { mutableIntStateOf(1) }
            BpmStepForm(listOf(
                BpmFormStep("¿A quién invitas?") { BpmTextField("ana@restaurante.pe", {}, "Correo electrónico *") },
                BpmFormStep("¿Qué podrá hacer?", description = "El rol define qué secciones verá en la app.") {
                    BpmChoiceRow("Administrador", false, supportingText = "Ve y administra todas las sedes.") {}
                    BpmChoiceRow("Responsable / chef", true, supportingText = "Trabaja solo en las sedes que elijas.") {}
                    BpmChoiceRow("Colaborador", false, supportingText = "Trabaja solo en las sedes que elijas.") {}
                },
                BpmFormStep("¿En qué sedes trabajará?", canContinue = false, blockedHint = "Elige al menos una sede para continuar.") {},
            ), step, { step = it }, submitLabel = "Enviar invitación", onSubmit = {}, onCancel = {})
        }
    }

    @Test fun formularioConPares() = widths.forEach { w ->
        shot("formulario", w) {
            BpmFormLayout(actions = { BpmFormActions("Crear sede", {}, onCancel = {}) }) {
                BpmSection("Datos de la sede") {
                    BpmTextField("Sede Miraflores", {}, "Nombre de la sede *")
                    BpmShortFields({ m -> BpmTextField("Av. Larco 123", {}, "Dirección *", m) }, { m -> BpmTextField("Miraflores", {}, "Distrito", m) })
                }
                BpmSection("Contacto") { BpmTextField("", {}, "Teléfono de contacto", error = "Usa solo números.") }
            }
        }
    }

    @Test fun listaYDetalle() = widths.forEach { w ->
        shot("lista-detalle", w) {
            BpmListDetail(list = {
                BpmGridLayout(people.take(3)) { (name, email, _), m -> BpmRecordCard(name, m, subtitle = email, onOpen = {}, actionLabel = "Ver incidencia", selected = name == people.first().first) }
            }, detail = {
                BpmDetailPane("Cámara de frío fuera de rango", status = BpmRecordStatus("En atención", BpmTone.Info),
                    subtitle = "La cámara 2 marcó 7 °C en la revisión de apertura.", onBack = {},
                    actions = {
                        RaisedButton("Registrar corrección", {}, Modifier.bpmPrimaryButtonWidth())
                        BpmSecondaryButton({}) { Text("Consultar historial") }
                    }) {
                    BpmSection("Datos de la incidencia") {
                        BpmDetailFields(listOf(BpmDetailField("Área", "Almacén"), BpmDetailField("Riesgo", "Alto"),
                            BpmDetailField("Reportó", "Ana Pérez"), BpmDetailField("Fecha del reporte", "21/11/2026 09:30"), BpmDetailField("Responsable", null)))
                    }
                }
            })
        }
    }

    /** El cursor sobre cualquier parte de la tarjeta la resalta entera, y el clic fuera del botón también la abre. */
    @Test fun tarjetaCompletaResponde() {
        var opened = ""
        shot("cursor-tarjeta", 1280, interact = { scene ->
            // Esquina superior izquierda de la primera tarjeta (título), lejos del botón.
            scene.sendPointerEvent(PointerEventType.Move, Offset(240f + 32f + 40f, 330f))
        }) { TeamList { opened = it } }
        ImageComposeScene(1280, 900, Density(1f)) { Shell(1280) { TeamList { opened = it } } }.use { scene ->
            scene.render()
            val point = Offset(240f + 32f + 40f, 330f)
            scene.sendPointerEvent(PointerEventType.Move, point)
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            scene.render()
        }
        assertEquals("Ana Pérez Rojas", opened)
    }

    /** Buscador de normativa con el campo compartido y resultados resaltados, a todos los anchos. */
    @Test fun buscadorDeNormativa() = widths.forEach { w ->
        shot("buscador", w) {
            val matches = listOf(
                pe.bpm.features.normative.domain.NormativeMatch("a", "NTS 142-MINSA/2018/DIGESA", "RM 822-2018/MINSA",
                    "Artículo 12. Los alimentos refrigerados se conservan a temperaturas de 0 °C a 4 °C y se registra la refrigeración dos veces al día."),
                pe.bpm.features.normative.domain.NormativeMatch("a", "NTS 142-MINSA/2018/DIGESA", "", "Artículo 13. Los productos congelados se mantienen a -18 °C o menos."),
            )
            pe.bpm.features.normative.presentation.NormativeSearchContent(pe.bpm.features.normative.domain.NormativeStatus(2), false,
                "temperatura refrigeracion", false, "temperatura refrigeracion", matches, null, null, {}, {}, {})
        }
    }
}
