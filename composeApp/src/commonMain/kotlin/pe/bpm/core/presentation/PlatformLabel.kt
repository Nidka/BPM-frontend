package pe.bpm.core.presentation

/** Versión visible de la app (igual a `versionName` de Android) para el mensaje a soporte. */
const val APP_VERSION = "0.1.0"

/** Nombre corto de la plataforma («Android», «iPhone», «Web») para el mensaje a soporte; nunca datos del equipo. */
internal expect fun platformLabel(): String
