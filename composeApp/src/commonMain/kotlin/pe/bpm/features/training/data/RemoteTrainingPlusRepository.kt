package pe.bpm.features.training.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.features.training.domain.*

/**
 * Adaptador Ktor de asignaciones, insignias, rutas y verificación pública. Los cuerpos se
 * construyen campo por campo (nunca se serializa un objeto de dominio) y las respuestas se
 * validan antes de llegar a la interfaz; el detalle técnico del servidor se descarta.
 */
class RemoteTrainingPlusRepository(
    private val client: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : TrainingLearnerRepository, TrainingPlusAdminRepository, CertificateVerificationRepository {

    // ---- Quien aprende -----------------------------------------------------------------------

    override suspend fun myAssignments(org: String, pageToken: String?): TrainingResult<TrainingPage<MyAssignment>> = guarded {
        val page = api<PagedEnvelope<MyAssignmentDto>>(org, "me/assignments", query = pageQuery(pageToken)).page { it.domain() }
        require(page.items.map { it.course.id.lowercase() }.distinct().size == page.items.size)
        page
    }

    override suspend fun myAchievements(org: String): TrainingResult<MyAchievements> = guarded {
        api<Envelope<MyAchievementsDto>>(org, "me/achievements").data.domain()
    }

    override suspend fun paths(org: String, pageToken: String?): TrainingResult<TrainingPage<LearningPath>> = guarded {
        // Quien aprende solo ve rutas publicadas; el filtro explícito evita un 403 para otros estados.
        api<PagedEnvelope<PathDto>>(org, "paths", query = pageQuery(pageToken) + ("status" to "PUBLISHED")).page { it.domain() }
    }

    override suspend fun path(org: String, pathId: String): TrainingResult<LearningPath> = guarded {
        api<Envelope<PathEnvelope>>(org, "paths/${trainingId(pathId)}").data.path.domain().also { require(it.id.equals(pathId, ignoreCase = true)) }
    }

    // ---- Asignaciones (administración) -------------------------------------------------------

    override suspend fun createAssignment(org: String, input: NewAssignment): TrainingResult<Assignment> = guarded {
        val body = buildJsonObject {
            put("id", trainingId(input.id))
            put("courseId", trainingId(input.courseId))
            put("target", buildJsonObject {
                when (val target = input.target) {
                    is AssignmentTarget.User -> { put("type", "USER"); put("userId", target.userId) }
                    is AssignmentTarget.Role -> {
                        put("type", "ROLE"); put("role", target.role)
                        target.locationId?.let { put("locationId", trainingId(it)) }
                    }
                    is AssignmentTarget.Location -> { put("type", "LOCATION"); put("locationId", trainingId(target.locationId)) }
                }
            })
            put("dueAt", input.dueAt?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        val created = api<Envelope<AssignmentEnvelope>>(org, "assignments", HttpMethod.Post, body).data.assignment.domain()
        require(created.id.equals(input.id, ignoreCase = true) && created.course.id.equals(input.courseId, ignoreCase = true))
        created
    }

    override suspend fun assignments(org: String, status: AssignmentRecordStatus?, courseId: String?, pageToken: String?): TrainingResult<TrainingPage<Assignment>> = guarded {
        val query = pageQuery(pageToken) + listOfNotNull(status?.let { "status" to it.name }, courseId?.let { "courseId" to trainingId(it) })
        val page = api<PagedEnvelope<AssignmentDto>>(org, "assignments", query = query).page { it.domain() }
        require(page.items.all { status == null || it.status == status } && page.items.all { courseId == null || it.course.id.equals(courseId, ignoreCase = true) })
        page
    }

    override suspend fun cancelAssignment(org: String, assignmentId: String): TrainingResult<Assignment> = guarded {
        val cancelled = api<Envelope<AssignmentEnvelope>>(org, "assignments/${trainingId(assignmentId)}/cancel", HttpMethod.Post, buildJsonObject {}).data.assignment.domain()
        require(cancelled.id.equals(assignmentId, ignoreCase = true) && cancelled.status == AssignmentRecordStatus.CANCELLED)
        cancelled
    }

    // ---- Insignias (administración) ----------------------------------------------------------

    override suspend fun achievements(org: String): TrainingResult<List<Achievement>> = guarded {
        val items = api<Envelope<ItemsDto<AchievementDto>>>(org, "achievements").data.items.map { it.domain() }
        require(items.size <= 200 && items.map { it.id.lowercase() }.distinct().size == items.size)
        items
    }

    override suspend fun updateAchievement(org: String, achievementId: String, edit: AchievementEdit): TrainingResult<Achievement> = guarded {
        val body = buildJsonObject {
            put("name", edit.name); put("description", edit.description); put("points", edit.points)
            put("criterionThreshold", edit.criterionThreshold); put("active", edit.active)
        }
        val updated = api<Envelope<AchievementEnvelope>>(org, "achievements/${trainingId(achievementId)}", HttpMethod.Put, body).data.achievement.domain()
        require(updated.id.equals(achievementId, ignoreCase = true))
        updated
    }

    // ---- Rutas (administración) --------------------------------------------------------------

    override suspend fun adminPaths(org: String, status: PathStatus?, pageToken: String?): TrainingResult<TrainingPage<LearningPath>> = guarded {
        val page = api<PagedEnvelope<PathDto>>(org, "paths", query = pageQuery(pageToken) + listOfNotNull(status?.let { "status" to it.name })).page { it.domain() }
        require(page.items.all { status == null || it.status == status })
        page
    }

    override suspend fun adminPath(org: String, pathId: String): TrainingResult<LearningPath> = path(org, pathId)

    override suspend fun createPath(org: String, input: PathInput): TrainingResult<LearningPath> = guarded {
        val created = api<Envelope<PathEnvelope>>(org, "paths", HttpMethod.Post, pathBody(input, includeId = true)).data.path.domain()
        require(created.id.equals(input.id, ignoreCase = true) && created.courses.map { it.courseId.lowercase() } == input.courseIds.map { it.lowercase() })
        created
    }

    override suspend fun updatePath(org: String, input: PathInput): TrainingResult<LearningPath> = guarded {
        val rowVersion = requireNotNull(input.rowVersion)
        val body = pathBody(input, includeId = false).let { JsonObject(it + ("rowVersion" to JsonPrimitive(rowVersion))) }
        val updated = api<Envelope<PathEnvelope>>(org, "paths/${trainingId(input.id)}", HttpMethod.Put, body).data.path.domain()
        require(updated.id.equals(input.id, ignoreCase = true) && updated.courses.map { it.courseId.lowercase() } == input.courseIds.map { it.lowercase() })
        updated
    }

    override suspend fun publishPath(org: String, pathId: String): TrainingResult<LearningPath> = guarded {
        api<Envelope<PathEnvelope>>(org, "paths/${trainingId(pathId)}/publish", HttpMethod.Post, buildJsonObject {}).data.path.domain()
            .also { require(it.id.equals(pathId, ignoreCase = true) && it.status == PathStatus.PUBLISHED) }
    }

    override suspend fun archivePath(org: String, pathId: String): TrainingResult<LearningPath> = guarded {
        api<Envelope<PathEnvelope>>(org, "paths/${trainingId(pathId)}/archive", HttpMethod.Post, buildJsonObject {}).data.path.domain()
            .also { require(it.id.equals(pathId, ignoreCase = true) && it.status == PathStatus.ARCHIVED) }
    }

    private fun pathBody(input: PathInput, includeId: Boolean): JsonObject = buildJsonObject {
        if (includeId) put("id", trainingId(input.id))
        put("title", input.title); put("description", input.description)
        putJsonArray("courseIds") { input.courseIds.forEach { add(trainingId(it)) } }
    }

    // ---- Verificación pública ----------------------------------------------------------------

    /** Consulta sin credenciales: no se envía token ni se notifica a la sesión. */
    override suspend fun verify(code: String): TrainingResult<CertificateVerification> = guarded {
        require(CertificateCodeRules.isValid(code))
        currentCoroutineContext().ensureActive()
        val response = client.get("${config.apiBaseUrl}/api/v1/public/training-certificates/$code")
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) throw TransportFailure(failureFor(response.status.value, ""))
        response.body<Envelope<CertificateVerificationDto>>().data.domain()
    }

    // ---- Transporte --------------------------------------------------------------------------

    private fun pageQuery(pageToken: String?): List<Pair<String, String>> = listOfNotNull(pageToken?.takeIf { it.isNotEmpty() }?.let { "pageToken" to it })

    private suspend inline fun <reified T> api(
        org: String,
        resource: String,
        verb: HttpMethod = HttpMethod.Get,
        body: JsonElement? = null,
        query: List<Pair<String, String>> = emptyList(),
    ): T {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(org))
        val access = token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(TrainingFailure.SESSION)
        currentCoroutineContext().ensureActive()
        val response = client.request("${config.apiBaseUrl}/api/v1/organizations/$org/training/$resource") {
            method = verb
            bearerAuth(access)
            query.forEach { (name, value) -> parameter(name, value) }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        onSessionResponse(response)
        currentCoroutineContext().ensureActive()
        if (!response.status.isSuccess()) {
            if (response.status.value == 401) onSessionRejected(access)
            val code = if (response.status.value in setOf(404, 409, 422)) {
                try { response.body<ErrorsEnvelope>().errors.firstOrNull()?.code.orEmpty() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { "" }
            } else ""
            throw TransportFailure(failureFor(response.status.value, code))
        }
        return response.body()
    }
}
