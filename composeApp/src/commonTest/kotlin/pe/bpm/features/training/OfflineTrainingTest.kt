package pe.bpm.features.training

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import pe.bpm.core.config.AppConfig
import pe.bpm.core.crypto.Sha256
import pe.bpm.core.network.createHttpClient
import pe.bpm.core.offline.*
import pe.bpm.features.training.data.*
import pe.bpm.features.training.domain.*
import kotlin.test.*

private const val API = "https://api.example.com"
private const val ORG = "org1"
private const val CATALOG_PATH = "/api/v1/organizations/$ORG/training/me/offline-catalog"
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

private val videoBytes = ByteArray(5_000) { (it * 13 + 5).toByte() }
private val videoSha = Sha256.hex(videoBytes)

private fun catalogJson(etag: String, sha: String = videoSha) = """{"data":{"etag":"$etag","generatedAt":"$STAMP","courses":[{
 "course":{"id":"$COURSE_ID","code":"M-01","title":"Higiene de manos","description":"d","estimatedDurationMinutes":15},
 "version":{"id":"$VERSION_ID","versionNumber":2,"passingScore":80},
 "lessons":[{"id":"$LESSON_1","title":"Paso a paso","content":"Texto uno","displayOrder":1,
   "video":{"mediaId":"$MEDIA_ID","mimeType":"video/mp4","sizeBytes":${videoBytes.size},"sha256":"$sha"},"externalVideo":null,"offlineAvailable":true},
  {"id":"$LESSON_2","title":"En YouTube","content":"","displayOrder":2,"video":null,"externalVideo":{"provider":"YOUTUBE","videoId":"dQw4w9WgXcQ"},"offlineAvailable":false}],
 "questions":[{"id":"$QUESTION_1","lessonId":"$LESSON_1","questionText":"¿Cuánto dura el lavado?","options":["5 s","20 s"],"displayOrder":1}]}]}}"""

private fun outcomeJson(attemptId: String) = """{"data":{
 "attempt":{"id":"$attemptId","attemptType":"FINAL","score":100,"passed":true,"completedAt":"$STAMP"},
 "results":[{"questionId":"$QUESTION_1","selectedOption":1,"correct":true,"correctOption":1,"explanation":"x"}],
 "progress":{"status":"COMPLETED","lastLessonId":null,"bestScore":100,"experiencePoints":20,"completedAt":"$STAMP"},"certificate":null,"achievements":[]}}"""

class OfflineTrainingTest {
    private val config = AppConfig(API)

    private fun remote(engine: MockEngine, objects: MockEngine = MockEngine { respond("", HttpStatusCode.OK) }, h: OfflineHarness? = null): RemoteTrainingRepository {
        val client = if (h == null) createHttpClient(engine) else createHttpClient(OfflineHttpEngine(engine, h.runtime.http, API))
        return RemoteTrainingRepository(client, HttpClient(objects) { expectSuccess = false; install(io.ktor.client.plugins.HttpTimeout) }, config, token = { "token" })
    }

    private suspend fun newHarness(): OfflineHarness = OfflineHarness().also { it.login() }

    // ---- Catálogo con ETag/304 -----------------------------------------------------------------

