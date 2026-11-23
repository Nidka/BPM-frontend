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
 * «{rol} · {detalle}»), ajustes de sonido y vibración y tarjeta de cuenta que el de Personal y Jefe, sin racha ni insignias
 * (son de quien aprende). Con [onLearn] ofrece «Mi aprendizaje» (el administrador estudia en Academia).
 */
@Composable
internal fun ManagerProfileContent(
    displayName: String,
    roleLabel: String,
    detail: String,
    settings: BpmFeedbackSettings,
    onSound: (Boolean) -> Unit,
    onVibration: (Boolean) -> Unit,
    onDevices: () -> Unit,
    onAcceptInvitation: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
    onLearn: (() -> Unit)? = null,
    onAccessibility: (textScale: Float, highContrast: Boolean, readAloud: Boolean) -> Unit = { _, _, _ -> },
) {
    BpmPlayColumns(modifier, alignStart = true, rail = {
        SettingsCard(settings, onSound, onVibration, onAccessibility)
    }) { railVisible ->
        ProfileHeader(displayName, detail, roleLabel)
        if (onLearn != null) BpmPlayCard(Modifier.fillMaxWidth()) {
            Text("Mi aprendizaje", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            BpmChunkyButton("Ir a Academia", onLearn, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        AccountCard(onDevices, onAcceptInvitation, onLogout)
        if (!railVisible) SettingsCard(settings, onSound, onVibration, onAccessibility)
    }
}
