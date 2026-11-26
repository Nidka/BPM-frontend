package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import pe.bpm.core.designsystem.*
import pe.bpm.core.designsystem.BpmChunkyButton
import pe.bpm.core.designsystem.BpmChunkyTone
import pe.bpm.core.designsystem.BpmFeedbackSettings
import pe.bpm.core.designsystem.BpmPlayCard
import pe.bpm.core.designsystem.BpmPlayColumns
import pe.bpm.core.designsystem.BpmPlayColors
import pe.bpm.core.designsystem.BpmPlayType
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Perfil del administrador de la organización y de la super administración: el mismo encabezado (avatar, nombre y
 * «{rol} · {detalle}»), la entrada a «Ajustes» que el de Personal y Jefe, sin racha ni insignias
 * (son de quien aprende), y una entrada a «Ajustes». Con [onLearn] ofrece «Mi aprendizaje» (el administrador estudia en Academia).
 */
@Composable
internal fun ManagerProfileContent(
    displayName: String,
    roleLabel: String,
    detail: String,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onLearn: (() -> Unit)? = null,
) {
    BpmPlayColumns(modifier, alignStart = true, rail = { SettingsEntryCard(onSettings) }) { railVisible ->
        ProfileHeader(displayName, detail, roleLabel)
        BpmSectionCard("Mi cuenta", BpmSymbol.Account) {
            BpmInfoRow(BpmSymbol.Team, "Rol", roleLabel, tone = BpmPlayTone.Streak)
            if (detail.isNotBlank()) BpmInfoRow(BpmSymbol.Company, if ('@' in detail) "Correo" else "Organización", detail, tone = BpmPlayTone.Correct)
        }
        if (onLearn != null) BpmSectionCard("Mi aprendizaje", BpmSymbol.Learn, tone = BpmPlayTone.Correct) {
            Text("Cursos y lecciones que tu organización publicó para ti.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            BpmChunkyButton("Ir a Academia", onLearn, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White, icon = BpmSymbol.Learn)
        }
        if (!railVisible) SettingsEntryCard(onSettings)
    }
}