    @Test fun catalogIsDownloadedWithEtagAndKeptOnNotModified() = runTest {
        val h = newHarness()
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        var etag = "a".repeat(64)
        val seen = mutableListOf<String?>()
        val catalog = OfflineTrainingCatalog(remote(MockEngine { request ->
            assertEquals(CATALOG_PATH, request.url.encodedPath)
            val conditional = request.headers[HttpHeaders.IfNoneMatch]
            seen += conditional
            if (conditional == "\"$etag\"") respond("", HttpStatusCode.NotModified) else respond(catalogJson(etag), HttpStatusCode.OK, jsonHeaders)
        }), store)

        assertEquals(CatalogSync.UPDATED, (catalog.sync(ORG) as TrainingResult.Success).value)
        val first = store.cachedCourse(ORG, COURSE_ID)!!
        assertEquals(videoSha, first.lessons[0].video?.sha256)
        assertTrue(first.lessons[0].offlineAvailable)
        // YouTube queda marcado como solo en línea y las preguntas no traen la respuesta.
        assertFalse(first.lessons[1].offlineAvailable)
        assertNotNull(first.lessons[1].externalVideo)
        assertFalse(first.questions.toString().contains("correct", ignoreCase = true))

        h.now += 60_000
        assertEquals(CatalogSync.NOT_MODIFIED, (catalog.sync(ORG) as TrainingResult.Success).value)
        assertEquals(listOf(null, "\"$etag\""), seen)
        // El 304 conserva el contenido y solo renueva la marca de sincronización.
        assertEquals("Texto uno", store.cachedCourse(ORG, COURSE_ID)!!.lessons[0].content)
        assertEquals(h.now, store.cachedCourse(ORG, COURSE_ID)!!.cachedAt)

        etag = "b".repeat(64)
        assertEquals(CatalogSync.UPDATED, (catalog.sync(ORG) as TrainingResult.Success).value)
        assertEquals("\"${"a".repeat(64)}\"", seen.last())
    }

    @Test fun anInvalidCatalogIsRejectedWithoutReplacingTheSavedOne() = runTest {
        val h = newHarness()
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        var body = catalogJson("a".repeat(64))
        val catalog = OfflineTrainingCatalog(remote(MockEngine { respond(body, HttpStatusCode.OK, jsonHeaders) }), store)
        catalog.sync(ORG)
        body = catalogJson("c".repeat(64)).replace("\"mediaId\":\"$MEDIA_ID\"", "\"mediaId\":\"no-es-uuid\"")
        assertTrue(catalog.sync(ORG) is TrainingResult.Failure)
        assertEquals("a".repeat(64), store.catalogMeta(ORG)?.etag)
    }

    // ---- Lectura, avance y modo estudio sin red -------------------------------------------------

    @Test fun courseFallsBackToTheSavedCatalogWhenThereIsNoConnection() = runTest {
        val h = newHarness()
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        OfflineTrainingCatalog(remote(MockEngine { respond(catalogJson("a".repeat(64)), HttpStatusCode.OK, jsonHeaders) }), store).sync(ORG)
        val fake = FakeTrainingRepository().also { it.course = TrainingResult.Failure(TrainingFailure.CONNECTION) }
        val repository = OfflineTrainingRepository(fake, store)
        val course = (repository.course(ORG, COURSE_ID) as TrainingResult.Success).value
        assertEquals("Higiene de manos", course.course.title)
        assertNotNull(course.cachedAt)
        // Un curso que no se descargó no se inventa.
        assertTrue(repository.course(ORG, "10000000-0000-4000-8000-000000000999") is TrainingResult.Failure)
    }

    @Test fun progressSavedOfflineIsShownAtOnceAndClearedWhenTheServerReceivesIt() = runTest {
        val h = newHarness()
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        OfflineTrainingCatalog(remote(MockEngine { respond(catalogJson("a".repeat(64)), HttpStatusCode.OK, jsonHeaders) }), store).sync(ORG)
        val fake = FakeTrainingRepository()
        fake.progressResult = { TrainingResult.Failure(TrainingFailure.QUEUED) }
        val repository = OfflineTrainingRepository(fake, store)
        val saved = (repository.saveProgress(ORG, COURSE_ID, LESSON_2) as TrainingResult.Success).value
        assertEquals(LESSON_2, saved.lastLessonId)
        assertEquals(ProgressStatus.IN_PROGRESS, saved.status)
        fake.course = TrainingResult.Failure(TrainingFailure.CONNECTION)
        assertEquals(LESSON_2, (repository.course(ORG, COURSE_ID) as TrainingResult.Success).value.progress.lastLessonId)
        store.onSynced("/api/v1/organizations/$ORG/training/courses/$COURSE_ID/progress", 200, "{}")
        assertEquals(null, store.progress(COURSE_ID))
    }

