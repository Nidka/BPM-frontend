package pe.bpm.features.reports.presentation

import pe.bpm.features.reports.domain.ReportFailure

/** Mensajes públicos y sanitizados; nunca se muestra texto del servidor. */
fun reportFailureMessage(failure: ReportFailure, fields: Map<String, String> = emptyMap()): String = when (failure) {
    ReportFailure.CONNECTION -> "No pudimos conectarnos. Revisa tu conexión e inténtalo de nuevo."
    ReportFailure.SESSION -> "Tu sesión venció. Inicia sesión nuevamente."
    ReportFailure.DENIED -> "Tu cuenta no tiene permiso para ver esta información."
    ReportFailure.NOT_FOUND -> "No encontramos la sede o el curso indicado. Elige otra opción."
    ReportFailure.INVALID_PERIOD -> "Revisa el período: las fechas deben ser válidas y respetar el máximo de días."
    ReportFailure.INVALID -> "Revisa los datos ingresados."
    ReportFailure.NO_DATA -> "No hay controles finalizados en ese período."
    ReportFailure.THRESHOLD_NOT_MET -> {
        val average = fields["averageCompliance"]
        val threshold = fields["threshold"]
        if (average != null && threshold != null) "El cumplimiento del período ($average %) no alcanza el umbral configurado ($threshold %). No se emitió la constancia."
        else "El cumplimiento del período no alcanza el umbral configurado. No se emitió la constancia."
    }
    ReportFailure.EXPORT_TOO_LARGE -> "El período tiene demasiados registros para exportar. Acórtalo o filtra por sede."
    ReportFailure.CERTIFICATE_NOT_FOUND -> "No encontramos una constancia con ese código."
    ReportFailure.RATE_LIMITED -> "Hay demasiadas solicitudes. Espera un momento y vuelve a intentar."
    ReportFailure.UNAVAILABLE -> "El servicio no está disponible por ahora. Inténtalo más tarde."
    ReportFailure.INVALID_RESPONSE -> "Recibimos una respuesta inesperada. Inténtalo de nuevo."
    ReportFailure.INCOMPLETE_DOWNLOAD -> "La descarga quedó incompleta. Vuelve a exportar."
}

/** Porcentaje con un decimal; "Sin datos" cuando no hubo ejecuciones. */
fun formatPercent(value: Double?): String =
    if (value == null) "Sin datos" else "${kotlin.math.round(value * 10) / 10} %"

/** `2026-11-02` -> `02/11` para ejes y listas compactas. */
fun shortDate(date: String): String = if (date.length == 10) "${date.substring(8, 10)}/${date.substring(5, 7)}" else date

/** `2026-11-02` -> `02/11/2026`. */
fun longDate(date: String): String = if (date.length >= 10) "${date.substring(8, 10)}/${date.substring(5, 7)}/${date.substring(0, 4)}" else date

fun roleName(role: String): String = when (role) {
    "ADMIN" -> "Administración"
    "CHEF" -> "Jefatura de sede"
    "WORKER" -> "Personal"
    else -> role
}
