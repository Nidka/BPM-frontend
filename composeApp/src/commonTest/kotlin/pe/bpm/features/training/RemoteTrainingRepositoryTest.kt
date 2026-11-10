package pe.bpm.features.training

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.training.data.RemoteTrainingRepository
import pe.bpm.features.training.domain.*

class RemoteTrainingRepositoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val config = AppConfig("https://api.example.com")
    private val base = "/api/v1/organizations/org/training"

    private fun repo(api: MockEngine, objects: MockEngine = MockEngine { respond("", HttpStatusCode.OK) }): Pair<RemoteTrainingRepository, List<HttpClient>> {
        val client = createHttpClient(api)
        val storage = HttpClient(objects) { followRedirects = false; expectSuccess = false; install(io.ktor.client.plugins.HttpTimeout) }
        return RemoteTrainingRepository(client, storage, config, token = { "token" }) to listOf(client, storage)
    }

    private val learning = """{"data":{
        "course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene","description":"d","estimatedDurationMinutes":15},
        "version":{"id":"$VERSION_ID","versionNumber":2,"passingScore":80},
        "lessons":[{"id":"$LESSON_1","title":"Paso","content":"Texto","displayOrder":1,"video":{"mediaId":"$MEDIA_ID","mimeType":"video/mp4","sizeBytes":1234}},
                   {"id":"$LESSON_2","title":"Dos","content":"","displayOrder":2,"video":null}],
        "questions":[{"id":"$QUESTION_1","lessonId":"$LESSON_1","questionText":"¿?","options":["a","b"],"displayOrder":1,"correctOption":1,"explanation":"secreto"}],
        "progress":{"status":"IN_PROGRESS","lastLessonId":"$LESSON_1","bestScore":null,"experiencePoints":0,"completedAt":null},
        "certificate":null}}"""

    @Test fun learningViewTranslatesWithoutAnyAnswerInformation() = runTest {
        val (repository, clients) = repo(MockEngine { request ->
            assertEquals("$base/courses/$COURSE_ID", request.url.encodedPath)
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            respond(learning, HttpStatusCode.OK, json)
        })
        try {
            val course = (repository.course("org", COURSE_ID) as TrainingResult.Success).value
            assertEquals(listOf(LESSON_1, LESSON_2), course.lessons.map { it.id })
            assertEquals("video/mp4", course.lessons[0].video?.mimeType)
            assertNull(course.lessons[1].video)
            assertEquals(LESSON_1, course.progress.lastLessonId)
            val question = course.questions.single()
            assertEquals(listOf("a", "b"), question.options)
            // El modelo de aprendizaje no tiene dónde guardar la respuesta ni la explicación.
            assertFalse(question.toString().contains("secreto"))
            assertFalse(TrainingQuestion::class.simpleName.orEmpty().contains("correct"))
        } finally { clients.forEach { it.close() } }
    }

    @Test fun inconsistentCourseResponsesAreRejected() = runTest {
        val other = learning.replace("\"id\":\"$COURSE_ID\"", "\"id\":\"10000000-0000-4000-8000-000000000999\"")
        val orphan = learning.replace("\"lessonId\":\"$LESSON_1\"", "\"lessonId\":\"30000000-0000-4000-8000-000000000999\"")
        for (body in listOf(other, orphan, "{}", "no json")) {
            val (repository, clients) = repo(MockEngine { respond(body, HttpStatusCode.OK, json) })
            try { assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), repository.course("org", COURSE_ID)) } finally { clients.forEach { it.close() } }
        }
    }

    @Test fun errorCodesMapToPublicReasons() = runTest {
        suspend fun reason(status: Int, code: String): TrainingFailure {
            val (repository, clients) = repo(MockEngine { respond("""{"errors":[{"code":"$code","message":"SQL private"}]}""", HttpStatusCode.fromValue(status), json) })
            try { return (repository.course("org", COURSE_ID) as TrainingResult.Failure).reason } finally { clients.forEach { it.close() } }
        }
        assertEquals(TrainingFailure.NOT_FOUND, reason(404, "TRAINING_COURSE_NOT_FOUND"))
        assertEquals(TrainingFailure.VERSION_OUTDATED, reason(409, "TRAINING_VERSION_OUTDATED"))
        assertEquals(TrainingFailure.DRAFT_CONFLICT, reason(409, "TRAINING_DRAFT_CONFLICT"))
        assertEquals(TrainingFailure.CODE_TAKEN, reason(409, "TRAINING_COURSE_CODE_TAKEN"))
        assertEquals(TrainingFailure.CONFLICT, reason(409, "IDEMPOTENCY_CONFLICT"))
        assertEquals(TrainingFailure.ANSWERS_INVALID, reason(422, "TRAINING_ANSWERS_INVALID"))
        assertEquals(TrainingFailure.DRAFT_INCOMPLETE, reason(422, "TRAINING_DRAFT_INCOMPLETE"))
        assertEquals(TrainingFailure.SESSION, reason(401, "SESSION_EXPIRED"))
        assertEquals(TrainingFailure.DENIED, reason(403, "ACCESS_DENIED"))
        assertEquals(TrainingFailure.RATE_LIMITED, reason(429, "RATE_LIMITED"))
        assertEquals(TrainingFailure.UNAVAILABLE, reason(503, "BACKEND_UNAVAILABLE"))
    }

    @Test fun attemptBodyHasExactlyTheContractFieldsAndOutcomeIsCheckedAgainstWhatWasSent() = runTest {
        var body = JsonObject(emptyMap())
        val attempt = AttemptSubmission("50000000-0000-4000-8000-000000000001", "60000000-0000-4000-8000-000000000001", VERSION_ID, AttemptType.FINAL, STAMP, STAMP,
            listOf(AttemptAnswer(QUESTION_1, 1), AttemptAnswer(QUESTION_2, 0)))
        val ok = """{"data":{"attempt":{"id":"${attempt.id}","attemptType":"FINAL","score":50,"passed":false,"completedAt":"$STAMP"},
            "results":[{"questionId":"$QUESTION_1","selectedOption":1,"correct":true,"correctOption":1,"explanation":"Bien"},
                       {"questionId":"$QUESTION_2","selectedOption":0,"correct":false,"correctOption":2,"explanation":""}],
            "progress":{"status":"IN_PROGRESS","experiencePoints":0},"certificate":null}}"""
        val (repository, clients) = repo(MockEngine { request ->
            assertEquals("$base/courses/$COURSE_ID/attempts", request.url.encodedPath)
            body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(ok, HttpStatusCode.Created, json)
        })
        try {
            val outcome = (repository.submitAttempt("org", COURSE_ID, attempt) as TrainingResult.Success).value
            assertEquals(setOf("id", "clientOperationId", "versionId", "attemptType", "startedAt", "clientCreatedAt", "answers"), body.keys)
            assertEquals(setOf("questionId", "selectedOption"), body["answers"]!!.jsonArray[0].jsonObject.keys)
            assertEquals(50, outcome.attempt.score)
            assertEquals(listOf(true, false), outcome.results.map { it.correct })
        } finally { clients.forEach { it.close() } }
        // Puntaje que no corresponde con los resultados: respuesta inválida.
        val (inconsistent, others) = repo(MockEngine { respond(ok.replace("\"score\":50", "\"score\":99"), HttpStatusCode.OK, json) })
        try { assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), inconsistent.submitAttempt("org", COURSE_ID, attempt)) } finally { others.forEach { it.close() } }
        // Versión desactualizada.
        val (outdated, last) = repo(MockEngine { respond("""{"errors":[{"code":"TRAINING_VERSION_OUTDATED","message":"x"}]}""", HttpStatusCode.Conflict, json) })
        try { assertEquals(TrainingResult.Failure(TrainingFailure.VERSION_OUTDATED), outdated.submitAttempt("org", COURSE_ID, attempt)) } finally { last.forEach { it.close() } }
    }

    @Test fun studyAnswerAndProgressUseTheirEndpoints() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val (repository, clients) = repo(MockEngine { request ->
            requests += request.method.value + " " + request.url.encodedPath to (request.body as? TextContent)?.text.orEmpty()
            if (request.url.encodedPath.endsWith("/progress"))
                respond("""{"data":{"progress":{"status":"IN_PROGRESS","lastLessonId":"$LESSON_2","experiencePoints":0}}}""", HttpStatusCode.OK, json)
            else respond("""{"data":{"correct":false,"correctOption":1,"explanation":"Porque sí"}}""", HttpStatusCode.OK, json)
        })
        try {
            val feedback = (repository.checkStudyAnswer("org", COURSE_ID, QUESTION_1, 0) as TrainingResult.Success).value
            assertEquals(StudyFeedback(false, 1, "Porque sí"), feedback)
            val saved = (repository.saveProgress("org", COURSE_ID, LESSON_2) as TrainingResult.Success).value
            assertEquals(LESSON_2, saved.lastLessonId)
            assertEquals("POST $base/courses/$COURSE_ID/study/answers", requests[0].first)
            assertEquals(setOf("questionId", "selectedOption"), Json.parseToJsonElement(requests[0].second).jsonObject.keys)
            assertEquals("PUT $base/courses/$COURSE_ID/progress", requests[1].first)
            assertEquals("""{"lastLessonId":"$LESSON_2"}""", requests[1].second)
        } finally { clients.forEach { it.close() } }
        // Una respuesta del servidor que contradice la opción enviada no se acepta.
        val (liar, others) = repo(MockEngine { respond("""{"data":{"correct":true,"correctOption":1,"explanation":""}}""", HttpStatusCode.OK, json) })
        try { assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), liar.checkStudyAnswer("org", COURSE_ID, QUESTION_1, 0)) } finally { others.forEach { it.close() } }
    }

    @Test fun videoTicketMustBeAnHttpsR2UrlThatHasNotExpired() = runTest {
        suspend fun ticket(url: String, expires: String): TrainingResult<VideoTicket> {
            val (repository, clients) = repo(MockEngine { respond("""{"data":{"url":"$url","mimeType":"video/mp4","expiresAt":"$expires"}}""", HttpStatusCode.OK, json) })
            try { return repository.videoTicket("org", COURSE_ID, LESSON_1) } finally { clients.forEach { it.close() } }
        }
        val good = "https://bucket.abc.r2.cloudflarestorage.com/o/v.mp4?X-Amz-Signature=s"
        assertIs<TrainingResult.Success<VideoTicket>>(ticket(good, "2099-01-01T00:00:00Z"))
        assertIs<TrainingResult.Failure>(ticket(good, "2001-01-01T00:00:00Z"))
        assertIs<TrainingResult.Failure>(ticket("http://bucket.abc.r2.cloudflarestorage.com/v.mp4", "2099-01-01T00:00:00Z"))
        assertIs<TrainingResult.Failure>(ticket("https://evil.example.com/v.mp4", "2099-01-01T00:00:00Z"))
    }

    @Test fun draftSaveSendsRowVersionAndSurfacesConcurrencyConflict() = runTest {
        var sent = JsonObject(emptyMap())
        val (repository, clients) = repo(MockEngine { request ->
            assertEquals("PUT", request.method.value)
            sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond("""{"data":{"version":{"id":"$VERSION_ID","versionNumber":3,"passingScore":80,"rowVersion":"v2"},
                "lessons":[{"id":"$LESSON_1","title":"Paso a paso","content":"Texto","displayOrder":1,"videoMediaId":null}],
                "questions":[{"id":"$QUESTION_1","lessonId":"$LESSON_1","questionText":"¿Cuánto dura?","options":["5 s","20 s"],"correctOption":1,"explanation":"Al menos 20 s","displayOrder":1}]}}""",
                HttpStatusCode.OK, json)
        })
        try {
            val saved = (repository.saveDraft("org", COURSE_ID, sampleDraft("v1")) as TrainingResult.Success).value
            assertEquals("v1", sent["version"]!!.jsonObject["rowVersion"]!!.jsonPrimitive.content)
            assertEquals(setOf("version", "lessons", "questions"), sent.keys)
            assertEquals(JsonNull, sent["lessons"]!!.jsonArray[0].jsonObject["videoMediaId"])
            assertEquals("v2", saved.rowVersion)
            assertEquals("Higiene de manos", saved.course.title)
        } finally { clients.forEach { it.close() } }
        val (conflict, others) = repo(MockEngine { respond("""{"errors":[{"code":"TRAINING_DRAFT_CONFLICT","message":"x"}]}""", HttpStatusCode.Conflict, json) })
        try { assertEquals(TrainingResult.Failure(TrainingFailure.DRAFT_CONFLICT), conflict.saveDraft("org", COURSE_ID, sampleDraft())) } finally { others.forEach { it.close() } }
    }

    private val mp4 = byteArrayOf(0, 0, 0, 24, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 1, 2, 3, 4)

    @Test fun videoUploadReservesPutsDirectlyToR2WithoutSessionAndConfirms() = runTest {
        val sha = pe.bpm.core.crypto.Sha256.hex(mp4)
        val api = mutableListOf<String>()
        var reserveBody = JsonObject(emptyMap())
        val reserved = """{"data":{"file":{"id":"$MEDIA_ID","originalName":"lavado.mp4","mimeType":"video/mp4","sizeBytes":${mp4.size},"status":"PENDING_UPLOAD"},
            "upload":{"method":"PUT","url":"https://bucket.abc.r2.cloudflarestorage.com/o/v.mp4?sig=1","headers":{"Content-Type":["video/mp4"],"If-None-Match":["*"],"X-Amz-Meta-Sha256":["$sha"],"Host":["bucket.abc.r2.cloudflarestorage.com"]},"expiresAt":"2099-01-01T00:00:00Z"}}}"""
        var objectRequest: io.ktor.client.request.HttpRequestData? = null
        val (repository, clients) = repo(
            MockEngine { request ->
                api += request.method.value + " " + request.url.encodedPath
                if (request.url.encodedPath.endsWith("/uploads")) {
                    reserveBody = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    respond(reserved, HttpStatusCode.Created, json)
                } else respond("""{"data":{"file":{"id":"$MEDIA_ID","originalName":"lavado.mp4","mimeType":"video/mp4","sizeBytes":${mp4.size},"status":"AVAILABLE"}}}""", HttpStatusCode.OK, json)
            },
            MockEngine { request -> objectRequest = request; respond("", HttpStatusCode.OK) },
        )
        try {
            val progress = mutableListOf<Float>()
            val file = (repository.uploadVideo("org", MEDIA_ID, PickedVideo("lavado.mp4", "video/mp4", mp4)) { progress += it } as TrainingResult.Success).value
            assertEquals(MEDIA_ID, file.id)
            assertEquals(listOf("POST $base/videos/uploads", "POST $base/videos/$MEDIA_ID/confirm"), api)
            assertEquals(sha, reserveBody["sha256"]!!.jsonPrimitive.content)
            assertEquals(mp4.size, reserveBody["sizeBytes"]!!.jsonPrimitive.int)
            val put = objectRequest!!
            assertEquals("PUT", put.method.value)
            assertEquals("bucket.abc.r2.cloudflarestorage.com", put.url.host)
            // Nunca viaja la sesión BPM ni cabeceras prohibidas hacia el almacenamiento.
            assertNull(put.headers[HttpHeaders.Authorization])
            assertEquals("video/mp4", put.headers["Content-Type"] ?: put.body.contentType?.toString())
            assertNull(put.headers["Host"])
            assertTrue(put.body is OutgoingContent)
        } finally { clients.forEach { it.close() } }
    }

    @Test fun uploadWithLostPutResponseStillConfirmsTheSameReservationAndFailedFileIsContentInvalid() = runTest {
        val sha = pe.bpm.core.crypto.Sha256.hex(mp4)
        val reserved = """{"data":{"file":{"id":"$MEDIA_ID","originalName":"lavado.mp4","mimeType":"video/mp4","sizeBytes":${mp4.size},"status":"PENDING_UPLOAD"},
            "upload":{"method":"PUT","url":"https://bucket.abc.r2.cloudflarestorage.com/o/v.mp4?sig=1","headers":{"Content-Type":["video/mp4"],"X-Amz-Meta-Sha256":["$sha"]},"expiresAt":"2099-01-01T00:00:00Z"}}}"""
        fun failedConfirm(status: String) = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/uploads")) respond(reserved, HttpStatusCode.Created, json)
            else respond("""{"data":{"file":{"id":"$MEDIA_ID","originalName":"lavado.mp4","mimeType":"video/mp4","sizeBytes":${mp4.size},"status":"$status"}}}""", HttpStatusCode.OK, json)
        }
        // El PUT falla con error de red; la confirmación decide.
        val broken = MockEngine { throw RuntimeException("sin red") }
        val (available, c1) = repo(failedConfirm("AVAILABLE"), broken)
        try { assertIs<TrainingResult.Success<VideoFile>>(available.uploadVideo("org", MEDIA_ID, PickedVideo("lavado.mp4", "video/mp4", mp4.copyOf())) {}) } finally { c1.forEach { it.close() } }
        val (failed, c2) = repo(failedConfirm("FAILED"))
        try { assertEquals(TrainingResult.Failure(TrainingFailure.CONTENT_INVALID), failed.uploadVideo("org", MEDIA_ID, PickedVideo("lavado.mp4", "video/mp4", mp4.copyOf())) {}) } finally { c2.forEach { it.close() } }
        // Firma hacia un destino que no es R2: se rechaza antes de enviar bytes.
        val evil = MockEngine { respond(reserved.replace("bucket.abc.r2.cloudflarestorage.com", "evil.example.com"), HttpStatusCode.Created, json) }
        val (guarded, c3) = repo(evil, MockEngine { error("no debe llamarse") })
        try { assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), guarded.uploadVideo("org", MEDIA_ID, PickedVideo("lavado.mp4", "video/mp4", mp4.copyOf())) {}) } finally { c3.forEach { it.close() } }
    }

    @Test fun authoringListAndCreateTranslate() = runTest {
        val (repository, clients) = repo(MockEngine { request ->
            if (request.method == HttpMethod.Get) {
                assertEquals("DRAFT", request.url.parameters["status"])
                respond("""{"data":{"items":[{"id":"$COURSE_ID","code":"M-01","title":"Higiene","status":"DRAFT","publishedVersionNumber":null,"hasDraft":true}]}}""", HttpStatusCode.OK, json)
            } else respond("""{"data":{"id":"$COURSE_ID","code":"M-02","title":"Nuevo","description":"","estimatedDurationMinutes":10,"status":"DRAFT"}}""", HttpStatusCode.Created, json)
        })
        try {
            val list = (repository.courses("org", CourseStatus.DRAFT) as TrainingResult.Success).value
            assertEquals(CourseStatus.DRAFT, list.single().status)
            assertTrue(list.single().hasDraft)
            assertEquals("M-02", (repository.createCourse("org", NewCourse("M-02", "Nuevo", "", 10)) as TrainingResult.Success).value.code)
            // El servidor no puede devolver otro curso distinto del solicitado.
            val (wrong, others) = repo(MockEngine { respond("""{"data":{"id":"$COURSE_ID","code":"M-99","title":"Otro","description":"","estimatedDurationMinutes":10,"status":"DRAFT"}}""", HttpStatusCode.Created, json) })
            try { assertIs<TrainingResult.Failure>(wrong.createCourse("org", NewCourse("M-02", "Nuevo", "", 10))) } finally { others.forEach { it.close() } }
        } finally { clients.forEach { it.close() } }
    }
}
