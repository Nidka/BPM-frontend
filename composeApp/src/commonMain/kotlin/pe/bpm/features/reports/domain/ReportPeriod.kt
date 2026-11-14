package pe.bpm.features.reports.domain

/** Fechas de calendario AAAA-MM-DD sin dependencias externas (algoritmo de días civiles). */
object ReportDates {
    /** Perú no usa horario de verano: UTC-5 todo el año. Es la zona por defecto del backend. */
    private const val LIMA_OFFSET_MILLIS = -5L * 3_600_000L

    /** Devuelve el día de época o nulo si el texto no es una fecha real. */
    fun parse(value: String): Long? {
        if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return null
        val year = value.substring(0, 4).toInt()
        val month = value.substring(5, 7).toInt()
        val day = value.substring(8, 10).toInt()
        if (month !in 1..12 || day < 1 || day > daysInMonth(year, month)) return null
        return daysFromCivil(year, month, day)
    }

    fun format(epochDay: Long): String {
        val z = epochDay + 719468
        val era = z.floorDiv(146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 3 else mp - 9
        val year = yoe + era * 400 + if (month <= 2) 1 else 0
        return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
    }

    /** Fecha local de Lima para un instante en milisegundos UTC. */
    fun today(nowMillis: Long): Long = (nowMillis + LIMA_OFFSET_MILLIS).floorDiv(86_400_000L)

    /** Primer día del mes del [epochDay]. */
    fun monthStart(epochDay: Long): Long = parse(format(epochDay).substring(0, 8) + "01")!!

    private fun daysInMonth(year: Int, month: Int) = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = y.floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }
}

enum class PeriodError { MISSING, INVALID, ORDER, TOO_LONG }

/** Límites del contrato: cumplimiento 92 días; resumen, exportación y constancias 366 días. */
object ReportPeriodRules {
    const val COMPLIANCE_MAX_DAYS = 92
    const val SUMMARY_MAX_DAYS = 366

    /** Valida un rango inclusivo de fechas locales; nulo significa válido. */
    fun validate(from: String, to: String, maxDays: Int): PeriodError? {
        if (from.isBlank() || to.isBlank()) return PeriodError.MISSING
        val start = ReportDates.parse(from.trim()) ?: return PeriodError.INVALID
        val end = ReportDates.parse(to.trim()) ?: return PeriodError.INVALID
        if (end < start) return PeriodError.ORDER
        if (end - start + 1 > maxDays) return PeriodError.TOO_LONG
        return null
    }

    fun message(error: PeriodError, maxDays: Int): String = when (error) {
        PeriodError.MISSING -> "Indica la fecha inicial y la final."
        PeriodError.INVALID -> "Escribe las fechas como AAAA-MM-DD, por ejemplo 2026-11-30."
        PeriodError.ORDER -> "La fecha final no puede ser anterior a la inicial."
        PeriodError.TOO_LONG -> "El período puede abarcar como máximo $maxDays días."
    }

    /** Período de los últimos [days] días terminando en [today] (inclusive). */
    fun lastDays(today: Long, days: Int): Pair<String, String> = ReportDates.format(today - days + 1) to ReportDates.format(today)
}
