package pe.bpm.features.operations.domain

import pe.bpm.core.domain.*

/** Turno de una sede: horas locales de la sede (no del dispositivo); el fin es excluyente. */
data class Shift(
    val id: String,
    val locationId: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val timezone: String = "",
    val crossesMidnight: Boolean = false,
    val active: Boolean = true,
    val openNow: Boolean = false,
    val rowVersion: Long = 1,
    val createdAt: String = "",
)

data class ShiftInput(
    val id: String,
    val locationId: String,
    val name: String,
    val startTime: String,
    val endTime: String,
)

data class ShiftChange(
    val name: String,
    val startTime: String,
    val endTime: String,
    val rowVersion: Long,
)

object ShiftAccess {
    fun canRead(role: String) = role in setOf("SUPER_ADMIN", "ADMIN", "CHEF", "WORKER")

    fun canManage(role: String) = role in setOf("SUPER_ADMIN", "ADMIN")
}

/** Validación previa en el dispositivo; el servidor sigue siendo la autoridad. */
object ShiftRules {
    private val clock = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    const val MINUTES_PER_DAY = 1440

    fun minutes(value: String): Int? =
        if (clock.matches(value)) value.take(2).toInt() * 60 + value.takeLast(2).toInt() else null

    /** Un turno cruza la medianoche cuando termina antes de lo que empieza (22:00-06:00). */
    fun crossesMidnight(start: String, end: String): Boolean {
        val s = minutes(start) ?: return false
        val e = minutes(end) ?: return false
        return e < s
    }

    private fun segments(start: Int, end: Int): List<IntRange> =
        if (end > start) listOf(start until end)
        else listOf(start until MINUTES_PER_DAY, 0 until end)

    /** Intervalos semiabiertos [inicio, fin) sobre un reloj circular de 24 horas. */
    fun overlaps(startA: String, endA: String, startB: String, endB: String): Boolean {
        val a = minutes(startA) ?: return false
        val b = minutes(endA) ?: return false
        val c = minutes(startB) ?: return false
        val d = minutes(endB) ?: return false
        if (a == b || c == d) return false
        return segments(a, b).any { x ->
            segments(c, d).any { y -> x.first <= y.last && y.first <= x.last }
        }
    }

    fun errors(
        name: String,
        start: String,
        end: String,
        others: List<Shift> = emptyList(),
        ignoreId: String? = null,
    ): Map<String, String> = buildMap {
        InputRules.text(2, 60).error(name)?.let { put("name", it) }
        if (minutes(start) == null) put("startTime", "Usa una hora de inicio como 06:00.")
        if (minutes(end) == null) put("endTime", "Usa una hora de fin como 14:00.")
        if (isEmpty() && start == end) put("endTime", "El fin debe ser distinto del inicio.")
        if (isEmpty()) {
            val active = others.filter { it.active && it.id != ignoreId }
            if (active.any { it.name.trim().equals(name.trim(), ignoreCase = true) })
                put("name", "Ya existe un turno activo con este nombre en la sede.")
            active
                .firstOrNull { overlaps(start, end, it.startTime, it.endTime) }
                ?.let {
                    put(
                        "startTime",
                        "Se cruza con el turno ${it.name} (${it.startTime}-${it.endTime}).",
                    )
                }
        }
    }
}

interface ShiftRepository {
    suspend fun shifts(
        org: String,
        site: String,
        includeInactive: Boolean,
        page: PageRequest,
    ): OperationsResult<Page<Shift>>

    suspend fun create(org: String, input: ShiftInput, key: String): OperationsResult<Shift>

    suspend fun update(
        org: String,
        id: String,
        change: ShiftChange,
        key: String,
    ): OperationsResult<Shift>

    suspend fun deactivate(
        org: String,
        id: String,
        rowVersion: Long,
        key: String,
    ): OperationsResult<Shift>
}

/** Casos de uso de turnos: comprueban el rol y las reglas antes de llegar al transporte. */
class ManageShifts(private val repository: ShiftRepository) {
    /** Lee todas las páginas (una sede tiene pocos turnos) con un tope defensivo. */
    suspend fun list(
        org: String,
        site: String,
        role: String,
        includeInactive: Boolean,
    ): OperationsResult<List<Shift>> {
        if (!ShiftAccess.canRead(role) || includeInactive && !ShiftAccess.canManage(role))
            return OperationsResult.Failure(OperationsFailure.DENIED)
        val all = mutableListOf<Shift>()
        var token = ""
        repeat(MAX_PAGES) {
            when (
                val page =
                    repository.shifts(org, site, includeInactive, PageRequest(token, size = 100))
            ) {
                is OperationsResult.Failure -> return page
                is OperationsResult.Success -> {
                    all += page.value.items
                    if (page.value.nextToken.isEmpty()) return OperationsResult.Success(all)
                    token = page.value.nextToken
                }
            }
        }
        return OperationsResult.Failure(OperationsFailure.INVALID_PAGE)
    }

    suspend fun create(
        org: String,
        role: String,
        input: ShiftInput,
        current: List<Shift>,
        key: String,
    ): OperationsResult<Shift> {
        if (!ShiftAccess.canManage(role)) return OperationsResult.Failure(OperationsFailure.DENIED)
        val errors = ShiftRules.errors(input.name, input.startTime, input.endTime, current)
        if (errors.isNotEmpty())
            return OperationsResult.Failure(OperationsFailure.INVALID, errors)
        return repository.create(org, input.copy(name = input.name.trim()), key)
    }

    suspend fun update(
        org: String,
        role: String,
        id: String,
        change: ShiftChange,
        current: List<Shift>,
        key: String,
    ): OperationsResult<Shift> {
        if (!ShiftAccess.canManage(role)) return OperationsResult.Failure(OperationsFailure.DENIED)
        val errors = ShiftRules.errors(change.name, change.startTime, change.endTime, current, id)
        if (errors.isNotEmpty())
            return OperationsResult.Failure(OperationsFailure.INVALID, errors)
        return repository.update(org, id, change.copy(name = change.name.trim()), key)
    }

    suspend fun deactivate(
        org: String,
        role: String,
        shift: Shift,
        key: String,
    ): OperationsResult<Shift> =
        if (!ShiftAccess.canManage(role)) OperationsResult.Failure(OperationsFailure.DENIED)
        else repository.deactivate(org, shift.id, shift.rowVersion, key)

    private companion object {
        const val MAX_PAGES = 10
    }
}
