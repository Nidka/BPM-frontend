package pe.bpm.core.presentation

import kotlin.time.Instant

/** Fecha legible en la zona del dispositivo; las API y el almacenamiento conservan UTC. */
fun displayDateTime(value: String): String = localDateTime(Instant.parse(value).toEpochMilliseconds())
internal expect fun localDateTime(epochMilliseconds: Long): String

/** Minutos transcurridos desde la medianoche en la zona del dispositivo (0–1439); alimenta «Ahora» del selector de hora. */
internal expect fun localMinutesOfDay(epochMilliseconds: Long): Int