    @Test fun studyFeedbackIsReusedOfflineOnlyIfItWasSeenBefore() = runTest {
        val h = newHarness()
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        val fake = FakeTrainingRepository()
        val repository = OfflineTrainingRepository(fake, store)
        // Sin red y sin retroalimentación guardada: necesita conexión, no se inventa la respuesta.
        fake.study = { _, _ -> TrainingResult.Failure(TrainingFailure.CONNECTION) }
        assertEquals(TrainingFailure.CONNECTION, (repository.checkStudyAnswer(ORG, COURSE_ID, QUESTION_1, 0) as TrainingResult.Failure).reason)
        // Con red se guarda; después se puede revisar sin conexión.
        fake.study = { _, selected -> TrainingResult.Success(StudyFeedback(selected == 1, 1, "Por higiene")) }
        repository.checkStudyAnswer(ORG, COURSE_ID, QUESTION_1, 1)
        fake.study = { _, _ -> TrainingResult.Failure(TrainingFailure.CONNECTION) }
        val offline = (repository.checkStudyAnswer(ORG, COURSE_ID, QUESTION_1, 0) as TrainingResult.Success).value
        assertFalse(offline.correct)
        assertEquals(1, offline.correctOption)
        assertEquals("Por higiene", offline.explanation)
    }

    // ---- Intento encolado y calificado al sincronizar -----------------------------------------------

    @Test fun anAttemptTakenOfflineIsQueuedAndGradedByTheServerOnSync() = runTest {
        val h = newHarness()
        h.connectivity.set(false)
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        val posts = mutableListOf<Pair<String, String>>()
        val api = MockEngine { request ->
            if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/attempts")) {
                posts += request.url.encodedPath to (request.body as io.ktor.http.content.TextContent).text
                respond(outcomeJson("50000000-0000-4000-8000-000000000001"), HttpStatusCode.Created, jsonHeaders)
            } else respond("{}", HttpStatusCode.OK, jsonHeaders)
        }
        val repository = OfflineTrainingRepository(remote(api, h = h), store)
        val attempt = AttemptSubmission("50000000-0000-4000-8000-000000000001", "60000000-0000-4000-8000-000000000001", VERSION_ID,
            AttemptType.FINAL, STAMP, STAMP, listOf(AttemptAnswer(QUESTION_1, 1)))

        val queued = repository.submitAttempt(ORG, COURSE_ID, attempt)
        assertEquals(TrainingFailure.QUEUED, (queued as TrainingResult.Failure).reason)
        assertTrue(posts.isEmpty())
        // El intento aparece como «enviada, se calificará al conectarse»: sin calificación del cliente.
        val pending = store.observeAttempts(COURSE_ID).first().single()
        assertFalse(pending.graded)
        assertEquals(1, h.runtime.outbox.snapshot().size)
        assertEquals(COURSE_ID, h.runtime.outbox.snapshot().single().entityId)

