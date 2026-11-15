package pe.bpm.core.offline

/**
 * Decisión sobre cerrar sesión según la Outbox de la cuenta activa. Un celular compartido nunca debe
 * perder cambios que todavía se pueden enviar: cerrar sesión borra los datos personales del dispositivo.
 */
sealed interface LogoutGate {
    /** No queda nada pendiente: se puede cerrar sesión y borrar los datos personales. */
    data object Clear : LogoutGate

    /**
     * Hay [unsent] cambios sin enviar (PENDING o SENDING, incluidos los que agotaron reintentos): la persona
     * debe conectarse y sincronizar; no se ofrece descartarlos.
     */
    data class Blocked(val unsent: Int) : LogoutGate

    /**
     * Solo quedan cambios en CONFLICTO o RECHAZADOS ([operationIds]) y los que dependen de ellos
     * ([dependents]). Se pueden descartar de forma explícita, con confirmación, para cerrar sesión.
     */
    data class NeedsDiscard(val operationIds: List<String>, val dependents: Int) : LogoutGate
}

object LogoutPolicy {
    /** Evalúa las operaciones de la cuenta activa (las enviadas no cuentan). */
    fun evaluate(operations: List<OutboxOperation>): LogoutGate {
        val live = operations.filter { !it.resolved }
        val failed = live.filter { it.status == OutboxStatus.CONFLICT || it.status == OutboxStatus.REJECTED }
        // Los dependientes de una operación fallida esperan y se descartan con ella: no bloquean por sí solos.
        val doomed = HashSet(failed.map { it.id })
        var grew = true
        while (grew) {
            grew = false
            for (op in live) if (op.id !in doomed && op.dependsOn.any { it in doomed }) { doomed += op.id; grew = true }
        }
        val unsent = live.count { (it.status == OutboxStatus.PENDING || it.status == OutboxStatus.SENDING) && it.id !in doomed }
        return when {
            unsent > 0 -> LogoutGate.Blocked(unsent)
            failed.isNotEmpty() -> LogoutGate.NeedsDiscard(failed.map { it.id }, doomed.size - failed.size)
            else -> LogoutGate.Clear
        }
    }

    /** Texto visible cuando hay cambios por enviar. */
    fun blockedMessage(unsent: Int): String =
        "Tienes $unsent ${if (unsent == 1) "cambio" else "cambios"} sin enviar. Conéctate y sincroniza antes de salir"
}
