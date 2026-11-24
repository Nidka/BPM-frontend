package pe.bpm.core.presentation

import kotlin.time.Instant

/** Fecha legible en la zona del dispositivo; las API y el almacenamiento conservan UTC. */
fun displayDateTime(value: String): String = localDateTime(Instant.parse(value).toEpochMilliseconds())
internal expect fun localDateTime(epochMilliseconds: Long): String

/** Desfase de la zona del dispositivo respecto de UTC, en minutos (Lima = -300); alimenta «Hoy» y «Ahora» de los selectores. */
internal expect fun localUtcOffsetMinutes(epochMilliseconds: Long): Int
