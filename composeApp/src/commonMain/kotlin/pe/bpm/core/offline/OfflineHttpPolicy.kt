package pe.bpm.core.offline

import kotlinx.serialization.json.*

/**
 * Reglas de qué lecturas se guardan localmente y qué escrituras se pueden diferir. Son listas
 * explícitas: una ruta nueva no se cachea ni se encola hasta añadirla aquí con su prueba.
 */
object OfflineHttpPolicy {
    const val API_PREFIX = "/api/v1/"
    private const val ID = "[A-Za-z0-9_-]{1,64}"
    private val orgPath = Regex("^/api/v1/organizations/($ID)/([a-z-]+)(?:/(.*))?$")

    /** Primeros segmentos bajo la empresa que se pueden leer sin conexión. */
    private val cacheableReads = setOf(
        "workspace", "locations", "members", "checklists", "checklist-runs", "checklist-assignments", "shifts",
        "products", "suppliers", "supply-receipts", "storage-lots", "cleaning-plans", "cleaning-executions",
        "stock-movements", "incidents", "incident-catalog", "alerts", "training", "research",
    )

    /** Escrituras que se registran en la Outbox cuando no hay red. */
    private val queueableWrites = setOf(
        "checklist-runs", "supply-receipts", "cleaning-executions", "stock-movements", "incidents", "alerts", "training", "research",
    )
    private val trainingWrite = Regex("^courses/($ID)/(progress|attempts)$")

    /** Ruta sin consulta ni fragmento. */
    fun pathOf(fullPath: String) = fullPath.substringBefore('?').substringBefore('#')

    /** Recurso bajo la empresa («incidents», «alerts»…); vacío si la ruta no es de empresa. */
    fun resourceOf(path: String): String = orgPath.matchEntire(pathOf(path))?.groupValues?.get(2).orEmpty()

    fun organizationOf(path: String): String = orgPath.matchEntire(pathOf(path))?.groupValues?.get(1).orEmpty()

    fun cacheableRead(method: String, path: String): Boolean {
        if (!method.equals("GET", true)) return false
        val clean = pathOf(path)
        if (clean == "/api/v1/me" || clean == "/api/v1/organizations") return true
        val match = orgPath.matchEntire(clean) ?: return false
        val rest = match.groupValues[3]
        if (rest.contains("evidence") || rest.contains("export") || rest.contains("certificate")) return false
        // El catálogo descargable tiene su propia caché con ETag y los videos viajan por URL firmada.
        if (match.groupValues[2] == "training" && (rest.contains("offline-catalog") || rest.contains("video") || rest.endsWith("/draft"))) return false
        // Investigación: solo lo que necesita el participante; los análisis y exportaciones requieren servidor.
        if (match.groupValues[2] == "research" && rest != "participation") return false
        return match.groupValues[2] in cacheableReads
    }

    /**
     * Una escritura es diferible si es POST/PUT/PATCH sobre un recurso permitido. Las altas de
     * catálogo, reglas de alertas, evidencias y administración permanecen en línea.
     */
    fun queueableWrite(method: String, path: String): Boolean {
        if (method.uppercase() !in setOf("POST", "PUT", "PATCH")) return false
        val match = orgPath.matchEntire(pathOf(path)) ?: return false
        val resource = match.groupValues[2]
        val rest = match.groupValues[3]
        if (resource !in queueableWrites || rest.contains("evidence")) return false
        // Alertas: solo marcar como leída o atendida; las reglas son administración.
        if (resource == "alerts") return Regex("^$ID/(view|attend)$").matches(rest)
        // Capacitación: solo avance (PUT) y evaluaciones (POST); el consentimiento se acepta con red.
        if (resource == "training") return trainingWrite.matches(rest) && (method.uppercase() == "PUT") == rest.endsWith("/progress")
        if (resource == "research") return rest == "submissions" && method.uppercase() == "POST"
        return true
    }

    /** Identificador de la entidad afectada; mantiene el orden entre operaciones del mismo registro. */
    fun entityOf(path: String, body: String): String {
        val match = orgPath.matchEntire(pathOf(path)) ?: return ""
        val first = match.groupValues[3].substringBefore('/')
        if (match.groupValues[2] == "training") trainingWrite.matchEntire(match.groupValues[3])?.let { return it.groupValues[1] }
        if (first.isNotEmpty() && match.groupValues[2] != "research") return first
        return try {
            (Json.parseToJsonElement(body) as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
        } catch (_: Exception) { "" }
    }

    /** Clave de idempotencia de la solicitud original, o la derivada de forma determinista. */
    fun idempotencyKey(headerValue: String?, method: String, path: String, body: String): String? {
        headerValue?.takeIf { it.length in 8..100 }?.let { return it }
        val match = orgPath.matchEntire(pathOf(path)) ?: return null
        val rest = match.groupValues[3]
        val json = try { Json.parseToJsonElement(body) as? JsonObject } catch (_: Exception) { null }
        json?.get("clientOperationId")?.jsonPrimitive?.contentOrNull?.takeIf { it.length in 8..100 }?.let { return it }
        // Marcar alerta como leída/atendida es naturalmente idempotente: una clave por alerta y acción.
        if (match.groupValues[2] == "alerts") {
            val parts = rest.split('/')
            if (parts.size == 2) return "alert-${parts[0]}-${parts[1]}".take(100).takeIf { it.length >= 8 }
        }
        return null
    }

    /** Escrituras sin clave propia a las que se les asigna una al encolarlas (cada envío es una operación distinta). */
    fun generatesKey(method: String, path: String): Boolean {
        val match = orgPath.matchEntire(pathOf(path)) ?: return false
        return method.uppercase() == "PUT" && match.groupValues[2] == "training" && match.groupValues[3].endsWith("/progress")
    }

    /** Texto para la pantalla de cambios pendientes; nunca incluye datos del cuerpo. */
    fun describe(method: String, path: String): String {
        val match = orgPath.matchEntire(pathOf(path)) ?: return "Cambio pendiente"
        val rest = match.groupValues[3]
        return when (match.groupValues[2]) {
            "checklist-runs" -> when {
                rest.endsWith("/answers") -> "Guardar respuestas del control"
                rest.endsWith("/submit") -> "Enviar control terminado"
                else -> "Iniciar control"
            }
            "supply-receipts" -> "Registrar recepción de insumos"
            "cleaning-executions" -> "Registrar limpieza"
            "stock-movements" -> "Registrar movimiento de stock"
            "incidents" -> if (rest.contains("actions")) "Registrar acción correctiva" else "Registrar incidencia"
            "training" -> if (rest.endsWith("/attempts")) "Enviar evaluación (se calificará al conectarse)" else "Guardar avance del curso"
            "research" -> "Enviar encuesta del estudio"
            "alerts" -> if (rest.endsWith("/attend")) "Marcar alerta como atendida" else "Marcar alerta como leída"
            else -> "Cambio pendiente"
        }
    }

    /** La ruta se valida antes de reenviarla: debe ser de la API y no escapar de ella. */
    fun safeApiPath(path: String): Boolean =
        path.startsWith(API_PREFIX) && !path.contains("..") && !path.contains("//") && !path.contains('\\') &&
            path.none { it.code < 33 || it.code == 127 }
}
