package pe.bpm.features.training.domain

/** Un subtítulo: texto visible entre [startMs] y [endMs] (milisegundos desde el inicio del video). */
data class CaptionCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Subtítulos WebVTT de una lección (RF-45). La validación replica la del servidor (NormalizeCaptions) para
 * avisar antes de guardar; el servidor vuelve a validar. Sin dependencias de plataforma.
 */
object WebVtt {
    /** Igual que el servidor: hasta 100 000 caracteres. */
    const val MAX_LENGTH = 100_000

    private val timing = Regex("""^((?:\d{2,}:)?\d{2}:\d{2}\.\d{3})\s+-->\s+((?:\d{2,}:)?\d{2}:\d{2}\.\d{3})""")

    /** Quita la marca BOM, unifica los saltos de línea y los espacios de los extremos. */
    fun normalize(raw: String): String =
        raw.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').trim()

    /** Mensaje para la persona si [raw] no es un WebVTT válido; `null` si es válido o está vacío (sin subtítulos). */
    fun validationIssue(raw: String): String? {
        val text = normalize(raw)
        return when {
            text.isEmpty() -> null
            text.length > MAX_LENGTH -> "Los subtítulos admiten hasta $MAX_LENGTH caracteres."
            !text.startsWith("WEBVTT") -> "Usa un archivo de subtítulos WebVTT: debe empezar con WEBVTT."
            text.lines().none { timing.containsMatchIn(it.trim()) } ->
                "Los subtítulos no tienen tiempos válidos (por ejemplo 00:00:01.000 --> 00:00:04.000)."
            else -> null
        }
    }

    /** Lee los subtítulos en orden; ignora cabecera, notas y bloques sin tiempos. Las etiquetas (<i>, <v>) se quitan. */
    fun parse(raw: String): List<CaptionCue> {
        val text = normalize(raw)
        if (!text.startsWith("WEBVTT")) return emptyList()
        return text.split(Regex("\n\\s*\n")).mapNotNull { block ->
            val lines = block.lines()
            val index = lines.indexOfFirst { timing.containsMatchIn(it.trim()) }
            if (index < 0) return@mapNotNull null
            val match = timing.find(lines[index].trim()) ?: return@mapNotNull null
            val start = millis(match.groupValues[1]); val end = millis(match.groupValues[2])
            val body = lines.drop(index + 1).joinToString("\n") { it.replace(Regex("<[^>]+>"), "").trim() }.trim()
            if (body.isEmpty() || end <= start) null else CaptionCue(start, end, body)
        }.sortedBy { it.startMs }
    }

    /** Subtítulo visible en [positionMs], o `null` entre subtítulos. */
    fun activeCue(cues: List<CaptionCue>, positionMs: Long): CaptionCue? =
        cues.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }

    /** Transcripción legible: el texto de todos los subtítulos, en orden y sin repetir líneas seguidas. */
    fun transcript(cues: List<CaptionCue>): String =
        cues.map { it.text.replace('\n', ' ') }.fold(mutableListOf<String>()) { acc, line -> if (acc.lastOrNull() != line) acc += line; acc }
            .joinToString("\n")

    private fun millis(value: String): Long {
        val parts = value.split(':')
        val (hours, minutes, seconds) = if (parts.size == 3) Triple(parts[0], parts[1], parts[2]) else Triple("0", parts[0], parts[1])
        val (whole, fraction) = seconds.split('.').let { it[0] to it[1] }
        return ((hours.toLong() * 60 + minutes.toLong()) * 60 + whole.toLong()) * 1000 + fraction.toLong()
    }
}
