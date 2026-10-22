package pe.bpm.core.presentation

import kotlin.time.Instant

/** Fecha legible en la zona del dispositivo; las API y el almacenamiento conservan UTC. */
fun displayDateTime(value: String): String = localDateTime(Instant.parse(value).toEpochMilliseconds())
internal expect fun localDateTime(epochMilliseconds: Long): String
