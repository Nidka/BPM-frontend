package pe.bpm.features.training

import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.features.training.data.RemoteTrainingPlusRepository
import pe.bpm.features.training.data.RemoteTrainingRepository
import pe.bpm.features.training.domain.*

class RemoteTrainingPlusRepositoryTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val config = AppConfig("https://api.example.com")
    private val base = "/api/v1/organizations/org/training"
    private val external = """{"provider":"YOUTUBE","videoId":"dQw4w9WgXcQ"}"""

    private fun plus(engine: MockEngine) = RemoteTrainingPlusRepository(createHttpClient(engine), config, token = { "token" })
    private fun body(request: io.ktor.client.request.HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    private val achievement = """{"id":"$ACHIEVEMENT_ID","code":"PERFECT_SCORE","name":"Puntaje perfecto","description":"d","points":30,"criterionType":"PERFECT_SCORE","criterionThreshold":1,"active":true,"awardedAt":"$STAMP"}"""
    private val progressJson = """{"status":"IN_PROGRESS","lastLessonId":null,"bestScore":100,"experiencePoints":5,"completedAt":null}"""

    // ---- externalVideo -----------------------------------------------------------------------

    private fun learning(lessonExtra: String) = """{"data":{
        "course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene","description":"d","estimatedDurationMinutes":15},
        "version":{"id":"$VERSION_ID","versionNumber":2,"passingScore":80},
        "lessons":[{"id":"$LESSON_1","title":"Paso","content":"Texto","displayOrder":1,$lessonExtra}],
        "questions":[],"progress":$progressJson,"certificate":null}}"""

    private suspend fun course(lessonExtra: String): TrainingResult<TrainingCourse> {
        val client = createHttpClient(MockEngine { respond(learning(lessonExtra), HttpStatusCode.OK, json) })
        val storage = io.ktor.client.HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        return try { RemoteTrainingRepository(client, storage, config, token = { "token" }).course("org", COURSE_ID) } finally { client.close(); storage.close() }
    }

    @Test fun lessonsExposeTheYouTubeVideoWithoutAnyUrlFromTheServer() = runTest {
        val lesson = (course(""""video":null,"externalVideo":$external""") as TrainingResult.Success).value.lessons.single()
        assertEquals("dQw4w9WgXcQ", lesson.externalVideo?.videoId)
        assertEquals("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ", lesson.externalVideo?.embedUrl)
        assertNull(lesson.video)
        // Las lecciones antiguas sin el campo siguen funcionando.
        assertNull((course(""""video":null""") as TrainingResult.Success).value.lessons.single().externalVideo)
    }

    @Test fun invalidOrDuplicatedVideoSourcesAreRejected() = runTest {
        listOf(
            """"video":null,"externalVideo":{"provider":"VIMEO","videoId":"dQw4w9WgXcQ"}""",
            """"video":null,"externalVideo":{"provider":"YOUTUBE","videoId":"corto"}""",
            """"video":{"mediaId":"$MEDIA_ID","mimeType":"video/mp4","sizeBytes":10},"externalVideo":$external""",
        ).forEach { assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), course(it), it) }
    }

    @Test fun draftSendsAndReceivesTheExternalVideoAndNeverBothSources() = runTest {
        val seen = mutableListOf<JsonObject>()
        val client = createHttpClient(MockEngine { request ->
            seen += body(request)
            respond("""{"data":{"version":{"id":"$VERSION_ID","versionNumber":3,"passingScore":80,"rowVersion":"v2"},
                "lessons":[{"id":"$LESSON_1","title":"Paso a paso","content":"Texto","displayOrder":1,"videoMediaId":null,"externalVideo":$external}],"questions":[]}}""", HttpStatusCode.OK, json)
        })
        val storage = io.ktor.client.HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        val repo = RemoteTrainingRepository(client, storage, config, token = { "token" })
        try {
            val draft = sampleDraft().copy(lessons = listOf(DraftLesson(LESSON_1, "Paso a paso", "Texto", 1, null, "dQw4w9WgXcQ")), questions = emptyList())
            val saved = (repo.saveDraft("org", COURSE_ID, draft) as TrainingResult.Success).value
            assertEquals("dQw4w9WgXcQ", saved.lessons.single().externalVideoId)
            val lesson = seen.single()["lessons"]!!.jsonArray.single().jsonObject
            assertEquals(JsonNull, lesson["videoMediaId"])
            assertEquals("YOUTUBE", lesson["externalVideo"]!!.jsonObject["provider"]!!.jsonPrimitive.content)
            assertEquals("dQw4w9WgXcQ", lesson["externalVideo"]!!.jsonObject["videoId"]!!.jsonPrimitive.content)
            val both = draft.copy(lessons = listOf(draft.lessons[0].copy(videoMediaId = MEDIA_ID)))
            assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), repo.saveDraft("org", COURSE_ID, both))
            assertEquals(1, seen.size)
        } finally { client.close(); storage.close() }
    }

    // ---- Intentos MODULE y logros ------------------------------------------------------------

    @Test fun moduleAttemptSendsLessonIdAndParsesNewAchievements() = runTest {
        val seen = mutableListOf<JsonObject>()
        val client = createHttpClient(MockEngine { request ->
            seen += body(request)
            respond("""{"data":{"attempt":{"id":"50000000-0000-4000-8000-000000000001","attemptType":"MODULE","score":100,"passed":true,"completedAt":"$STAMP"},
                "results":[{"questionId":"$QUESTION_1","selectedOption":1,"correct":true,"correctOption":1,"explanation":"ok"}],
                "progress":$progressJson,"certificate":null,"achievements":[$achievement]}}""", HttpStatusCode.Created, json)
        })
        val storage = io.ktor.client.HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        val repo = RemoteTrainingRepository(client, storage, config, token = { "token" })
        try {
            val attempt = AttemptSubmission("50000000-0000-4000-8000-000000000001", "60000000-0000-4000-8000-000000000001", VERSION_ID, AttemptType.MODULE, STAMP, STAMP,
                listOf(AttemptAnswer(QUESTION_1, 1)), LESSON_1)
            val outcome = (repo.submitAttempt("org", COURSE_ID, attempt) as TrainingResult.Success).value
            assertEquals(AttemptType.MODULE, outcome.attempt.attemptType)
            assertEquals("PERFECT_SCORE", outcome.achievements.single().code)
            assertEquals(AchievementCriterion.PERFECT_SCORE, outcome.achievements.single().criterion)
            assertEquals(LESSON_1, seen.single()["lessonId"]!!.jsonPrimitive.content)
            assertEquals("MODULE", seen.single()["attemptType"]!!.jsonPrimitive.content)
            // Otros tipos nunca envían lessonId ni aceptan una lección.
            assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), repo.submitAttempt("org", COURSE_ID, attempt.copy(attemptType = AttemptType.FINAL)))
        } finally { client.close(); storage.close() }
    }

    @Test fun attemptsWithoutAchievementsFieldStillParse() = runTest {
        val client = createHttpClient(MockEngine {
            respond("""{"data":{"attempt":{"id":"50000000-0000-4000-8000-000000000001","attemptType":"FINAL","score":100,"passed":true,"completedAt":"$STAMP"},
                "results":[{"questionId":"$QUESTION_1","selectedOption":1,"correct":true,"correctOption":1,"explanation":""}],"progress":$progressJson,"certificate":null}}""", HttpStatusCode.OK, json)
        })
        val storage = io.ktor.client.HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        try {
            val attempt = AttemptSubmission("50000000-0000-4000-8000-000000000001", "60000000-0000-4000-8000-000000000001", VERSION_ID, AttemptType.FINAL, STAMP, STAMP, listOf(AttemptAnswer(QUESTION_1, 1)))
            val outcome = (RemoteTrainingRepository(client, storage, config, token = { "token" }).submitAttempt("org", COURSE_ID, attempt) as TrainingResult.Success).value
            assertTrue(outcome.achievements.isEmpty())
        } finally { client.close(); storage.close() }
    }

    // ---- Asignaciones ------------------------------------------------------------------------

    private val assignmentJson = """{"id":"$ASSIGNMENT_ID","course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene"},
        "target":{"type":"ROLE","userId":null,"userName":null,"role":"CHEF","locationId":null,"locationName":null},
        "dueAt":"2099-12-31T23:59:00Z","status":"ACTIVE","createdAt":"$STAMP","cancelledAt":null}"""

    @Test fun myAssignmentsMapStatusDueDateAndPagination() = runTest {
        val engine = MockEngine { request ->
            assertEquals("$base/me/assignments", request.url.encodedPath)
            assertEquals("tok_1", request.url.parameters["pageToken"])
            assertEquals("Bearer token", request.headers[HttpHeaders.Authorization])
            respond("""{"data":{"items":[{"assignmentId":"$ASSIGNMENT_ID","course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene","description":"d","estimatedDurationMinutes":15},
                "dueAt":"2020-01-01T00:00:00Z","assignedAt":"$STAMP","status":"OVERDUE","progressStatus":"IN_PROGRESS","bestScore":null,"completedAt":null}]},"pagination":{"nextPageToken":"tok_2"}}""", HttpStatusCode.OK, json)
        }
        val page = (plus(engine).myAssignments("org", "tok_1") as TrainingResult.Success).value
        assertEquals(AssignmentProgress.OVERDUE, page.items.single().status)
        assertEquals(ProgressStatus.IN_PROGRESS, page.items.single().progressStatus)
        assertEquals("tok_2", page.nextToken)
    }

    @Test fun myAssignmentsRejectInventedStatesAndDuplicatedCourses() = runTest {
        // String.format no existe en Kotlin/Native: se arma el JSON con una función.
        fun item(status: String) = """{"assignmentId":"$ASSIGNMENT_ID","course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene"},"assignedAt":"$STAMP","status":"$status","progressStatus":"NOT_STARTED"}"""
        for (payload in listOf("""{"data":{"items":[${item("RARO")}]}}""", """{"data":{"items":[${item("PENDING")},${item("PENDING")}]}}""")) {
            assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), plus(MockEngine { respond(payload, HttpStatusCode.OK, json) }).myAssignments("org", null))
        }
    }

    @Test fun createAssignmentBuildsEachTargetBodyExactly() = runTest {
        val seen = mutableListOf<JsonObject>()
        val engine = MockEngine { request -> seen += body(request); respond("""{"data":{"assignment":$assignmentJson}}""", HttpStatusCode.Created, json) }
        val repo = plus(engine)
        val role = NewAssignment(ASSIGNMENT_ID, COURSE_ID, AssignmentTarget.Role("CHEF"), "2099-12-31T23:59:00Z")
        val created = (repo.createAssignment("org", role) as TrainingResult.Success).value
        assertEquals(AssignmentTarget.Role("CHEF"), created.target)
        assertEquals(setOf("id", "courseId", "target", "dueAt"), seen[0].keys)
        assertEquals(setOf("type", "role"), seen[0]["target"]!!.jsonObject.keys)
        repo.createAssignment("org", role.copy(target = AssignmentTarget.User("uid"), dueAt = null))
        assertEquals(setOf("type", "userId"), seen[1]["target"]!!.jsonObject.keys)
        assertEquals(JsonNull, seen[1]["dueAt"])
        repo.createAssignment("org", role.copy(target = AssignmentTarget.Role("WORKER", LESSON_1)))
        assertEquals(LESSON_1, seen[2]["target"]!!.jsonObject["locationId"]!!.jsonPrimitive.content)
        // Un id distinto al enviado en la respuesta se descarta.
        val other = plus(MockEngine { respond("""{"data":{"assignment":${assignmentJson.replace(ASSIGNMENT_ID, "90000000-0000-4000-8000-000000000999")}}}""", HttpStatusCode.Created, json) })
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), other.createAssignment("org", role))
    }

    @Test fun assignmentAndPathErrorCodesMapToPublicReasons() = runTest {
        suspend fun reason(status: Int, code: String, call: suspend (RemoteTrainingPlusRepository) -> TrainingResult<*>): TrainingFailure {
            val repo = plus(MockEngine { respond("""{"errors":[{"code":"$code","message":"SQL"}]}""", HttpStatusCode.fromValue(status), json) })
            return (call(repo) as TrainingResult.Failure).reason
        }
        val new = NewAssignment(ASSIGNMENT_ID, COURSE_ID, AssignmentTarget.Role("CHEF"), null)
        assertEquals(TrainingFailure.ASSIGNMENT_EXISTS, reason(409, "TRAINING_ASSIGNMENT_EXISTS") { it.createAssignment("org", new) })
        assertEquals(TrainingFailure.CONFLICT, reason(409, "IDEMPOTENCY_CONFLICT") { it.createAssignment("org", new) })
        assertEquals(TrainingFailure.COURSE_NOT_PUBLISHED, reason(422, "TRAINING_COURSE_NOT_PUBLISHED") { it.createAssignment("org", new) })
        assertEquals(TrainingFailure.NOT_FOUND, reason(404, "TRAINING_ASSIGNEE_NOT_FOUND") { it.createAssignment("org", new) })
        assertEquals(TrainingFailure.PATH_CONFLICT, reason(409, "TRAINING_PATH_CONFLICT") { it.updatePath("org", PathInput(PATH_ID, "Ruta", "", emptyList(), "1")) })
        assertEquals(TrainingFailure.PATH_ARCHIVED, reason(409, "TRAINING_PATH_ARCHIVED") { it.publishPath("org", PATH_ID) })
        assertEquals(TrainingFailure.DENIED, reason(403, "ACCESS_DENIED") { it.myAssignments("org", null) })
        assertEquals(TrainingFailure.RATE_LIMITED, reason(429, "RATE_LIMITED") { it.assignments("org", null, null, null) })
    }

    @Test fun listAndCancelAssignmentsUseTheContractRoutesAndFilters() = runTest {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.method.value + " " + request.url.encodedPath + "?" + request.url.parameters.entries().joinToString("&") { it.key + "=" + it.value.single() }
            if (request.url.encodedPath.endsWith("/cancel")) respond("""{"data":{"assignment":${assignmentJson.replace("ACTIVE", "CANCELLED").replace("\"cancelledAt\":null", "\"cancelledAt\":\"$STAMP\"")}}}""", HttpStatusCode.OK, json)
            else respond("""{"data":{"items":[$assignmentJson]},"pagination":{"nextPageToken":""}}""", HttpStatusCode.OK, json)
        }
        val repo = plus(engine)
        val page = (repo.assignments("org", AssignmentRecordStatus.ACTIVE, COURSE_ID, null) as TrainingResult.Success).value
        assertNull(page.nextToken)
        assertEquals("GET $base/assignments?status=ACTIVE&courseId=$COURSE_ID", paths[0])
        // Un listado que ignora el filtro de estado se descarta.
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), repo.assignments("org", AssignmentRecordStatus.CANCELLED, null, null))
        val cancelled = (repo.cancelAssignment("org", ASSIGNMENT_ID) as TrainingResult.Success).value
        assertEquals(AssignmentRecordStatus.CANCELLED, cancelled.status)
        assertEquals("POST $base/assignments/$ASSIGNMENT_ID/cancel?", paths.last())
    }

    // ---- Logros y rutas ----------------------------------------------------------------------

    @Test fun achievementsMapTotalsAndEarnedState() = runTest {
        val engine = MockEngine {
            respond("""{"data":{"totals":{"experiencePoints":95,"courseExperiencePoints":30,"moduleExperiencePoints":15,"achievementPoints":50},"achievements":[$achievement,
                {"id":"a0000000-0000-4000-8000-000000000002","code":"STREAK_3","name":"Racha","description":"","points":10,"criterionType":"STREAK_DAYS","criterionThreshold":3,"active":true,"awardedAt":null}]}}""", HttpStatusCode.OK, json)
        }
        val mine = (plus(engine).myAchievements("org") as TrainingResult.Success).value
        assertEquals(95, mine.totals.experiencePoints)
        assertEquals(1, mine.earned.size)
        assertEquals("STREAK_3", mine.pending.single().code)
        val broken = plus(MockEngine { respond("""{"data":{"totals":{"experiencePoints":-5},"achievements":[]}}""", HttpStatusCode.OK, json) })
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), broken.myAchievements("org"))
    }

    @Test fun achievementUpdateSendsOnlyEditableFields() = runTest {
        var sent: JsonObject? = null
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            sent = body(request)
            respond("""{"data":{"achievement":$achievement}}""", HttpStatusCode.OK, json)
        }
        plus(engine).updateAchievement("org", ACHIEVEMENT_ID, AchievementEdit("Puntaje perfecto", "d", 30, 1, true))
        assertEquals(setOf("name", "description", "points", "criterionThreshold", "active"), sent!!.keys)
    }

    private fun pathJson(rowVersion: String = "3", status: String = "PUBLISHED") = """{"id":"$PATH_ID","title":"Inducción","description":"","status":"$status","rowVersion":"$rowVersion","publishedAt":null,
        "courses":[{"courseId":"$COURSE_ID","code":"M-01","title":"Higiene","position":1,"courseStatus":"PUBLISHED","progressStatus":"COMPLETED"}],
        "progress":{"status":"COMPLETED","completedCourses":1,"totalCourses":1,"percent":100,"nextCourseId":null}}"""

    @Test fun learnerPathsAskOnlyForPublishedAndMapProgress() = runTest {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath == "$base/paths" -> {
                    assertEquals("PUBLISHED", request.url.parameters["status"])
                    respond("""{"data":{"items":[${pathJson()}]},"pagination":{"nextPageToken":"n"}}""", HttpStatusCode.OK, json)
                }
                else -> respond("""{"data":{"path":${pathJson()}}}""", HttpStatusCode.OK, json)
            }
        }
        val repo = plus(engine)
        val page = (repo.paths("org", null) as TrainingResult.Success).value
        assertEquals(100, page.items.single().progress?.percent)
        assertNull(page.items.single().progress?.nextCourseId)
        assertEquals("n", page.nextToken)
        assertEquals(ProgressStatus.COMPLETED, (repo.path("org", PATH_ID) as TrainingResult.Success).value.courses.single().progressStatus)
    }

    @Test fun pathCreateAndUpdateSendRowVersionOnlyWhenEditing() = runTest {
        val seen = mutableListOf<Pair<String, JsonObject>>()
        val engine = MockEngine { request -> seen += request.method.value to body(request); respond("""{"data":{"path":${pathJson("4", "DRAFT")}}}""", HttpStatusCode.OK, json) }
        val repo = plus(engine)
        repo.createPath("org", PathInput(PATH_ID, "Inducción", "", listOf(COURSE_ID)))
        assertEquals("POST", seen[0].first)
        assertEquals(setOf("id", "title", "description", "courseIds"), seen[0].second.keys)
        val updated = (repo.updatePath("org", PathInput(PATH_ID, "Inducción", "", listOf(COURSE_ID), "3")) as TrainingResult.Success).value
        assertEquals("PUT", seen[1].first)
        assertEquals(setOf("title", "description", "courseIds", "rowVersion"), seen[1].second.keys)
        assertEquals("4", updated.rowVersion)
        // La secuencia devuelta debe coincidir con la enviada.
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), repo.updatePath("org", PathInput(PATH_ID, "Inducción", "", emptyList(), "3")))
    }

    // ---- Verificación pública ----------------------------------------------------------------

    @Test fun publicVerificationSendsNoCredentials() = runTest {
        val code = "ABCDEFGHIJKLMNOPQRSTUVWX"
        val engine = MockEngine { request ->
            assertEquals("/api/v1/public/training-certificates/$code", request.url.encodedPath)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("""{"data":{"status":"VALID","courseTitle":"Manos limpias","versionNumber":2,"issuedAt":"$STAMP","organization":"Sol","holder":"Ana P."}}""", HttpStatusCode.OK, json)
        }
        val verified = (plus(engine).verify(code) as TrainingResult.Success).value
        assertEquals(CertificateStatus.VALID, verified.status)
        assertEquals("Ana P.", verified.holder)
        assertEquals(TrainingResult.Failure(TrainingFailure.NOT_FOUND), plus(MockEngine { respond("""{"errors":[{"code":"TRAINING_CERTIFICATE_NOT_FOUND"}]}""", HttpStatusCode.NotFound, json) }).verify(code))
        assertEquals(TrainingResult.Failure(TrainingFailure.RATE_LIMITED), plus(MockEngine { respond("{}", HttpStatusCode.TooManyRequests, json) }).verify(code))
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), plus(MockEngine { respond("""{"data":{"status":"OTRO"}}""", HttpStatusCode.OK, json) }).verify(code))
        // Un código con formato imposible no llega a la red.
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), plus(MockEngine { error("no debe llamarse") }).verify("abc"))
    }
}
