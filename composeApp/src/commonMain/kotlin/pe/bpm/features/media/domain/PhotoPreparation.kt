package pe.bpm.features.media.domain

/**
 * Decide cómo dejar una foto lista para subir. Los teléfonos entregan HEIC/WebP, fotos de 50 a 200 megapíxeles o
 * de más de 10 MiB; el servidor solo acepta JPEG/PNG de hasta [MAX_PIXELS] y el tamaño de la política. En lugar de
 * rechazar la foto (y que la persona no pueda adjuntar nada), se reduce y se convierte a JPEG en el dispositivo.
 */
internal object PhotoPreparation {
    /** Lado largo y área a partir de los cuales se reduce aunque pese poco (límite del servidor: 8000 px y 16 MP). */
    const val MAX_SIDE = 6000
    const val MAX_PIXELS = 12_000_000L

    /** Tope de lectura de la foto original: más allá no se intenta convertir (evita agotar la memoria). */
    const val MAX_SOURCE_BYTES = 48 * 1024 * 1024

    /** Calidades JPEG que se prueban de mayor a menor hasta que la foto cabe en el límite. */
    val JPEG_QUALITIES = listOf(88, 80, 72, 64, 56, 48)

    sealed interface Plan {
        /** Se envían los bytes originales sin tocarlos. */
        data object PassThrough : Plan

        /** Se decodifica con [sampleSize] (potencia de 2), se corrige la orientación y se guarda como JPEG. */
        data class Recode(val sampleSize: Int) : Plan

        data object Unsupported : Plan
    }

    fun plan(sniffedMime: String?, sizeBytes: Long, width: Int, height: Int, limit: Int, allowed: Set<String>): Plan {
        if (width <= 0 || height <= 0 || sizeBytes <= 0) return Plan.Unsupported
        val pixels = width.toLong() * height
        val native = sniffedMime in EvidencePhotoSelectionRules.supportedMimeTypes && sniffedMime in allowed
        if (native && sizeBytes <= limit && pixels <= MAX_PIXELS && maxOf(width, height) <= MAX_SIDE) return Plan.PassThrough
        if ("image/jpeg" !in allowed) return Plan.Unsupported
        return Plan.Recode(sampleSizeFor(width, height))
    }

    /** Potencia de 2 más pequeña con la que el área decodificada queda dentro de [MAX_PIXELS] y [MAX_SIDE]. */
    fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > MAX_PIXELS || maxOf(width, height) / sample > MAX_SIDE) sample *= 2
        return sample
    }

    /** Nombre apto para la API (sin rutas ni controles, hasta 100 caracteres) y con la extensión del formato enviado. */
    fun fileName(original: String?, mime: String, fallback: String = "evidencia"): String {
        val extension = if (mime == "image/png") "png" else "jpg"
        val cleaned = original.orEmpty().filter { !it.isISOControl() && it != '/' && it != '\\' }.trim()
        val base = cleaned.substringBeforeLast('.', cleaned).trim().ifEmpty { fallback }
        return base.take(96).trim().ifEmpty { fallback } + "." + extension
    }
}
