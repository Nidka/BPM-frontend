package pe.bpm.features.incidents.presentation

import pe.bpm.core.designsystem.BpmPlayTone
import pe.bpm.core.designsystem.BpmSymbol

/*
 * Reglas puras del reporte rápido del personal (WORKER). El catálogo de incidencias no trae iconos: se eligen por
 * palabras del nombre de la categoría, sin tocar el servidor.
 */

/** Icono y color de la ficha de una categoría de incidencia. */
internal data class ReportIcon(val symbol: BpmSymbol, val tone: BpmPlayTone)

private fun plain(text: String): String =
    text.lowercase().replace('á', 'a').replace('é', 'e').replace('í', 'i').replace('ó', 'o').replace('ú', 'u').replace('ñ', 'n')

/**
 * Icono de una categoría por palabras de su [name]: temperatura → termómetro; plaga → insecto; equipo/malogr → llave;
 * insumo/producto → caja; agua/desag → gota; limpieza → rociador; cualquier otra → «Otro» (tres puntos).
 */
internal fun reportIcon(name: String): ReportIcon {
    val t = plain(name)
    fun has(vararg words: String) = words.any { it in t }
    return when {
        has("temperatura", "°c", "frio", "refriger", "congel") -> ReportIcon(BpmSymbol.Thermometer, BpmPlayTone.Streak)
        has("plaga", "insecto", "roedor", "mosca", "cucaracha") -> ReportIcon(BpmSymbol.Bug, BpmPlayTone.Gold)
        has("personal", "manos", "uniforme") -> ReportIcon(BpmSymbol.Hand, BpmPlayTone.Correct)
        has("equipo", "malogr", "maquina", "averi", "danad") -> ReportIcon(BpmSymbol.Wrench, BpmPlayTone.Action)
        has("insumo", "producto", "alimento", "vencid") -> ReportIcon(BpmSymbol.Box, BpmPlayTone.Correct)
        has("agua", "desag", "fuga") -> ReportIcon(BpmSymbol.Drop, BpmPlayTone.Action)
        has("limpieza", "higiene", "desinf", "sucio") -> ReportIcon(BpmSymbol.Spray, BpmPlayTone.Streak)
        else -> ReportIcon(BpmSymbol.Dots, BpmPlayTone.Gold)
    }
}

/** Los dos pasos del reporte: qué tipo de problema es y cuéntanos qué pasó (con foto opcional). */
internal enum class ReportStep { Category, Describe }

/** Largo máximo de la descripción (el servidor admite 4000). */
private const val DESCRIPTION_MAX = 4000

/**
 * Descripción de la incidencia con lo que escribió el personal: «Dónde: Cocina. La cámara no enfría» (lugar y nota),
 * solo «Dónde: Cocina» o solo la nota. Vacía si no escribió nada.
 */
internal fun reportDescription(place: String, note: String): String {
    val where = place.trim()
    val what = note.trim()
    val text = when {
        where.isNotEmpty() && what.isNotEmpty() -> "Dónde: $where. $what"
        where.isNotEmpty() -> "Dónde: $where"
        else -> what
    }
    return text.take(DESCRIPTION_MAX)
}
