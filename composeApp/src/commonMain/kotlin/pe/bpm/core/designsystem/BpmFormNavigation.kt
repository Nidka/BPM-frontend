package pe.bpm.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester

/** Orden de campos del formulario. «Siguiente» omite botones y controles de selección. */
class BpmFormNavigation internal constructor(private val fields: Map<String, BpmFieldNavigation>) {
    operator fun get(id: String): BpmFieldNavigation = fields.getValue(id)
    fun focusFirstError(ids: Collection<String>) {
        fields.entries.firstOrNull { it.key in ids }?.value?.requester?.requestFocus()
    }
}

class BpmFieldNavigation internal constructor(
    internal val requester: FocusRequester,
    internal val next: FocusRequester?,
)

/** Las claves identifican campos visibles, en su orden de lectura, también en formularios dinámicos. */
@Composable
fun rememberBpmFormNavigation(ids: List<String>): BpmFormNavigation {
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    return remember(ids.toList()) {
        require(ids.distinct().size == ids.size)
        requesters.keys.retainAll(ids.toSet())
        ids.forEach { requesters.getOrPut(it) { FocusRequester() } }
        BpmFormNavigation(ids.mapIndexed { index, id ->
            id to BpmFieldNavigation(requesters.getValue(id), ids.getOrNull(index + 1)?.let(requesters::getValue))
        }.toMap())
    }
}
