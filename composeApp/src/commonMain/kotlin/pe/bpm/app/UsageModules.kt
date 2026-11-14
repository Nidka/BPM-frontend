package pe.bpm.app

import pe.bpm.features.usage.domain.UsageModule

/**
 * Traduce la ruta actual al módulo del contrato de uso. Solo se registra el módulo (nunca la URL,
 * identificadores de plantillas, cursos o controles). Rutas sin módulo (acceso, conexión) no se registran.
 */
internal fun usageModuleFor(route: String?): UsageModule? = when {
    route == null || route == "acceso" || route == "conexion" -> null
    route == "inicio" || route == "organizaciones" -> UsageModule.HOME
    route.startsWith("control/operacion") || route.startsWith("empresa/operacion") -> UsageModule.OPERATIONS
    route.contains("incidencias") -> UsageModule.INCIDENTS
    route == "avisos" || route == "empresa/avisos" -> UsageModule.ALERTS
    route.startsWith("aprender") || route.startsWith("empresa/academia") -> UsageModule.TRAINING
    route.startsWith("reportes") -> UsageModule.REPORTS
    route.startsWith("control") || route.startsWith("tareas") || route.startsWith("seguimiento") ||
        route.startsWith("empresa/plantillas") || route.startsWith("empresa/controles-programados") -> UsageModule.CHECKLISTS
    route == "cuenta" || route == "equipo" || route == "empresa" -> UsageModule.SETTINGS
    else -> UsageModule.APP
}
