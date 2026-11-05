package pe.bpm.features.incidents.presentation

/** Rechaza fechas inexistentes/ambiguas y zonas IANA desconocidas; no adivina el desfase. */
internal expect fun incidentDeadline(date: String, time: String, timeZone: String): String?
