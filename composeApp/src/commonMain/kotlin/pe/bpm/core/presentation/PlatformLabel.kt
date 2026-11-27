package pe.bpm.core.presentation

/** Versión visible de la app (igual a `versionName` de Android) para el mensaje a soporte. */
const val APP_VERSION = "0.2.0"

/** Nombre corto de la plataforma («Android», «iPhone», «Web») para el mensaje a soporte; nunca datos del equipo. */
internal expect fun platformLabel(): String

/** Enlace estable al APK firmado más reciente (GitHub Releases, etiqueta `android-v*`). */
const val ANDROID_APK_URL = "https://github.com/Nidka/BPM-frontend/releases/latest/download/BPM.apk"

/** Solo la web fuera de iPhone/iPad ofrece descargar el APK; dentro de las apps nativas nunca se muestra. */
internal expect fun offersAndroidDownload(): Boolean
