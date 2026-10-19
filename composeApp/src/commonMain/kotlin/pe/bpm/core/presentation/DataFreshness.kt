package pe.bpm.core.presentation

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Mide antigüedad sin sondeo ni dependencia del reloj civil del dispositivo. */
class DataFreshness(private val source: TimeSource = TimeSource.Monotonic, private val lifetime: Duration = 2.minutes) {
    private var loadedAt: TimeMark? = null
    val stale: Boolean get() = loadedAt?.elapsedNow()?.let { it >= lifetime } ?: true
    fun loaded() { loadedAt = source.markNow() }
    fun reset() { loadedAt = null }
}
