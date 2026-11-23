package pe.bpm.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        if (onLearn != null) BpmPlayCard(Modifier.fillMaxWidth()) {
            Text("Mi aprendizaje", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            BpmChunkyButton("Ir a Academia", onLearn, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        if (!railVisible) SettingsEntryCard(onSettings)
    }
}
