package pe.bpm.core.designsystem

import kotlin.time.Clock

/**
 * Fechas civiles `AAAA-MM-DD` sin depender de la zona horaria ni de librerías: lo mínimo que necesita [BpmDatePicker] (días del mes,
 * día de la semana, sumar días y escribirla en español). Las funciones son puras y se prueban en `BpmCalendarTest`.
 */
data class CivilDate(val year: Int, val month: Int, val day: Int) : Comparable<CivilDate> {
    override fun compareTo(other: CivilDate) = compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    /** `2026-11-05`; sin `String.format` (no existe en iOS). */
    val iso: String get() = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    /** «5 de noviembre de 2026». */
    val spoken: String get() = "$day de ${BpmCalendar.monthNames[month - 1]} de $year"

    /** 0 = lunes … 6 = domingo. */
    val weekday: Int get() = (((BpmCalendar.epochDay(this) + 3) % 7) + 7).toInt() % 7

    fun plusDays(n: Int): CivilDate = BpmCalendar.fromEpochDay(BpmCalendar.epochDay(this) + n)
}

object BpmCalendar {
    val monthNames = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
    val weekdayInitials = listOf("L", "M", "M", "J", "V", "S", "D")
    val weekdayNames = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

    fun isLeap(year: Int) = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeap(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Lee `AAAA-MM-DD` (se ignora lo que venga después de la fecha); nulo si no es una fecha real. */
    fun parse(text: String): CivilDate? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(text.trim()) ?: return null
        val (y, mo, d) = m.destructured
        val date = CivilDate(y.toInt(), mo.toInt(), d.toInt())
        return date.takeIf { it.month in 1..12 && it.day in 1..daysInMonth(it.year, it.month) }
    }

    /** Días desde 1970-01-01 (algoritmo de Howard Hinnant). */
    fun epochDay(date: CivilDate): Long {
        val y = if (date.month <= 2) date.year - 1 else date.year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val mp = (date.month + 9) % 12
        val doy = (153 * mp + 2) / 5 + date.day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era.toLong() * 146097 + doe - 719468
    }

    fun fromEpochDay(days: Long): CivilDate {
        val z = days + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = (z - era * 146097).toInt()
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = (yoe + era * 400).toInt() + if (month <= 2) 1 else 0
        return CivilDate(year, month, day)
    }

    /** Hoy en la zona del dispositivo (a las 19:00 en Lima sigue siendo el mismo día, aunque en UTC ya sea mañana). */
    fun today(): CivilDate = Clock.System.now().toEpochMilliseconds().let { fromEpochDay(localMillis(it).floorDiv(86_400_000L)) }

    /** Milisegundos «de reloj de pared» del dispositivo: UTC más el desfase de su zona. */
    internal fun localMillis(epochMilliseconds: Long): Long = epochMilliseconds + pe.bpm.core.presentation.localUtcOffsetMinutes(epochMilliseconds) * 60_000L

    /** Primer día de la cuadrícula de un mes: los huecos antes del día 1 (lunes = 0). */
    fun leadingBlanks(year: Int, month: Int): Int = CivilDate(year, month, 1).weekday

    fun nextMonth(year: Int, month: Int) = if (month == 12) year + 1 to 1 else year to month + 1
    fun previousMonth(year: Int, month: Int) = if (month == 1) year - 1 to 12 else year to month - 1
}
