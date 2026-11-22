package pe.bpm.features.training

import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import pe.bpm.core.config.AppConfig
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.offline.*
import pe.bpm.features.training.data.*
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.*

/**
 * Pendientes del personal (F1): campos nuevos del backend (avance por lección, «Lo esencial», racha, XP del intento,
 * siguiente curso) con y sin ellos, y el envío de `completedLessonId` al avanzar de lección.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingPendientesTest {
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val config = AppConfig("https://api.example.com")
    private val base = "/api/v1/organizations/org/training"
    private val oldProgress = """{"status":"IN_PROGRESS","lastLessonId":"$LESSON_1","bestScore":null,"experiencePoints":0,"completedAt":null}"""
    private val newProgress = """{"status":"IN_PROGRESS","lastLessonId":"$LESSON_2","bestScore":null,"experiencePoints":0,"completedAt":null,
        "completedLessonIds":["$LESSON_1"],"completedLessons":1,"totalLessons":2,"percent":50}"""

    private fun remote(engine: MockEngine): Pair<RemoteTrainingRepository, List<HttpClient>> {
        val client = createHttpClient(engine)
        val storage = HttpClient(MockEngine { respond("", HttpStatusCode.OK) })
        return RemoteTrainingRepository(client, storage, config, token = { "token" }) to listOf(client, storage)
    }

    private fun learning(progress: String, lessonExtra: String = "") = """{"data":{
        "course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene","description":"d","estimatedDurationMinutes":15},
        "version":{"id":"$VERSION_ID","versionNumber":2,"passingScore":80},
        "lessons":[{"id":"$LESSON_1","title":"Paso","content":"Texto","displayOrder":1,"video":null$lessonExtra},
                   {"id":"$LESSON_2","title":"Dos","content":"","displayOrder":2,"video":null}],
        "questions":[],"progress":$progress,"certificate":null}}"""

    private suspend fun course(body: String): TrainingResult<TrainingCourse> {
        val (repository, clients) = remote(MockEngine { respond(body, HttpStatusCode.OK, json) })
        return try { repository.course("org", COURSE_ID) } finally { clients.forEach { it.close() } }
    }

    // ---- Avance por lección y «Lo esencial» (aprender) ----------------------------------------

    @Test fun courseProgressAndKeyPointsArriveWhenTheBackendSendsThem() = runTest {
        val value = (course(learning(newProgress, ""","keyPoints":["Lávate 20 s","Seca con papel"]""")) as TrainingResult.Success).value
        assertEquals(listOf(LESSON_1), value.progress.completedLessonIds)
        assertEquals(1, value.progress.completedLessons)
        assertEquals(2, value.progress.totalLessons)
        assertEquals(50, value.progress.percent)
        assertEquals(listOf("Lávate 20 s", "Seca con papel"), value.lessons[0].keyPoints)
        assertEquals(emptyList(), value.lessons[1].keyPoints)
    }

    @Test fun anOldBackendWithoutTheNewFieldsGivesEmptyDefaults() = runTest {
        val value = (course(learning(oldProgress)) as TrainingResult.Success).value
        assertEquals(emptyList(), value.progress.completedLessonIds)
        assertEquals(0, value.progress.completedLessons)
        assertEquals(0, value.progress.totalLessons)
        assertEquals(0, value.progress.percent)
        assertTrue(value.lessons.all { it.keyPoints.isEmpty() })
    }

    @Test fun inconsistentProgressOrKeyPointsAreRejected() = runTest {
        val badProgress = listOf(
            newProgress.replace("\"percent\":50", "\"percent\":101"),
            newProgress.replace("\"completedLessons\":1", "\"completedLessons\":-1"),
            newProgress.replace("[\"$LESSON_1\"]", "[\"no-es-uuid\"]"),
        )
        badProgress.forEach { assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), course(learning(it)), it) }
        val tooMany = (1..21).joinToString(",") { "\"p$it\"" }
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), course(learning(oldProgress, ""","keyPoints":[$tooMany]""")))
    }

    // ---- PUT progress con completedLessonId --------------------------------------------------

    @Test fun saveProgressSendsCompletedLessonIdOnlyWhenGiven() = runTest {
        val bodies = mutableListOf<String>()
        val (repository, clients) = remote(MockEngine { request ->
            assertEquals("PUT $base/courses/$COURSE_ID/progress", request.method.value + " " + request.url.encodedPath)
            bodies += (request.body as TextContent).text
            respond("""{"data":{"progress":$newProgress}}""", HttpStatusCode.OK, json)
        })
        try {
            val saved = (repository.saveProgress("org", COURSE_ID, LESSON_2, LESSON_1) as TrainingResult.Success).value
            assertEquals(listOf(LESSON_1), saved.completedLessonIds)
            assertEquals(50, saved.percent)
            repository.saveProgress("org", COURSE_ID, LESSON_2)
            assertEquals("""{"lastLessonId":"$LESSON_2","completedLessonId":"$LESSON_1"}""", bodies[0])
            assertEquals("""{"lastLessonId":"$LESSON_2"}""", bodies[1])
            // Un identificador que no es UUID nunca sale del dispositivo.
            assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), repository.saveProgress("org", COURSE_ID, LESSON_2, "no-es-uuid"))
            assertEquals(2, bodies.size)
        } finally { clients.forEach { it.close() } }
    }

    @Test fun withCompletedLessonIsIdempotentAndRecomputesThePercent() {
        val start = TrainingProgress(ProgressStatus.IN_PROGRESS, LESSON_1, null, 0, null, totalLessons = 4)
        val one = start.withCompletedLesson(LESSON_1)
        assertEquals(listOf(LESSON_1), one.completedLessonIds)
        assertEquals(25, one.percent)
        assertEquals(one, one.withCompletedLesson(LESSON_1))
        val unknownTotal = TrainingProgress(ProgressStatus.IN_PROGRESS, null, null, 0, null).withCompletedLesson(LESSON_1)
        assertEquals(1, unknownTotal.totalLessons)
        assertEquals(100, unknownTotal.percent)
        assertEquals(100, TrainingProgress(ProgressStatus.COMPLETED, null, 100, 10, STAMP, totalLessons = 4).withCompletedLesson(LESSON_1).percent)
    }

    // ---- Borrador: subtítulos (RF-45) solo con un servidor que los conoce ---------------------

    @Test fun captionsAreSentOnlyWhenTheServerKnowsThem() = runTest {
        val seen = mutableListOf<JsonObject>()
        fun response(captions: String) = """{"data":{"course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene","description":"d","estimatedDurationMinutes":15},
            "version":{"id":"$VERSION_ID","versionNumber":3,"passingScore":80,"rowVersion":"v2"},
            "lessons":[{"id":"$LESSON_1","title":"Paso a paso","content":"Texto","displayOrder":1,"videoMediaId":null$captions}],"questions":[]}}"""
        var reply = response("")
        val (repository, clients) = remote(MockEngine { request ->
            if (request.method == HttpMethod.Put) seen += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(reply, HttpStatusCode.OK, json)
        })
        try {
            // Servidor anterior (sin V16): no manda el campo, no se ofrece y no se envía (lo rechazaría).
            val old = (repository.draft("org", COURSE_ID) as TrainingResult.Success).value
            assertFalse(old.captionsSupported)
            repository.saveDraft("org", COURSE_ID, old.copy(questions = emptyList()))
            assertFalse("captionsVtt" in seen.last()["lessons"]!!.jsonArray.single().jsonObject)
            // Servidor con V16: se leen y se envían normalizados.
            reply = response(""","captionsVtt":"WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHola\n"""")
            val current = (repository.draft("org", COURSE_ID) as TrainingResult.Success).value
            assertTrue(current.captionsSupported)
            assertEquals(1, WebVtt.parse(current.lessons.single().captionsVtt).size)
            repository.saveDraft("org", COURSE_ID, current.copy(questions = emptyList(),
                lessons = listOf(current.lessons.single().copy(captionsVtt = "\r\nWEBVTT\r\n\r\n00:00:01.000 --> 00:00:03.000\r\nAdiós\r\n"))))
            assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nAdiós\n",
                seen.last()["lessons"]!!.jsonArray.single().jsonObject["captionsVtt"]!!.jsonPrimitive.content)
        } finally { clients.forEach { it.close() } }
    }

    // ---- Borrador: keyPoints de lectura y escritura ------------------------------------------

    @Test fun draftReadsKeyPointsAndAlwaysSendsThemOnSave() = runTest {
        val seen = mutableListOf<JsonObject>()
        fun response(points: String) = """{"data":{"course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene","description":"d","estimatedDurationMinutes":15},
            "version":{"id":"$VERSION_ID","versionNumber":3,"passingScore":80,"rowVersion":"v2"},
            "lessons":[{"id":"$LESSON_1","title":"Paso a paso","content":"Texto","displayOrder":1,"videoMediaId":null$points}],"questions":[]}}"""
        var reply = response(""","keyPoints":["Uno","Dos"]""")
        val (repository, clients) = remote(MockEngine { request ->
            if (request.method == HttpMethod.Put) seen += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            respond(reply, HttpStatusCode.OK, json)
        })
        try {
            val read = (repository.draft("org", COURSE_ID) as TrainingResult.Success).value
            assertEquals(listOf("Uno", "Dos"), read.lessons.single().keyPoints)
            val edited = read.copy(lessons = listOf(read.lessons.single().copy(keyPoints = listOf("  Nuevo  ", "  ", "Otro"))), questions = emptyList())
            val saved = (repository.saveDraft("org", COURSE_ID, edited) as TrainingResult.Success).value
            assertEquals(listOf("Uno", "Dos"), saved.lessons.single().keyPoints)
            val sent = seen.single()["lessons"]!!.jsonArray.single().jsonObject["keyPoints"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf("Nuevo", "Otro"), sent)
            // Vaciar «Lo esencial» envía [] (no se omite: omitirlo conservaría los anteriores).
            repository.saveDraft("org", COURSE_ID, edited.copy(lessons = listOf(edited.lessons.single().copy(keyPoints = emptyList()))))
            assertEquals(JsonArray(emptyList()), seen.last()["lessons"]!!.jsonArray.single().jsonObject["keyPoints"])
            // Un servidor anterior no manda el campo: llega vacío.
            reply = response("")
            assertEquals(emptyList(), (repository.draft("org", COURSE_ID) as TrainingResult.Success).value.lessons.single().keyPoints)
        } finally { clients.forEach { it.close() } }
    }

    // ---- Asignaciones, racha, XP del intento y siguiente curso --------------------------------

    private fun plus(engine: MockEngine) = RemoteTrainingPlusRepository(createHttpClient(engine), config, token = { "token" })

    @Test fun assignmentPercentIsMappedAndDefaultsToZero() = runTest {
        fun item(extra: String) = """{"assignmentId":"$ASSIGNMENT_ID","course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene"},"assignedAt":"$STAMP","status":"IN_PROGRESS","progressStatus":"IN_PROGRESS"$extra}"""
        val with = (plus(MockEngine { respond("""{"data":{"items":[${item(""","percent":40""")}]}}""", HttpStatusCode.OK, json) }).myAssignments("org", null) as TrainingResult.Success).value
        assertEquals(40, with.items.single().percent)
        val without = (plus(MockEngine { respond("""{"data":{"items":[${item("")}]}}""", HttpStatusCode.OK, json) }).myAssignments("org", null) as TrainingResult.Success).value
        assertEquals(0, without.items.single().percent)
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE),
            plus(MockEngine { respond("""{"data":{"items":[${item(""","percent":101""")}]}}""", HttpStatusCode.OK, json) }).myAssignments("org", null))
    }

    @Test fun achievementsStreakIsMappedAndAbsentForAnOldBackend() = runTest {
        val totals = """{"experiencePoints":10,"courseExperiencePoints":10,"moduleExperiencePoints":0,"achievementPoints":0}"""
        fun reply(streak: String) = """{"data":{"totals":$totals,"achievements":[]$streak}}"""
        val with = (plus(MockEngine { respond(reply(""","streak":{"currentDays":4,"longestDays":9,"lastActivityDate":"2026-11-19","activeToday":false,"timezone":"America/Lima"}"""), HttpStatusCode.OK, json) })
            .myAchievements("org") as TrainingResult.Success).value
        assertEquals(LearningStreak(4, 9, "2026-11-19", false, "America/Lima"), with.streak)
        val empty = (plus(MockEngine { respond(reply(""","streak":{"currentDays":0,"longestDays":0,"lastActivityDate":null,"activeToday":false,"timezone":"America/Lima"}"""), HttpStatusCode.OK, json) })
            .myAchievements("org") as TrainingResult.Success).value
        assertNull(empty.streak?.lastActivityDate)
        val old = (plus(MockEngine { respond(reply(""), HttpStatusCode.OK, json) }).myAchievements("org") as TrainingResult.Success).value
        assertNull(old.streak)
        for (bad in listOf("""{"currentDays":-1,"longestDays":0,"activeToday":false,"timezone":"x"}""", """{"currentDays":1,"longestDays":1,"lastActivityDate":"ayer","activeToday":true,"timezone":"x"}"""))
            assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), plus(MockEngine { respond(reply(""","streak":$bad"""), HttpStatusCode.OK, json) }).myAchievements("org"), bad)
    }

    @Test fun attemptOutcomeCarriesXpAndNextCourseOrNullForAnOldBackend() = runTest {
        val attempt = AttemptSubmission("50000000-0000-4000-8000-000000000001", "60000000-0000-4000-8000-000000000001", VERSION_ID, AttemptType.FINAL, STAMP, STAMP,
            listOf(AttemptAnswer(QUESTION_1, 1)))
        fun reply(attemptExtra: String, outcomeExtra: String) = """{"data":{"attempt":{"id":"${attempt.id}","attemptType":"FINAL","score":100,"passed":true,"completedAt":"$STAMP"$attemptExtra},
            "results":[{"questionId":"$QUESTION_1","selectedOption":1,"correct":true,"correctOption":1,"explanation":"Bien"}],
            "progress":{"status":"COMPLETED","experiencePoints":20},"certificate":null$outcomeExtra}}"""
        suspend fun outcome(body: String): TrainingResult<AttemptOutcome> {
            val (repository, clients) = remote(MockEngine { respond(body, HttpStatusCode.Created, json) })
            return try { repository.submitAttempt("org", COURSE_ID, attempt) } finally { clients.forEach { it.close() } }
        }
        val path = "30000000-0000-4000-8000-0000000000aa"
        val full = (outcome(reply(""","xpEarned":35""", ""","nextCourse":{"pathId":"$path","pathTitle":"Inducción","courseId":"$COURSE_ID","courseTitle":"Inocuidad"}""")) as TrainingResult.Success).value
        assertEquals(35, full.attempt.xpEarned)
        assertEquals(NextCourse(path, "Inducción", COURSE_ID, "Inocuidad"), full.nextCourse)
        val noPath = (outcome(reply("", ""","nextCourse":{"courseId":"$COURSE_ID","courseTitle":"Inocuidad"}""")) as TrainingResult.Success).value
        assertNull(noPath.attempt.xpEarned)
        assertEquals(NextCourse(null, null, COURSE_ID, "Inocuidad"), noPath.nextCourse)
        val old = (outcome(reply("", "")) as TrainingResult.Success).value
        assertNull(old.attempt.xpEarned)
        assertNull(old.nextCourse)
        val explicitNull = (outcome(reply("", ""","nextCourse":null""")) as TrainingResult.Success).value
        assertNull(explicitNull.nextCourse)
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), outcome(reply(""","xpEarned":-1""", "")))
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID_RESPONSE), outcome(reply("", ""","nextCourse":{"courseId":"no-es-uuid","courseTitle":"X"}""")))
    }

    // ---- Catálogo y cachés sin conexión ------------------------------------------------------

    @Test fun offlineCatalogKeepsKeyPointsAndOldCachedProgressStillDecodes() = runTest {
        val h = OfflineHarness().also { it.login() }
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        fun catalog(extra: String) = """{"data":{"etag":"${"a".repeat(64)}","generatedAt":"$STAMP","courses":[{
            "course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene de manos","description":"d","estimatedDurationMinutes":15},
            "version":{"id":"$VERSION_ID","versionNumber":2,"passingScore":80},
            "lessons":[{"id":"$LESSON_1","title":"Paso","content":"Texto","displayOrder":1,"video":null,"externalVideo":null,"offlineAvailable":true$extra}],"questions":[]}]}}"""
        var body = catalog(""","keyPoints":["Uno"]""")
        val client = createHttpClient(OfflineHttpEngine(MockEngine { respond(body, HttpStatusCode.OK, json) }, h.runtime.http, "https://api.example.com"))
        val catalogRepo = OfflineTrainingCatalog(RemoteTrainingRepository(client, HttpClient(MockEngine { respond("", HttpStatusCode.OK) }), config, token = { "token" }), store)
        catalogRepo.sync("org1")
        assertEquals(listOf("Uno"), store.cachedCourse("org1", COURSE_ID)!!.lessons.single().keyPoints)
        // Catálogo guardado por una versión anterior (sin keyPoints) y avance guardado sin los campos nuevos.
        body = catalog("").replace("a".repeat(64), "b".repeat(64))
        catalogRepo.sync("org1")
        assertEquals(emptyList(), store.cachedCourse("org1", COURSE_ID)!!.lessons.single().keyPoints)
        h.runtime.store.put(TrainingOfflineStore.PROGRESS, COURSE_ID, oldProgress)
        val old = store.progress(COURSE_ID)!!
        assertEquals(LESSON_1, old.lastLessonId)
        assertEquals(emptyList(), old.completedLessonIds)
        assertEquals(0, old.percent)
        // Ida y vuelta con los campos nuevos.
        store.saveProgress(COURSE_ID, old.withCompletedLesson(LESSON_1))
        assertEquals(listOf(LESSON_1), store.progress(COURSE_ID)!!.completedLessonIds)
    }

    @Test fun aLessonCompletedOfflineIsKeptLocallyUntilTheServerConfirmsIt() = runTest {
        val h = OfflineHarness().also { it.login() }
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        val fake = FakeTrainingRepository()
        fake.progressResult = { TrainingResult.Failure(TrainingFailure.QUEUED) }
        val repository = OfflineTrainingRepository(fake, store)
        val saved = (repository.saveProgress("org1", COURSE_ID, LESSON_2, LESSON_1) as TrainingResult.Success).value
        assertEquals(listOf(LESSON_1), saved.completedLessonIds)
        assertEquals(LESSON_2, saved.lastLessonId)
        assertEquals(listOf<String?>(LESSON_1), fake.completedCalls)
        // El servidor aún no la conoce: el curso leído en línea conserva la lección terminada pendiente.
        fake.course = TrainingResult.Success(sampleCourse().copy(progress = progress.copy(status = ProgressStatus.IN_PROGRESS, lastLessonId = LESSON_1)))
        val merged = (repository.course("org1", COURSE_ID) as TrainingResult.Success).value.progress
        assertEquals(listOf(LESSON_1), merged.completedLessonIds)
        assertEquals(LESSON_2, merged.lastLessonId)
        // Al confirmarse por la Outbox se retira el avance local.
        store.onSynced("/api/v1/organizations/org1/training/courses/$COURSE_ID/progress", 200, "{}")
        assertNull(store.progress(COURSE_ID))
    }

    // ---- ViewModel: completedLessonId al avanzar ---------------------------------------------

    private fun vm(repo: FakeTrainingRepository): TrainingViewModel {
        var n = 0
        return TrainingViewModel(LoadTrainingCourse(repo), RequestLessonVideo(repo), SaveLessonProgress(repo), CheckStudyAnswer(repo), SubmitTrainingAttempt(repo),
            { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }, { STAMP }, { Instant.parse("2026-11-10T12:00:00Z") })
    }

    private suspend fun TestScope.withVm(repo: FakeTrainingRepository, canPersist: Boolean = true, block: suspend (TrainingViewModel) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val model = vm(repo)
        try {
            model.open("user", "org", COURSE_ID, canPersist); advanceUntilIdle()
            block(model)
        } finally { model.reset(); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun nextLessonMarksTheCurrentOneCompletedTogetherWithTheNewLastLesson() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            model.selectLesson(LESSON_1); advanceUntilIdle()
            // Abrir una lección no la completa.
            assertEquals(listOf<String?>(null), repo.completedCalls)
            model.nextLesson(); advanceUntilIdle()
            assertEquals(listOf(LESSON_1, LESSON_2), repo.progressCalls)
            assertEquals(listOf<String?>(null, LESSON_1), repo.completedCalls)
            assertEquals(LESSON_2, model.state.value.lessonId)
            // Volver atrás no marca nada.
            model.previousLesson(); advanceUntilIdle()
            assertEquals(listOf(LESSON_1, LESSON_2, LESSON_1), repo.progressCalls)
            assertEquals(listOf<String?>(null, LESSON_1, null), repo.completedCalls)
        }
    }

    @Test fun finishLessonsCompletesTheLastLessonAndReturnsToTheOverview() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo) { model ->
            model.selectLesson(LESSON_2); advanceUntilIdle()
            assertTrue(model.finishLessons()); advanceUntilIdle()
            assertEquals(TrainingPane.OVERVIEW, model.state.value.screen)
            assertEquals(listOf(LESSON_2, LESSON_2), repo.progressCalls)
            assertEquals(listOf<String?>(null, LESSON_2), repo.completedCalls)
            // Fuera de una lección no guarda nada y se comporta como «volver».
            assertFalse(model.finishLessons()); advanceUntilIdle()
            assertEquals(2, repo.progressCalls.size)
        }
    }

    @Test fun theCourseProgressIsUpdatedFromTheResponseAndAnOldServerIsCoveredLocally() = runTest {
        val repo = FakeTrainingRepository()
        // Servidor nuevo: devuelve las lecciones terminadas.
        repo.progressResult = { id -> TrainingResult.Success(TrainingProgress(ProgressStatus.IN_PROGRESS, id, null, 0, null, listOf(LESSON_1), 1, 2, 50)) }
        withVm(repo) { model ->
            model.selectLesson(LESSON_1); advanceUntilIdle()
            model.nextLesson(); advanceUntilIdle()
            val progress = model.state.value.course!!.progress
            assertEquals(listOf(LESSON_1), progress.completedLessonIds)
            assertEquals(50, progress.percent)
        }
        // Servidor anterior: la respuesta no trae la marca y el cliente la refleja.
        val old = FakeTrainingRepository()
        withVm(old) { model ->
            model.selectLesson(LESSON_1); advanceUntilIdle()
            model.nextLesson(); advanceUntilIdle()
            val progress = model.state.value.course!!.progress
            assertEquals(LESSON_2, progress.lastLessonId)
            assertEquals(listOf(LESSON_1), progress.completedLessonIds)
        }
    }

    @Test fun withoutPersistenceNothingIsSent() = runTest {
        val repo = FakeTrainingRepository()
        withVm(repo, canPersist = false) { model ->
            model.selectLesson(LESSON_1); model.nextLesson(); model.finishLessons(); advanceUntilIdle()
            assertTrue(repo.progressCalls.isEmpty())
        }
    }

    @Test fun aFailedSaveDoesNotBlockNavigationAndFlagsTheNotice() = runTest {
        val repo = FakeTrainingRepository().apply { progressResult = { TrainingResult.Failure(TrainingFailure.CONNECTION) } }
        withVm(repo) { model ->
            model.selectLesson(LESSON_1); advanceUntilIdle()
            model.nextLesson(); advanceUntilIdle()
            assertEquals(LESSON_2, model.state.value.lessonId)
            assertTrue(model.state.value.progressNotSaved)
        }
    }
}
