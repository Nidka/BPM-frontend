package pe.bpm.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.time.Clock
import pe.bpm.core.designsystem.*
import pe.bpm.core.offline.LocalOnline
import pe.bpm.features.training.domain.Achievement
import pe.bpm.features.training.domain.AchievementCriterion
import pe.bpm.features.training.domain.MyAssignment
import pe.bpm.features.training.presentation.TrainingLearnerViewModel
import pe.bpm.features.training.presentation.badgeCriterion
import pe.bpm.features.training.presentation.badgeOrder
import pe.bpm.features.training.presentation.badgeSymbol
import pe.bpm.features.training.presentation.certificateCourses
import pe.bpm.features.training.presentation.deviceToday
import pe.bpm.features.training.presentation.readableDate
import pe.bpm.features.training.presentation.streakTitle
import pe.bpm.features.training.presentation.streakWeek
import pe.bpm.features.training.presentation.trainingFailureMessage

/** Cuántas constancias muestra el perfil antes de remitir a «Aprender». */
private const val CERTIFICATE_LIMIT = 5

/**
 * Perfil del personal (WORKER), estilo de juego («Perfil y logros» de la maqueta): avatar con iniciales, nombre y
 * «Personal · {org}»; la tarjeta de racha con la semana (L–D); las insignias grandes (las ganadas en color y las
 * pendientes en gris con candado: al tocar una se lee cómo ganarla); las constancias de los cursos completados y,
 * debajo, los ajustes «Sonido» y «Vibración» (preferencia local), «Mis dispositivos», «Aceptar una invitación» y
 * «Cerrar sesión» (las acciones de siempre). Solo datos reales: racha y logros del servidor ([TrainingLearnerViewModel]).
 *
 * Desde la clase expandida los ajustes y las cuentas pasan a un panel derecho de 330 dp. Sin conexión se avisa; las
 * constancias llevan al curso (su constancia con código vive en la guía del curso).
 */
