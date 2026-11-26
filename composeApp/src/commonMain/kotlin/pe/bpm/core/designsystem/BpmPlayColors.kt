package pe.bpm.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Un matiz del estilo de juego: [face] es el color principal (fondo de botón, borde, trazo), [shadow] el relieve
 * inferior (más oscuro), [soft] el fondo suave de una ficha o fila y [ink] el color de un icono o número sobre [soft].
 */
@Immutable
class BpmPlayShade(val face: Color, val shadow: Color, val soft: Color, val ink: Color = face)

/** Tono semántico de las fichas, filas, contadores y barras del estilo de juego. */
enum class BpmPlayTone { Action, Correct, Error, Gold, Streak }

/**
 * Colores del estilo de juego de Personal (propuesta `personal-v2`, sección «Sistema visual»). Solo los usan las
 * pantallas de Personal; el resto de roles sigue con [BpmTokens]. Los valores son los de la maqueta, sin variantes.
 */
/**
 * Alto contraste: lo enciende la raíz con la preferencia de Accesibilidad. Es un estado de Compose, así que lo que lo lee
 * (los colores de texto de apoyo y bordes de [BpmPlayColors]) se redibuja solo al cambiarlo.
 */
object BpmContrast {
    private val state = androidx.compose.runtime.mutableStateOf(false)
    var high: Boolean
        get() = state.value
        set(value) { state.value = value }
}

object BpmPlayColors {
    /** Acción principal: teal de la marca. */
    val action = BpmPlayShade(Color(0xFF005B59), Color(0xFF003D3C), Color(0xFFE3F1EE))
    /** Correcto, hecho, avance. */
    val correct = BpmPlayShade(Color(0xFF2E9E5B), Color(0xFF1E7442), Color(0xFFE2F5E8))
    /** Error y «Casi». */
    val error = BpmPlayShade(Color(0xFFC9382D), Color(0xFF962A21), Color(0xFFFDE7E4))
    /** Logro, puntos y lección hecha. El texto sobre fondo suave usa el dorado oscuro. */
    val gold = BpmPlayShade(Color(0xFFF5B700), Color(0xFFC48F00), Color(0xFFFFF4CC), ink = Color(0xFFC48F00))
    /** Texto dorado sobre el fondo suave dorado (contraste ≥ 4,5:1; [gold]`.ink` queda para iconos y números grandes). */
    val goldText = Color(0xFF7A5800)
    /** Texto oscuro sobre la franja dorada de una ficha de resultado. */
    val goldBandText = Color(0xFF3D2C00)
    /** Borde de un aviso sobre fondo suave dorado. */
    val goldBorder = Color(0xFFF1DB8C)
    /** Racha y «Reportar». */
    val streak = BpmPlayShade(Color(0xFFED5A3B), Color(0xFFB83E24), Color(0xFFFFE6DF))

    /** Bloqueado o deshabilitado: relleno y relieve. */
    val lock get() = if (BpmContrast.high) Color(0xFFD3DADA) else Color(0xFFE5EAEA)
    val lockShadow = Color(0xFFC7CFCF)
    /** Texto de un botón deshabilitado y trazo de un icono bloqueado. */
    val lockText get() = if (BpmContrast.high) Color(0xFF5A676B) else Color(0xFF9AA6A9)
    val lockIcon get() = if (BpmContrast.high) Color(0xFF5A676B) else Color(0xFFA9B4B6)

    /** Borde y relieve neutros de tarjetas, filas y opciones. */
    val line get() = if (BpmContrast.high) Color(0xFF7D8B90) else Color(0xFFDCE3E3)
    /** Texto principal y de apoyo de las maquetas. */
    val ink = Color(0xFF233F4D)
    val muted get() = if (BpmContrast.high) Color(0xFF2F3E44) else Color(0xFF5E6E73)
    /** Etiqueta inactiva de la navegación. */
    val navInactive get() = if (BpmContrast.high) Color(0xFF2F3E44) else Color(0xFF66767B)
    /** Borde del elemento seleccionado de la navegación y de las opciones elegidas. */
    val navBorder = Color(0xFF9DCBC2)
    val selectedBorder = Color(0xFF7FC3B6)
    val correctBorder = Color(0xFF8FD4A9)
    val wrongBorder = Color(0xFFF2A49B)
    val doneRowBorder = Color(0xFFB8E2C6)
    /** Aro alrededor del nodo actual del camino. */
    val pathHalo = Color(0xFFCFE6E0)
    /** Textos de la franja de respuesta. */
    val feedbackCorrectText = Color(0xFF25603D)
    val feedbackWrongText = Color(0xFF8C2A21)
    /** Colores de los módulos del camino (verde azulado, morado, azul). */
    val unitPurple = Color(0xFF7A4A9E)
    val unitBlue = Color(0xFF2C6E9B)
    val unitColors: List<Color> get() = listOf(action.face, unitPurple, unitBlue)

    /** Color de un texto pequeño de [tone] sobre blanco o sobre su fondo suave (contraste ≥ 4,5:1). */
    fun textOn(tone: BpmPlayTone): Color = if (tone == BpmPlayTone.Gold) goldText else shade(tone).shadow

    fun shade(tone: BpmPlayTone): BpmPlayShade = when (tone) {
        BpmPlayTone.Action -> action
        BpmPlayTone.Correct -> correct
        BpmPlayTone.Error -> error
        BpmPlayTone.Gold -> gold
        BpmPlayTone.Streak -> streak
    }
}
