package pe.bpm.features.training.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import io.ktor.http.*
import io.ktor.serialization.JsonConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.crypto.Sha256
import pe.bpm.core.network.SessionAccessException
import pe.bpm.features.training.domain.*

/**
 * Adaptador Ktor de Academia. Las URL firmadas viven solo en la pila de cada operación: no se
 * guardan, no se registran y nunca llegan al estado de presentación salvo [VideoTicket], que la
 * pantalla descarta al salir de la lección.
 */
class RemoteTrainingRepository(
    private val client: HttpClient,
    private val objects: HttpClient,
    private val config: AppConfig,
    private val onSessionRejected: (String) -> Unit = {},
    private val onSessionResponse: (HttpResponse) -> Unit = {},
    private val token: suspend () -> String?,
) : TrainingRepository, TrainingAuthoringRepository {

    // ---- Aprender ----------------------------------------------------------------------------

    override suspend fun course(org: String, courseId: String): TrainingResult<TrainingCourse> = guarded {
        api<Envelope<CourseDetailDto>>(org, "courses/${trainingId(courseId)}").data.domain(courseId)
    }

    override suspend fun videoTicket(org: String, courseId: String, lessonId: String): TrainingResult<VideoTicket> = guarded {
        api<Envelope<VideoTicketDto>>(org, "courses/${trainingId(courseId)}/lessons/${trainingId(lessonId)}/video", HttpMethod.Post, buildJsonObject {}).data.domain()
    }

    override suspend fun saveProgress(org: String, courseId: String, lessonId: String): TrainingResult<TrainingProgress> = guarded {
        api<Envelope<ProgressEnvelope>>(org, "courses/${trainingId(courseId)}/progress", HttpMethod.Put,
            buildJsonObject { put("lastLessonId", trainingId(lessonId)) }).data.progress.domain()
    }

    override suspend fun checkStudyAnswer(org: String, courseId: String, questionId: String, selected: Int): TrainingResult<StudyFeedback> = guarded {
        require(selected in 0..5)
        val dto = api<Envelope<StudyDto>>(org, "courses/${trainingId(courseId)}/study/answers", HttpMethod.Post,
            buildJsonObject { put("questionId", trainingId(questionId)); put("selectedOption", selected) }).data
        require(dto.correctOption in 0..5 && dto.correct == (selected == dto.correctOption))
        StudyFeedback(dto.correct, dto.correctOption, dto.explanation)
    }

    override suspend fun submitAttempt(org: String, courseId: String, attempt: AttemptSubmission): TrainingResult<AttemptOutcome> = guarded {
        require(attempt.answers.isNotEmpty() && attempt.answers.size <= TrainingLimits.MAX_QUESTIONS)
        val body = buildJsonObject {
            put("id", trainingId(attempt.id))
            put("clientOperationId", trainingId(attempt.clientOperationId))
            put("versionId", trainingId(attempt.versionId))
            put("attemptType", attempt.attemptType.name)
            put("startedAt", attempt.startedAt)
            put("clientCreatedAt", attempt.clientCreatedAt)
            // MODULE evalúa solo una lección: el identificador viaja únicamente en ese tipo.
            if (attempt.attemptType == AttemptType.MODULE) put("lessonId", trainingId(requireNotNull(attempt.lessonId)))
            else require(attempt.lessonId == null)
            putJsonArray("answers") {
                attempt.answers.forEach { add(buildJsonObject { put("questionId", trainingId(it.questionId)); put("selectedOption", it.selectedOption) }) }
            }
        }
        api<Envelope<AttemptOutcomeDto>>(org, "courses/${trainingId(courseId)}/attempts", HttpMethod.Post, body).data.domain(attempt)
    }

    // ---- Autoría -----------------------------------------------------------------------------

    override suspend fun courses(org: String, status: CourseStatus?): TrainingResult<List<AdminCourse>> = guarded {
        val items = api<Envelope<AdminListDto>>(org, "courses", query = status?.name).data.items.map { it.domain() }
        require(items.size <= 100 && items.map { it.id.lowercase() }.distinct().size == items.size && items.all { status == null || it.status == status })
        items
    }

    override suspend fun createCourse(org: String, course: NewCourse): TrainingResult<TrainingCourseInfo> = guarded {
        val raw = api<Envelope<JsonObject>>(org, "courses", HttpMethod.Post, buildJsonObject {
            put("code", course.code); put("title", course.title); put("description", course.description)
            put("estimatedDurationMinutes", course.estimatedDurationMinutes)
        }).data
        // El backend responde {data:{course:{…}}}; se acepta también el curso directo.
        val created = lenientJson.decodeFromJsonElement(CourseInfoDto.serializer(), raw["course"] ?: raw).domain()
        require(created.code == course.code && created.title == course.title)
        created
    }

    override suspend fun draft(org: String, courseId: String): TrainingResult<TrainingDraft> = guarded {
        api<Envelope<DraftDto>>(org, "courses/${trainingId(courseId)}/draft").data.domain(null, courseId)
    }

    override suspend fun saveDraft(org: String, courseId: String, draft: TrainingDraft): TrainingResult<TrainingDraft> = guarded {
        val body = buildJsonObject {
            putJsonObject("version") {
                put("id", trainingId(draft.versionId)); put("versionNumber", draft.versionNumber)
                put("passingScore", draft.passingScore); put("rowVersion", draft.rowVersion)
            }
            putJsonArray("lessons") {
                draft.lessons.forEach { lesson -> add(buildJsonObject {
                    put("id", trainingId(lesson.id)); put("title", lesson.title); put("content", lesson.content)
                    put("displayOrder", lesson.displayOrder)
                    put("videoMediaId", lesson.videoMediaId?.let { JsonPrimitive(trainingId(it)) } ?: JsonNull)
                    // Video privado y de YouTube son excluyentes; se envía el identificador normalizado.
                    require(lesson.videoMediaId == null || lesson.externalVideoId == null)
                    put("externalVideo", lesson.externalVideoId?.let { id ->
                        require(YouTubeLinks.isVideoId(id))
                        buildJsonObject { put("provider", "YOUTUBE"); put("videoId", id) }
                    } ?: JsonNull)
                }) }
            }
            putJsonArray("questions") {
                draft.questions.forEach { question -> add(buildJsonObject {
                    put("id", trainingId(question.id))
                    put("lessonId", question.lessonId?.let { JsonPrimitive(trainingId(it)) } ?: JsonNull)
                    put("questionText", question.questionText)
                    putJsonArray("options") { question.options.forEach { add(it) } }
                    put("correctOption", question.correctOption); put("explanation", question.explanation)
                    put("displayOrder", question.displayOrder)
                }) }
            }
        }
        api<Envelope<DraftDto>>(org, "courses/${trainingId(courseId)}/draft", HttpMethod.Put, body).data.domain(draft.course, courseId)
    }

    override suspend fun publish(org: String, courseId: String): TrainingResult<Unit> = guarded {
        api<JsonElement>(org, "courses/${trainingId(courseId)}/publish", HttpMethod.Post, buildJsonObject {})
        Unit
    }

    override suspend fun uploadVideo(org: String, mediaId: String, video: PickedVideo, onProgress: (Float) -> Unit): TrainingResult<VideoFile> {
        if (TrainingVideoRules.validate(video.name, video.mimeType, video.bytes) != null) return TrainingResult.Failure(TrainingFailure.CONTENT_INVALID)
        val sha256 = try { Sha256.hexCediendo(video.bytes) } catch (cancelled: CancellationException) { throw cancelled }
        val reservation = guarded {
            val reserved = api<Envelope<UploadReservationDto>>(org, "videos/uploads", HttpMethod.Post, buildJsonObject {
                put("id", trainingId(mediaId)); put("originalName", video.name); put("mimeType", video.mimeType)
                put("sizeBytes", video.bytes.size); put("sha256", sha256)
            }).data
            val file = reserved.file.domain()
            require(file.id.equals(mediaId, ignoreCase = true) && file.originalName == video.name && file.mimeType == video.mimeType && file.sizeBytes == video.bytes.size.toLong())
            require(reserved.file.status in setOf("PENDING_UPLOAD", "AVAILABLE"))
            if (reserved.file.status == "PENDING_UPLOAD") {
                val ticket = reserved.upload ?: throw TransportFailure(TrainingFailure.UNAVAILABLE)
                ticket.validate(video.mimeType, video.bytes.size, sha256)
            }
            reserved
        }
        if (reservation is TrainingResult.Failure) return reservation
        val reserved = (reservation as TrainingResult.Success).value
        if (reserved.file.status == "AVAILABLE") return TrainingResult.Success(reserved.file.domain())
        val ticket = reserved.upload!!
        // Un PUT incierto o un 412 se resuelven confirmando la MISMA reserva; nunca se duplica el objeto.
        try {
            currentCoroutineContext().ensureActive()
            objects.preparePut(ticket.url) {
                timeout { requestTimeoutMillis = 30 * 60_000L; socketTimeoutMillis = 5 * 60_000L }
                ticket.headers.forEach { (name, values) ->
                    if (name.lowercase() !in setOf("host", "content-length")) values.forEach { header(name, it) }
                }
                headers.remove(HttpHeaders.Authorization)
                headers.remove(HttpHeaders.Cookie)
                setBody(video.bytes)
                onUpload { sent, _ -> onProgress((sent.toDouble() / video.bytes.size).coerceIn(0.0, 1.0).toFloat()) }
            }.execute { response ->
                if (!response.status.isSuccess() && response.status.value != 412) throw TransportFailure(TrainingFailure.UNAVAILABLE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // La confirmación decide si el objeto llegó.
        }
        // La confirmación es idempotente y puede tardar más que el timeout HTTP en videos grandes:
        // ante timeout o 5xx se repite con espera creciente hasta ver AVAILABLE o FAILED.
        var result = confirmVideo(org, mediaId, video)
        var wait = 2_000L
        repeat(5) {
            if (result is TrainingResult.Failure && (result as TrainingResult.Failure).reason.retryable) {
                delay(wait); wait *= 2
                result = confirmVideo(org, mediaId, video)
            }
        }
        return result
    }

    private suspend fun confirmVideo(org: String, mediaId: String, video: PickedVideo): TrainingResult<VideoFile> {
        return guarded {
            val confirmed = api<Envelope<JsonObject>>(org, "videos/${trainingId(mediaId)}/confirm", HttpMethod.Post, buildJsonObject {}).data
            val dto = lenientJson.decodeFromJsonElement(MediaFileDto.serializer(), confirmed["file"] ?: confirmed)
            val file = dto.domain()
            require(file.id.equals(mediaId, ignoreCase = true) && file.sizeBytes == video.bytes.size.toLong())
            when (dto.status) {
                "AVAILABLE" -> file
                "FAILED" -> throw TransportFailure(TrainingFailure.CONTENT_INVALID)
                else -> throw TransportFailure(TrainingFailure.CONNECTION)
            }
        }
    }

    // ---- Transporte --------------------------------------------------------------------------

    private suspend inline fun <reified T> api(
        org: String,
        resource: String,
        verb: HttpMethod = HttpMethod.Get,
        body: JsonElement? = null,
        query: String? = null,
    ): T {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(org))
        val access = token()?.takeIf { it.isNotBlank() } ?: throw TransportFailure(TrainingFailure.SESSION)
        currentCoroutineContext().ensureActive()
        val response = client.request("${config.apiBaseUrl}/api/v1/organizations/$org/training/$resource") {
            method = verb
            bearerAuth(access)
            query?.let { parameter("status", it) }
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

/** Traduce estado HTTP y código de dominio a un motivo público; el mensaje del servidor se descarta. */
internal fun failureFor(status: Int, code: String): TrainingFailure = when (status) {
    400 -> TrainingFailure.INVALID
    401 -> TrainingFailure.SESSION
    403 -> TrainingFailure.DENIED
    404 -> TrainingFailure.NOT_FOUND
    409 -> when (code) {
        "TRAINING_VERSION_OUTDATED" -> TrainingFailure.VERSION_OUTDATED
        "TRAINING_COURSE_CODE_TAKEN" -> TrainingFailure.CODE_TAKEN
        "TRAINING_DRAFT_CONFLICT" -> TrainingFailure.DRAFT_CONFLICT
        "TRAINING_ASSIGNMENT_EXISTS" -> TrainingFailure.ASSIGNMENT_EXISTS
        "TRAINING_PATH_CONFLICT" -> TrainingFailure.PATH_CONFLICT
        "TRAINING_PATH_ARCHIVED" -> TrainingFailure.PATH_ARCHIVED
        // Aún no hay confirmación: repetir comprueba la misma reserva, no crea otra.
        "UPLOAD_NOT_READY", "TRAINING_VIDEO_NOT_READY" -> TrainingFailure.CONNECTION
        else -> TrainingFailure.CONFLICT
    }
    413 -> TrainingFailure.TOO_LARGE
    422 -> when (code) {
        "TRAINING_ANSWERS_INVALID" -> TrainingFailure.ANSWERS_INVALID
        "TRAINING_DRAFT_INCOMPLETE" -> TrainingFailure.DRAFT_INCOMPLETE
        "TRAINING_COURSE_NOT_PUBLISHED" -> TrainingFailure.COURSE_NOT_PUBLISHED
        "MEDIA_CONTENT_INVALID", "TRAINING_VIDEO_INVALID" -> TrainingFailure.CONTENT_INVALID
        else -> TrainingFailure.INVALID
    }
    429 -> TrainingFailure.RATE_LIMITED
    else -> TrainingFailure.UNAVAILABLE
}

internal class TransportFailure(val reason: TrainingFailure) : Exception()

internal val lenientJson = Json { ignoreUnknownKeys = true }

internal suspend inline fun <T> guarded(block: () -> T): TrainingResult<T> =
    try {
        val result = block()
        currentCoroutineContext().ensureActive()
        TrainingResult.Success(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TransportFailure) {
        TrainingResult.Failure(error.reason)
    } catch (error: SessionAccessException) {
        TrainingResult.Failure(if (error.expired) TrainingFailure.SESSION else TrainingFailure.CONNECTION)
    } catch (_: SerializationException) {
        TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE)
    } catch (_: JsonConvertException) {
        TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE)
    } catch (_: IllegalArgumentException) {
        TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE)
    } catch (_: IllegalStateException) {
        TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE)
    } catch (_: Exception) {
        TrainingResult.Failure(TrainingFailure.CONNECTION)
    }
