package pe.bpm.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import org.jetbrains.skia.EncodedImageFormat
import pe.bpm.core.designsystem.*
import java.io.File
import kotlin.test.Test

/**
 * H1 · Base común: dibuja los componentes nuevos y los reestilizados dentro de rejillas con medidas intrínsecas
 * ([BpmCardGrid], [BpmRowGrid], [BpmPanelLayout]) a 390 y 1280 dp. Si alguno usara `BoxWithConstraints` o medidas
 * no soportadas, la composición fallaría aquí y no en la app de Administrador o Jefe. Guarda PNG en `build/visual`.
 */
class BaseCommonComponentsTest {
    private val out = File("build/visual").apply { mkdirs() }

    private fun shot(width: Int) {
        ImageComposeScene(width, 1500, Density(1f)) {
            BpmTheme {
                ProvideBpmWindowClass(BpmWindowClass.fromWidth(width.dp), 1500.dp) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        BpmAttentionBar(listOf(BpmAttentionItem("3 controles vencidos") {}, BpmAttentionItem("2 incidencias abiertas") {}))
                        BpmAttentionBar(emptyList(), calmText = "Tu turno va al día.")
                        BpmPanelLayout(indicators = listOf(
                            { m -> BpmKpiTile("Cumplimiento", "86 %", m, tone = BpmPlayTone.Correct, progress = 0.86f, trend = "▲ 4 pts", trendGood = true) },
                            { m -> BpmKpiTile("Controles hoy", "7", m, tone = BpmPlayTone.Gold, support = "3 pendientes") },
                            { m -> BpmKpiTile("Incidencias", null, m) },
                            { m -> BpmStatTile("12", "Personas", m, support = "Activas") },
                        )) { }
                        BpmRowGrid(listOf("Ana Pérez", "Luis Gómez"), columns = 2) { name, m -> BpmPersonRow(name, m, detail = "Cocina · Sede Miraflores", progress = 0.62f) }
                        BpmCard(Modifier.fillMaxWidth()) {
                            BpmDataRow("Sede Miraflores", subtitle = "12 personas", progress = 0.8f, value = "80 %", status = BpmRecordStatus("Al día", BpmTone.Success))
                            BpmDataRow("Sede San Isidro", progress = 0.4f, value = "40 %", status = BpmRecordStatus("Atrasada", BpmTone.Danger), selected = true, onClick = {})
                        }
                        BpmListRow("María Fernanda Quispe", detail = "maria@restaurante.pe", chip = "Colaborador") { Text("Cocina") }
                        BpmStepForm(
                            steps = listOf(BpmFormStep("¿A quién invitas?") { BpmTextField("", {}, "Correo") }, BpmFormStep("¿Qué rol tendrá?") { Text("Rol") }),
                            current = 0, onStepChange = {}, submitLabel = "Enviar invitación", onSubmit = {}, onCancel = {},
                        )
                    }
                }
            }
        }.use { scene ->
            scene.render(0); scene.render(2_000_000_000); scene.render(2_500_000_000)
            File(out, "h1-base-$width.png").writeBytes(scene.render(3_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test fun baseComponentsComposeInPhoneAndDesktopGrids() { shot(390); shot(1280) }
}
