package pe.bpm.core.presentation

import kotlin.time.Instant

/**
 * Único formateador de fechas y horas visibles de la aplicación: día/mes/año y reloj de 12 horas con «a. m./p. m.»,
 * sin abreviatura de zona («25/11/2026 · 8:41 p. m.»). Es el mismo código en Android, iOS, web y escritorio: solo el
 * desfase de la zona del dispositivo ([localUtcOffsetMinutes]) depende de la plataforma. Las API y el almacenamiento conservan UTC.
 */
fun displayDateTime(value: String): String = localDateTime(Instant.parse(value).toEpochMilliseconds())

internal fun localDateTime(epochMilliseconds: Long): String = formatDateTime(epochMilliseconds, localUtcOffsetMinutes(epochMilliseconds))

/**
 * Fecha «03/12/2026» de un instante visto en la zona del dispositivo (un plazo que vence a las 23:59 de Lima cae a las 04:59 UTC
 * del día siguiente: leer el día en UTC lo adelantaba un día). Una fecha sin hora «2026-12-03» solo se reordena.
 */
fun displayDay(value: String?): String {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return ""
    if ('T' !in text) return Regex("(\\d{4})-(\\d{2})-(\\d{2})").matchEntire(text)?.destructured?.let { (y, m, d) -> "$d/$m/$y" } ?: text
    return runCatching { displayDateTime(text).substringBefore(" · ") }.getOrDefault(text.substringBefore('T'))
}

/** Hora de reloj de 12 horas de un instante, p. ej. «8:41 p. m.». */
fun displayClockOf(value: String): String {
    val millis = Instant.parse(value).toEpochMilliseconds()
    return formatClock(millis, localUtcOffsetMinutes(millis))
}

/**
 * Convierte una hora de 24 horas «HH:mm» (como la guarda la API: horarios de turnos, límites, reglas) a «8:41 p. m.».
 * Un valor que no tenga ese formato se devuelve igual para no ocultar datos.
 */
fun displayClock(hhmm: String): String {
    val parts = hhmm.trim().split(':')
    if (parts.size < 2) return hhmm
    val hour = parts[0].toIntOrNull()?.takeIf { it in 0..23 } ?: return hhmm
    val minute = parts[1].take(2).toIntOrNull()?.takeIf { it in 0..59 } ?: return hhmm
    return clock12(hour, minute)
}

/** Desfase de la zona del dispositivo respecto de UTC, en minutos (Lima = -300); alimenta «Hoy» y «Ahora» de los selectores. */
internal expect fun localUtcOffsetMinutes(epochMilliseconds: Long): Int

/** «25/11/2026 · 8:41 p. m.» para el instante [epochMilliseconds] visto con [offsetMinutes] respecto de UTC. */
internal fun formatDateTime(epochMilliseconds: Long, offsetMinutes: Int): String {
    val local = epochMilliseconds + offsetMinutes * 60_000L
    val day = local.floorDiv(86_400_000L)
    val (year, month, dom) = civilFromDays(day)
    return "${dom.pad2()}/${month.pad2()}/${year.toString().padStart(4, '0')} · ${formatClock(epochMilliseconds, offsetMinutes)}"
}

internal fun formatClock(epochMilliseconds: Long, offsetMinutes: Int): String {
    val minuteOfDay = (epochMilliseconds + offsetMinutes * 60_000L).floorMod(86_400_000L) / 60_000L
    return clock12((minuteOfDay / 60).toInt(), (minuteOfDay % 60).toInt())
}

private fun clock12(hour: Int, minute: Int): String {
    val twelve = if (hour % 12 == 0) 12 else hour % 12
    return "$twelve:${minute.pad2()} ${if (hour < 12) "a. m." else "p. m."}"
}

private fun Int.pad2() = toString().padStart(2, '0')
private fun Long.floorMod(other: Long): Long = ((this % other) + other) % other

/** Días desde 1970-01-01 → (año, mes, día); algoritmo de Howard Hinnant. */
private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return Triple(year, month, day)
}
