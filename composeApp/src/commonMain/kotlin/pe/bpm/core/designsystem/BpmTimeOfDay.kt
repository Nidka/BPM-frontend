package pe.bpm.core.designsystem

/**
 * Hora del día `HH:mm` (24 horas) sin zona: lo mínimo que necesita [BpmTimePicker]. Funciones puras, probadas en `BpmTimeOfDayTest`.
 * Sin `String.format` (no existe en iOS).
 */
data class TimeOfDay(val hour: Int, val minute: Int) : Comparable<TimeOfDay> {
    override fun compareTo(other: TimeOfDay) = compareValuesBy(this, other, { it.hour }, { it.minute })

    /** `08:05`. */
    val text: String get() = BpmTime.two(hour) + ":" + BpmTime.two(minute)

    /** Lo que ve la persona: reloj de 12 horas, «8:30 p. m.» (el valor guardado sigue siendo `HH:mm`). */
    val display: String get() = pe.bpm.core.presentation.displayClock(text)

    /** Para el lector de pantalla: «8 y 30 de la mañana», «12 en punto del mediodía». */
    val spoken: String
        get() {
            val h12 = if (hour % 12 == 0) 12 else hour % 12
            val part = when {
                hour == 12 && minute == 0 -> "del mediodía"
                hour == 0 && minute == 0 -> "de la medianoche"
                hour < 12 -> "de la mañana"
                hour < 19 -> "de la tarde"
                else -> "de la noche"
            }
            return (if (minute == 0) "$h12 en punto" else "$h12 y $minute") + " $part"
        }
}

object BpmTime {
    internal fun two(n: Int) = if (n < 10) "0$n" else n.toString()

    /** `HH:mm` o `H:mm` válidos (00:00–23:59) → hora; nulo si no es una hora real. Acepta una hora con segundos detrás («08:30:00»). */
    fun parse(value: String): TimeOfDay? {
        val m = Regex("""^(\d{1,2}):(\d{2})(?::\d{2})?$""").find(value.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) TimeOfDay(h, min) else null
    }

    /** Minutos desde la medianoche local → hora. */
    fun fromMinutes(minutes: Int): TimeOfDay = ((minutes % 1440) + 1440).rem(1440).let { TimeOfDay(it / 60, it % 60) }

    /** La hora actual del dispositivo (zona local), con minutos exactos. */
    fun now(): TimeOfDay = fromMinutes((BpmCalendar.localMillis(kotlin.time.Clock.System.now().toEpochMilliseconds()).mod(86_400_000L) / 60_000L).toInt())

    /** Minutos de la cuadrícula (cada 5). */
    val minuteSteps: List<Int> = (0..55 step 5).toList()
}