@Composable
internal fun WorkerProfileContent(
    learner: TrainingLearnerViewModel,
    userId: String,
    organizationId: String,
    displayName: String,
    organizationName: String,
    onSettings: () -> Unit,
    onOpenCourse: (String) -> Unit,
    onLearn: () -> Unit,
    modifier: Modifier = Modifier,
    /** Rol que se muestra bajo el nombre: «Personal» o «Jefe de cocina» (el perfil es el mismo para ambos). */
    roleLabel: String = "Personal",
) {
    LaunchedEffect(userId, organizationId) {
        learner.open(userId, organizationId, member = true)
        learner.loadAchievements()
    }
    val state by learner.state.collectAsStateWithLifecycle()
    val achievements = state.achievements
    val streak = achievements?.streak
    BpmPlayColumns(modifier, rail = { SettingsEntryCard(onSettings) }) { railVisible ->
        ProfileHeader(displayName, organizationName, roleLabel)
        if (!LocalOnline.current) BpmPlayNotice("Sin conexión: se muestran tus últimos logros. Se actualizarán al volver la red.")
        state.achievementsFailure?.takeIf { achievements == null }?.let { failure ->
            Text(trainingFailureMessage(failure), style = BpmPlayType.sub, color = BpmPlayColors.error.shadow, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            BpmChunkyButton("Volver a intentar", learner::loadAchievements, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
        if (streak != null) StreakCard(streak.currentDays, streak.activeToday, streak.lastActivityDate)
        if (achievements != null) {
            Text("Insignias", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
            BadgeGrid(badgeOrder(achievements.achievements))
        }
        CertificatesCard(certificateCourses(state.assignments), state.assignmentsLoaded, onOpenCourse, onLearn)
        if (!railVisible) SettingsEntryCard(onSettings)
    }
}

/** Avatar de 64 dp con las iniciales, el nombre (Baloo 2) y «{rol} · {org}». */
@Composable
internal fun ProfileHeader(displayName: String, organizationName: String, roleLabel: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(64.dp).background(BpmPlayColors.action.soft, CircleShape).border(BpmPlayBorder, BpmPlayColors.navBorder, CircleShape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(initialsOf(displayName), style = BpmPlayType.h1.copy(fontSize = 22.sp), color = BpmPlayColors.action.face)
        }
        Column(Modifier.weight(1f)) {
            Text(displayName.ifBlank { "Mi perfil" }, style = BpmPlayType.h1.copy(fontSize = 24.sp), color = BpmPlayColors.ink,
                maxLines = pe.bpm.core.designsystem.bpmTitleLines(), overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            Text(if (organizationName.isBlank()) roleLabel else "$roleLabel · $organizationName", style = BpmPlayType.sub, color = BpmPlayColors.muted, maxLines = pe.bpm.core.designsystem.bpmTitleLines(), overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Tarjeta de racha: el título («Racha de 6 días»), la llama y la semana L–D con los días activos en coral. */
@Composable
private fun StreakCard(currentDays: Int, activeToday: Boolean, lastActivityDate: String?) {
    val today = remember { deviceToday(Clock.System.now().toEpochMilliseconds()) }
    val week = remember(currentDays, activeToday, lastActivityDate, today) { streakWeek(currentDays, activeToday, lastActivityDate, today) }
    BpmPlayCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(streakTitle(currentDays), style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.weight(1f).semantics { heading() })
            BpmIcon(BpmSymbol.Flame, tint = BpmPlayColors.streak.face, modifier = Modifier.size(28.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            week.forEach { day ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = day.description }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).then(when {
                        day.active -> Modifier.background(BpmPlayColors.streak.face)
                        day.today -> Modifier.background(Color.White).border(3.dp, BpmPlayColors.streak.face, CircleShape)
                        else -> Modifier.background(BpmPlayColors.lock)
                    }), contentAlignment = Alignment.Center) {
                        if (day.active) BpmIcon(BpmSymbol.Check, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    Text(day.letter, style = BpmPlayType.label, color = if (day.today) BpmPlayColors.streak.shadow else BpmPlayColors.muted)
                }
            }
        }
    }
}

/** Rejilla de 3 columnas (2 si son cuatro) de insignias grandes; tocar una muestra debajo su descripción o cómo ganarla. */
@Composable
private fun BadgeGrid(badges: List<Achievement>) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    if (badges.isEmpty()) {
        Text("Todavía no hay insignias para ganar.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
        return
    }
    // Con cuatro insignias, 4 en una fila (2 × 2 en teléfono): una tercera columna dejaría una insignia sola en la segunda fila.
    val columns = if (badges.size == 4) (if (currentWindowClass == BpmWindowClass.Compact) 2 else 4) else 3
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        badges.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { badge ->
                    BadgeTile(badge, badge.id == selectedId, { selectedId = if (selectedId == badge.id) null else badge.id }, Modifier.weight(1f))
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        badges.firstOrNull { it.id == selectedId }?.let { BadgeDetail(it) }
    }
}

/** Color de una insignia ganada según lo que premia (curso dorado, racha coral, puntaje perfecto verde). */
private fun badgeShade(achievement: Achievement): BpmPlayShade = when (achievement.criterion) {
    AchievementCriterion.COURSES_COMPLETED -> BpmPlayColors.gold
    AchievementCriterion.STREAK_DAYS -> BpmPlayColors.streak
    AchievementCriterion.PERFECT_SCORE -> BpmPlayColors.correct
}

/** Insignia de 68 dp (`.badge`): color si está ganada, gris con candado si falta; área táctil de toda la columna. */
@Composable
private fun BadgeTile(badge: Achievement, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val earned = badge.awardedAt != null
    val shade = badgeShade(badge)
    val shape = RoundedCornerShape(20.dp)
    val description = if (earned) "${badge.name}, ganada" else "${badge.name}, bloqueada. ${badgeCriterion(badge)}"
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = BpmSpacing.touch)
            .then(if (selected) Modifier.background(BpmPlayColors.action.soft) else Modifier)
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.bpmRelief(if (earned) shade.shadow else BpmPlayColors.lockShadow, shape, 5.dp).size(68.dp).clip(shape)
            .background(if (earned) shade.face else BpmPlayColors.lock), contentAlignment = Alignment.Center) {
            BpmIcon(if (earned) badgeSymbol(badge) else BpmSymbol.Lock, tint = if (earned) Color.White else BpmPlayColors.lockIcon, modifier = Modifier.size(32.dp))
        }
        Text(badge.name, style = BpmPlayType.rowDetail, color = if (earned) BpmPlayColors.ink else BpmPlayColors.muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Detalle de la insignia tocada: su descripción y, si falta, cómo ganarla; si está ganada, cuándo. */
@Composable
private fun BadgeDetail(badge: Achievement) {
    val earned = badge.awardedAt != null
    BpmPlayCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(badge.name, style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.weight(1f))
            BpmPlayChip(if (earned) "Ganada" else "Bloqueada", if (earned) BpmPlayTone.Correct else BpmPlayTone.Action)
        }
        if (badge.description.isNotBlank()) Text(badge.description, style = BpmPlayType.sub, color = BpmPlayColors.muted)
        Text(if (earned) "Ganada el ${readableDate(badge.awardedAt)} · +${badge.points} puntos" else "Cómo ganarla: ${badgeCriterion(badge)} · +${badge.points} puntos",
            style = BpmPlayType.sub, color = BpmPlayColors.ink)
    }
}

/** Constancias de los cursos completados; sin ninguna, dice cuándo aparecerán y lleva a Aprender. */
@Composable
private fun CertificatesCard(courses: List<MyAssignment>, loaded: Boolean, onOpenCourse: (String) -> Unit, onLearn: () -> Unit) {
    if (!loaded) return
    BpmPlayCard {
        Text("Constancias", style = BpmPlayType.h2, color = BpmPlayColors.ink, modifier = Modifier.semantics { heading() })
        if (courses.isEmpty()) {
            Text("Cuando completes un curso, su constancia aparecerá aquí.", style = BpmPlayType.sub, color = BpmPlayColors.muted)
            BpmChunkyButton("Ir a Aprender", onLearn, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        } else {
            courses.take(CERTIFICATE_LIMIT).forEach { assignment ->
                val date = readableDate(assignment.completedAt)
                Row(
                    Modifier.fillMaxWidth().defaultMinSize(minHeight = BpmSpacing.touch).clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = { onOpenCourse(assignment.course.id) })
                        .semantics(mergeDescendants = true) { contentDescription = "Constancia: ${assignment.course.title}" + if (date.isNotEmpty()) ", completado el $date" else "" },
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).background(BpmPlayColors.gold.soft, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        BpmIcon(BpmSymbol.Award, tint = BpmPlayColors.gold.ink, modifier = Modifier.size(24.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(assignment.course.title, style = BpmPlayType.rowTitle, color = BpmPlayColors.ink, maxLines = pe.bpm.core.designsystem.bpmTitleLines(), overflow = TextOverflow.Ellipsis)
                        if (date.isNotEmpty()) Text("Completado el $date", style = BpmPlayType.rowDetail, color = BpmPlayColors.muted)
                    }
                    BpmIcon(BpmSymbol.ChevronRight, tint = BpmPlayColors.muted, modifier = Modifier.size(20.dp))
                }
            }
            if (courses.size > CERTIFICATE_LIMIT) BpmChunkyButton("Ver todas en Aprender", onLearn, Modifier.fillMaxWidth(), tone = BpmChunkyTone.White)
        }
    }
}

/** Entrada a «Ajustes»: el Perfil muestra solo quién eres; accesibilidad, sonido, dispositivos y cerrar sesión viven en Ajustes. */
@Composable
internal fun SettingsEntryCard(onSettings: () -> Unit) {
    BpmPlayCard {
        BpmSettingLink("Ajustes", "Tamaño del texto, contraste, «Escuchar», sonido, dispositivos y cerrar sesión.", onSettings, symbol = BpmSymbol.Settings)
        pe.bpm.features.research.presentation.StudyLinkRow()
    }
}
