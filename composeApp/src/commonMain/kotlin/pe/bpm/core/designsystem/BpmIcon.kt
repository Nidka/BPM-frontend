package pe.bpm.core.designsystem

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import pe.bpm.resources.*

/** Recursos Lucide del prototipo, con el mismo tamaño y trazo en todas las plataformas. */
enum class BpmSymbol(internal val resource: DrawableResource) {
    Home(Res.drawable.ic_house), Learn(Res.drawable.ic_book_open),
    Control(Res.drawable.ic_clipboard_check), Team(Res.drawable.ic_users),
    Company(Res.drawable.ic_building), Account(Res.drawable.ic_user_round),
    Sprout(Res.drawable.ic_sprout), Refresh(Res.drawable.ic_refresh),
    ChevronDown(Res.drawable.ic_chevron_down), Back(Res.drawable.ic_arrow_left),
    Logout(Res.drawable.ic_log_out), Check(Res.drawable.ic_check),
    Clock(Res.drawable.ic_clock), Alert(Res.drawable.ic_circle_alert),
}

/** La etiqueta pertenece al control; los íconos decorativos reciben una descripción nula. */
@Composable
fun BpmIcon(symbol: BpmSymbol, contentDescription: String? = null,
    modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(symbol.resource), contentDescription, modifier.size(24.dp), tint)
}
