package pe.bpm.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Texto principal de la racha: «5 días seguidos», «1 día seguido» o «Sin racha todavía». */
fun streakTitle(currentDays: Int): String = when {
    currentDays <= 0 -> "Sin racha todavía"
    currentDays == 1 -> "1 día seguido"
    else -> "$currentDays días seguidos"
}

/**
 * Pista bajo la racha: si ya aprendió hoy lo celebra; si la racha sigue vigente (terminó ayer) avisa que hoy hay que
 * aprender para no perderla; sin racha invita a empezar una.
 */
fun streakHint(currentDays: Int, activeToday: Boolean): String = when {
    activeToday -> "Hoy ya aprendiste. ¡Sigue así!"
    currentDays > 0 -> "Aprende hoy para no perder tu racha."
    else -> "Aprende hoy para empezar una racha."
}

/**
 * Marcas de los últimos 7 días (del más antiguo al de hoy, que es el último): `true` si ese día cuenta en la racha.
 * La racha termina hoy si [activeToday] y, si no, ayer (vigente pero sin actividad hoy).
 */
fun streakWeekMarks(currentDays: Int, activeToday: Boolean): List<Boolean> {
    val days = currentDays.coerceAtLeast(0)
    val lastCounted = if (activeToday) 6 else 5
    val first = lastCounted - minOf(days, 7) + 1
    return (0..6).map { index -> days > 0 && index in first..lastCounted }
}

/**
 * Racha de días con aprendizaje: llama en un cuadro de 40 dp, «N días seguidos», una pista y siete puntos con la
 * semana (el último es hoy; vacío con aro si aún no aprendió hoy). Los datos son los del servidor; no calcula nada.
 */
@Composable
fun BpmStreakBadge(
    currentDays: Int,
    activeToday: Boolean,
    modifier: Modifier = Modifier,
    longestDays: Int? = null,
) {
    val marks = streakWeekMarks(currentDays, activeToday)
    val title = streakTitle(currentDays)
    val hint = streakHint(currentDays, activeToday)
    val warm = currentDays > 0
    BpmCard(modifier.fillMaxWidth(), variant = BpmCardVariant.Compact) {
        Row(horizontalArrangement = Arrangement.spacedBy(BpmSpacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (warm) BpmTokens.warningSurface else BpmNeutralSurface),
                contentAlignment = Alignment.Center,
            ) {
                BpmIcon(BpmSymbol.Flame, tint = if (warm) BpmTokens.warningText else BpmTokens.muted, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BpmSpacing.xs)) {
                Text("Tu racha", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
            }
        }
        Row(
            Modifier.fillMaxWidth().clearAndSetSemantics {
                contentDescription = "Últimos 7 días: ${marks.count { it }} con aprendizaje" + if (activeToday) ", incluido hoy" else ", hoy todavía no"
            },
            horizontalArrangement = Arrangement.spacedBy(BpmSpacing.sm),
        ) {
            marks.forEachIndexed { index, on ->
                val today = index == marks.lastIndex
                val color = if (on) BpmTokens.warningText else MaterialTheme.colorScheme.outlineVariant
                Box(
                    Modifier.size(12.dp).clip(CircleShape)
                        .then(if (on) Modifier.background(color) else Modifier.border(2.dp, if (today) MaterialTheme.colorScheme.primary else color, CircleShape)),
                )
            }
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (longestDays != null && longestDays > currentDays)
            Text("Tu mejor racha: ${if (longestDays == 1) "1 día" else "$longestDays días"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
