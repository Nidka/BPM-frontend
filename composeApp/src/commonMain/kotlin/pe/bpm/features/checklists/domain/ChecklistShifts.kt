package pe.bpm.features.checklists.domain

/** Estado del turno de una programación según la zona de la sede y el reloj del servidor. */
enum class ShiftState {
    OPEN,
    CLOSED,
    UPCOMING,
}

/** Turno elegible al programar un control diario; las horas son locales de la sede. */
data class ChecklistShiftOption(
    val id: String,
    val name: String,
    val startTime: String,
    val endTime: String,
    val openNow: Boolean = false,
)

sealed interface ShiftOptionsResult {
    data class Success(val shifts: List<ChecklistShiftOption>) : ShiftOptionsResult

    data class Failure(val reason: AssignmentFailure) : ShiftOptionsResult
}

fun interface ChecklistShiftReader {
    suspend fun shifts(organizationId: String, locationId: String): ShiftOptionsResult
}

class LoadChecklistShifts(private val reader: ChecklistShiftReader) {
    suspend operator fun invoke(org: String, locationId: String) =
        reader.shifts(org, locationId)
}
