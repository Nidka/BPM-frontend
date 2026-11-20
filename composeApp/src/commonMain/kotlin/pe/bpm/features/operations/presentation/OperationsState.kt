package pe.bpm.features.operations.presentation

import pe.bpm.core.presentation.DraftSaveStatus
import pe.bpm.features.operations.domain.*

data class OperationsState(
    val userId: String? = null,
    val organizationId: String? = null,
    val role: String = "",
    val siteId: String? = null,
    val resource: OperationsResource = OperationsResource.SUPPLIERS,
    val rows: List<OperationsEntry> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val failure: OperationsFailure? = null,
    val fields: Map<String, String> = emptyMap(),
    val query: String = "",
    val page: Int = 1,
    val previous: List<String> = emptyList(),
    val token: String = "",
    val nextToken: String = "",
    val form: OperationsForm? = null,
    val dirty: Boolean = false,
    val pending: PendingOperation? = null,
    val review: Boolean = false,
    val localStatus: DraftSaveStatus = DraftSaveStatus.IDLE,
    val saved: OperationsEntry? = null,
    val detail: OperationsEntry? = null,
    /** Aviso positivo, p. ej. «Guardado en este dispositivo; se enviará al conectarte». */
    val notice: String? = null,
    val picker: OperationsResource? = null,
    val pickerRows: List<OperationsEntry> = emptyList(),
    val pickerQuery: String = "",
    val pickerPage: Int = 1,
    val pickerPrevious: List<String> = emptyList(),
    val pickerToken: String = "",
    val pickerNext: String = "",
    val pickerLoading: Boolean = false,
    val products: List<Product> = emptyList(),
    val selectedPlan: CleaningPlan? = null,
    val shifts: List<Shift> = emptyList(),
    val shiftsFailed: Boolean = false,
    /** Planes activos de la sede con su estado de hoy (`view=today`), para la pestaña «Por hacer» del personal. */
    val todayPlans: List<CleaningPlan> = emptyList(),
    val todayLoading: Boolean = false,
    val todayLoaded: Boolean = false,
    val todayFailure: OperationsFailure? = null,
) {
    val editable
        get() =
            form != null &&
                !busy &&
                !loading &&
                pending == null &&
                saved == null &&
                failure !in setOf(OperationsFailure.SESSION, OperationsFailure.DENIED)

    val saving
        get() = busy || localStatus == DraftSaveStatus.SAVING
}