        // Al volver la red el servidor califica y la respuesta se guarda en el registro local.
        h.runtime.register(HttpOutboxHandler.KIND, HttpOutboxHandler(createHttpClient(api), API, { "token" }, store = h.runtime.store,
            onResponse = { path, _, status, body -> store.onSynced(path, status, body) }))
        h.connectivity.set(true)
        assertEquals(1, h.runtime.syncNow().sent)
        assertEquals(1, posts.size)
        assertTrue(posts.single().first.endsWith("/training/courses/$COURSE_ID/attempts"))
        // Se reenvía exactamente el mismo cuerpo, con el clientOperationId que el servidor usa para ser idempotente.
        assertTrue(posts.single().second.contains("\"clientOperationId\":\"60000000-0000-4000-8000-000000000001\""))
        val graded = store.observeAttempts(COURSE_ID).first().single()
        assertTrue(graded.graded)
        assertEquals(100, graded.result?.score)
        assertEquals(true, graded.result?.passed)
        assertEquals(ProgressStatus.COMPLETED, store.progress(COURSE_ID)?.status)
    }

    @Test fun retryingAnAlreadyQueuedAttemptDoesNotDuplicateIt() = runTest {
        val h = newHarness()
        h.connectivity.set(false)
        val store = TrainingOfflineStore(h.runtime.store) { h.now }
        val repository = OfflineTrainingRepository(remote(MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }, h = h), store)
        val attempt = AttemptSubmission("50000000-0000-4000-8000-000000000002", "60000000-0000-4000-8000-000000000002", VERSION_ID,
            AttemptType.DIAGNOSTIC, STAMP, STAMP, listOf(AttemptAnswer(QUESTION_1, 0)))
        repeat(2) { repository.submitAttempt(ORG, COURSE_ID, attempt) }
        assertEquals(1, h.runtime.outbox.snapshot().size)
    }

    // ---- Descarga de videos con límite de espacio -------------------------------------------------------

    /** Las descargas corren en hilos reales (el motor de pruebas no usa el reloj virtual): se espera a que terminen. */
    private suspend fun awaitIdle(downloads: OfflineTrainingDownloads) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        kotlinx.coroutines.withTimeout(10_000) { while (downloads.state.value.activeCourseId != null) kotlinx.coroutines.delay(10) }
    }

    private fun courseWithVideo(sha: String = videoSha) = sampleCourse().let { base ->
        base.copy(lessons = listOf(base.lessons[0].copy(video = LessonVideo(MEDIA_ID, "video/mp4", videoBytes.size.toLong(), sha)), base.lessons[1]))
    }

    private fun downloads(h: OfflineHarness, files: PrivateFileStore, objects: MockEngine, scope: kotlinx.coroutines.CoroutineScope): Pair<OfflineTrainingDownloads, FakeTrainingRepository> {
        val fake = FakeTrainingRepository()
        val client = HttpClient(objects) { expectSuccess = false; install(io.ktor.client.plugins.HttpTimeout) }
        return OfflineTrainingDownloads(files, VerifiedDownloader(client, files, chunkBytes = 1024, retryDelay = {}), fake, h.runtime.store, scope) to fake
    }

    @Test fun courseVideosAreDownloadedVerifiedAndPlayedFromTheLocalFile() = runTest {
        val h = newHarness()
        val files = InMemoryPrivateFileStore(durable = true)
        val (downloads, fake) = downloads(h, files, MockEngine { respond(ByteReadChannel(videoBytes), HttpStatusCode.OK) }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        downloads.refresh()
        downloads.download(ORG, courseWithVideo())
        awaitIdle(downloads)
        assertTrue(downloads.state.value.downloaded(MEDIA_ID))
        assertEquals(videoBytes.size.toLong(), downloads.state.value.usedBytes)
        assertEquals(1, fake.ticketCalls.size)
        assertNull(downloads.state.value.activeCourseId)
        // YouTube no genera descarga y el reproductor recibe el archivo local (en esta prueba el almacén no ofrece URI).
        assertNull(downloads.localVideo(LessonVideo(MEDIA_ID, "video/mp4", videoBytes.size.toLong(), videoSha)))
        downloads.delete(MEDIA_ID)
        assertEquals(0L, downloads.state.value.usedBytes)
        assertFalse(downloads.state.value.downloaded(MEDIA_ID))
    }

    @Test fun aCorruptVideoIsNotKeptAndTheIssueIsReported() = runTest {
        val h = newHarness()
        val files = InMemoryPrivateFileStore(durable = true)
        val (downloads, _) = downloads(h, files, MockEngine { respond(ByteReadChannel(videoBytes), HttpStatusCode.OK) }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        downloads.download(ORG, courseWithVideo(sha = "f".repeat(64)))
        awaitIdle(downloads)
        assertFalse(downloads.state.value.downloaded(MEDIA_ID))
        assertEquals(DownloadIssue.CORRUPT, downloads.state.value.of(MEDIA_ID)?.issue)
        assertEquals(0L, downloads.state.value.usedBytes)
    }

    @Test fun theConfiguredSpaceLimitStopsTheDownloadBeforeStartingIt() = runTest {
        val h = newHarness()
        val files = InMemoryPrivateFileStore(durable = true)
        var requests = 0
        val (downloads, fake) = downloads(h, files, MockEngine { requests++; respond(ByteReadChannel(videoBytes), HttpStatusCode.OK) }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        downloads.setLimit(videoBytes.size - 1L)
        downloads.download(ORG, courseWithVideo())
        awaitIdle(downloads)
        assertEquals(DownloadIssue.LIMIT, downloads.state.value.of(MEDIA_ID)?.issue)
        assertEquals(0, requests)
        assertTrue(fake.ticketCalls.isEmpty())
        // Con espacio suficiente la misma descarga sí termina.
        downloads.setLimit(videoBytes.size.toLong())
        downloads.download(ORG, courseWithVideo())
        awaitIdle(downloads)
        assertTrue(downloads.state.value.downloaded(MEDIA_ID))
    }

    @Test fun browsersWithoutDurableFilesDisableDownloads() = runTest {
        val h = newHarness()
        val (downloads, fake) = downloads(h, InMemoryPrivateFileStore(durable = false), MockEngine { respond("", HttpStatusCode.OK) }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default))
        assertFalse(downloads.state.value.supported)
        downloads.download(ORG, courseWithVideo())
        awaitIdle(downloads)
        assertTrue(fake.ticketCalls.isEmpty())
    }

    // ---- Videos del dispositivo: sobreviven al cierre de sesión, solo se ven con el catálogo propio -------------

    /** Almacén en memoria que ofrece URI, como el de archivos de Android o iOS. */
    private class UriStore(private val inner: InMemoryPrivateFileStore = InMemoryPrivateFileStore(durable = true)) : PrivateFileStore by inner {
        override suspend fun localUri(ref: String) = if (inner.size(ref) != null) "file:///vault/$ref" else null
    }

    private val ownVideo get() = LessonVideo(MEDIA_ID, "video/mp4", videoBytes.size.toLong(), videoSha)
    private val vaultRef get() = OfflineTrainingDownloads.ref(MEDIA_ID, "video/mp4", videoSha)

    private fun vaultDownloads(vault: PrivateFileStore, runtime: OfflineRuntime, catalog: () -> List<LessonVideo>, legacy: PrivateFileStore? = null): OfflineTrainingDownloads {
        val client = HttpClient(MockEngine { respond(ByteReadChannel(videoBytes), HttpStatusCode.OK) }) { expectSuccess = false; install(io.ktor.client.plugins.HttpTimeout) }
        return OfflineTrainingDownloads(vault, VerifiedDownloader(client, vault, chunkBytes = 1024, retryDelay = {}), FakeTrainingRepository(), runtime.store,
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default), catalog = { catalog() }, legacy = legacy)
    }

    private suspend fun activeRuntime(personal: PrivateFileStore, vault: PrivateFileStore, user: String = "user-1") =
        OfflineRuntime(OfflinePlatform(files = personal, videos = vault), { 1L }, { "op" }).also { it.activate(OfflineScope(API, user)) }

    @Test fun downloadedVideosSurviveLogoutButOnlyPlayWhenTheUsersOwnCatalogIncludesThem() = runTest {
        val vault = UriStore()
        val runtime = activeRuntime(InMemoryPrivateFileStore(durable = true), vault)
        var catalog = listOf(ownVideo)
        val downloads = vaultDownloads(vault, runtime, { catalog })
        downloads.bind(ORG)
        downloads.download(ORG, courseWithVideo())
        awaitIdle(downloads)
        assertEquals("file:///vault/$vaultRef", downloads.localVideo(ownVideo)?.url)
        assertTrue(downloads.state.value.downloaded(MEDIA_ID))

        // Cierre de sesión: se borran los datos personales, el video queda en el dispositivo.
        downloads.unbind()
        runtime.wipe()
        assertEquals(videoBytes.size.toLong(), vault.size(vaultRef))
        // Otra cuenta cuyo catálogo no incluye ese mediaId no lo ve ni lo reproduce.
        runtime.activate(OfflineScope(API, "user-2"))
        catalog = emptyList()
        downloads.bind(ORG)
        assertNull(downloads.localVideo(ownVideo))
        assertFalse(downloads.state.value.downloaded(MEDIA_ID))
        assertEquals(videoBytes.size.toLong(), downloads.state.value.usedBytes)
        // Sin catálogo cargado (bind pendiente) tampoco se reproduce.
        downloads.unbind()
        assertNull(downloads.localVideo(ownVideo))
        // Con el mismo mediaId pero otro SHA-256 en su catálogo, tampoco: es otro contenido.
        catalog = listOf(ownVideo.copy(sha256 = "f".repeat(64)))
        downloads.bind(ORG)
        assertNull(downloads.localVideo(ownVideo.copy(sha256 = "f".repeat(64))))
        // Una cuenta cuyo catálogo sí lo incluye lo reproduce sin volver a descargarlo.
        catalog = listOf(ownVideo)
        downloads.bind(ORG)
        assertEquals("file:///vault/$vaultRef", downloads.localVideo(ownVideo)?.url)
        assertTrue(downloads.state.value.downloaded(MEDIA_ID))
    }

    @Test fun deleteDownloadsEmptiesTheDeviceStoreIncludingOtherAccountsVideos() = runTest {
        val vault = UriStore()
        val runtime = activeRuntime(InMemoryPrivateFileStore(durable = true), vault)
        vault.save("video-otro-0123456789abcdef.mp4", ByteArray(10))
        val downloads = vaultDownloads(vault, runtime, { listOf(ownVideo) })
        downloads.bind(ORG)
        assertEquals(10L, downloads.state.value.usedBytes)
        downloads.deleteAll()
        assertTrue(vault.list().isEmpty())
    }

    @Test fun legacyDownloadsMoveToTheDeviceStoreWhenTheCatalogIdentifiesThem() = runTest {
        val personal = InMemoryPrivateFileStore(durable = true)
        val vault = InMemoryPrivateFileStore(durable = true)
        val runtime = activeRuntime(personal, vault)
        personal.save("video-$MEDIA_ID.mp4", videoBytes)
        personal.save("video-sin-catalogo.mp4", ByteArray(7))
        personal.save("foto-1.jpg", ByteArray(3))
        val downloads = vaultDownloads(vault, runtime, { listOf(ownVideo) }, legacy = personal)
        downloads.bind(ORG)
        assertEquals(videoBytes.size.toLong(), vault.size(vaultRef))
        assertNull(personal.size("video-$MEDIA_ID.mp4"))
        // Lo que el catálogo no identifica se descarta; las fotos personales no se tocan.
        assertNull(personal.size("video-sin-catalogo.mp4"))
        assertEquals(3L, personal.size("foto-1.jpg"))
        assertTrue(downloads.state.value.downloaded(MEDIA_ID))
    }

    @Test fun theSpaceLimitFreesVideosOutsideTheCatalogBeforeRefusingTheDownload() = runTest {
        val vault = InMemoryPrivateFileStore(durable = true)
        val runtime = activeRuntime(InMemoryPrivateFileStore(durable = true), vault)
        vault.save("video-ajeno-fedcba9876543210.mp4", ByteArray(5_000))
        val downloads = vaultDownloads(vault, runtime, { listOf(ownVideo) })
        downloads.setLimit(6_000)
        downloads.bind(ORG)
        downloads.download(ORG, courseWithVideo())
        awaitIdle(downloads)
        assertTrue(downloads.state.value.downloaded(MEDIA_ID))
        assertNull(vault.size("video-ajeno-fedcba9876543210.mp4"))
        // Un video del propio catálogo nunca se borra solo por el límite: la descarga se rechaza.
        downloads.setLimit(videoBytes.size - 1L)
        downloads.download(ORG, courseWithVideo().let { it.copy(lessons = listOf(it.lessons[0].copy(video = LessonVideo("70000000-0000-4000-8000-000000000002", "video/mp4", videoBytes.size.toLong(), videoSha)), it.lessons[1])) })
        awaitIdle(downloads)
        assertNotNull(vault.size(vaultRef))
    }

}
