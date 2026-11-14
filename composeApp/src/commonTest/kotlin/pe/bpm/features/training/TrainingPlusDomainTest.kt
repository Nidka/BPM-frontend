package pe.bpm.features.training

import kotlin.test.*
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest
import pe.bpm.features.training.domain.*
import pe.bpm.features.training.presentation.AssignmentForm
import pe.bpm.features.training.presentation.AssignmentFormRules
import pe.bpm.features.training.presentation.TargetKind

class TrainingPlusDomainTest {
    private val now = Instant.parse("2026-11-10T12:00:00Z")

    @Test fun youTubeLinksAcceptOnlySupportedFormats() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id", "http://youtube.com/watch?v=$id&t=10", "https://m.youtube.com/watch?feature=x&v=$id",
            "https://youtu.be/$id", "https://youtu.be/$id?si=abc", "https://www.youtube.com/shorts/$id", "https://www.youtube-nocookie.com/embed/$id", "  https://youtu.be/$id  ",
        ).forEach { assertEquals(id, YouTubeLinks.idFromUrl(it), it) }
        listOf(
            "", "dQw4w9WgXcQ", "https://vimeo.com/$id", "https://evil.com/watch?v=$id", "https://youtube.com.evil.com/watch?v=$id", "https://user:pw@youtu.be/$id",
            "https://youtu.be:8080/$id", "https://www.youtube.com/watch?v=corto", "https://www.youtube.com/watch", "ftp://youtu.be/$id", "javascript:alert(1)",
            "https://www.youtube.com/embed/$id", "https://youtu.be/${id}extra",
        ).forEach { assertNull(YouTubeLinks.idFromUrl(it), it) }
        assertEquals("https://www.youtube-nocookie.com/embed/$id", ExternalVideo(id).embedUrl)
        assertFailsWith<IllegalArgumentException> { YouTubeLinks.embedUrl("x") }
    }

    @Test fun draftEditsKeepPrivateAndYouTubeVideosExclusive() {
        val draft = sampleDraft()
        val withMedia = TrainingDraftEdits.setLessonVideo(draft, LESSON_1, MEDIA_ID)
        val external = TrainingDraftEdits.setExternalVideo(withMedia, LESSON_1, "dQw4w9WgXcQ")
        assertNull(external.lessons[0].videoMediaId)
        assertEquals("dQw4w9WgXcQ", external.lessons[0].externalVideoId)
        val back = TrainingDraftEdits.setLessonVideo(external, LESSON_1, MEDIA_ID)
        assertNull(back.lessons[0].externalVideoId)
        assertEquals(MEDIA_ID, back.lessons[0].videoMediaId)
        assertNull(TrainingDraftEdits.setExternalVideo(external, LESSON_1, null).lessons[0].externalVideoId)
        // El borrador con ambos o con un identificador inválido no pasa la validación.
        val both = draft.copy(lessons = listOf(draft.lessons[0].copy(videoMediaId = MEDIA_ID, externalVideoId = "dQw4w9WgXcQ")))
        assertTrue(TrainingDraftRules.validate(both).any { it.kind == DraftIssueKind.EXTERNAL_VIDEO })
        val bad = draft.copy(lessons = listOf(draft.lessons[0].copy(externalVideoId = "x")))
        assertTrue(TrainingDraftRules.validate(bad).any { it.kind == DraftIssueKind.EXTERNAL_VIDEO })
        assertTrue(TrainingDraftRules.validate(external).isEmpty())
    }

    @Test fun moduleAttemptsOnlyCoverTheirLessonQuestions() {
        val course = sampleCourse()
        val use = SubmitTrainingAttempt(FakeTrainingRepository())
        var n = 0
        val newId = { "00000000-0000-4000-8000-${(++n).toString().padStart(12, '0')}" }
        val module = use.prepare(course, AttemptType.MODULE, mapOf(QUESTION_1 to 1), newId, STAMP, STAMP, LESSON_1)
        assertNotNull(module)
        assertEquals(LESSON_1, module.lessonId)
        assertEquals(listOf(QUESTION_1), module.answers.map { it.questionId })
        // Responder una pregunta de otra lección, omitir una o no indicar lección se rechaza.
        assertNull(use.prepare(course, AttemptType.MODULE, mapOf(QUESTION_1 to 1, QUESTION_2 to 0), newId, STAMP, STAMP, LESSON_1))
        assertNull(use.prepare(course, AttemptType.MODULE, mapOf(QUESTION_2 to 0), newId, STAMP, STAMP, LESSON_1))
        assertNull(use.prepare(course, AttemptType.MODULE, mapOf(QUESTION_1 to 1), newId, STAMP, STAMP, null))
        assertNull(use.prepare(course, AttemptType.FINAL, mapOf(QUESTION_1 to 1, QUESTION_2 to 0), newId, STAMP, STAMP, LESSON_1))
        assertNull(use.prepare(course, AttemptType.MODULE, mapOf(QUESTION_1 to 1), newId, STAMP, STAMP, "30000000-0000-4000-8000-000000000999"))
        // Una lección sin preguntas no se puede evaluar.
        assertNull(use.prepare(course, AttemptType.MODULE, emptyMap(), newId, STAMP, STAMP, LESSON_2))
        assertNotNull(use.prepare(course, AttemptType.FINAL, mapOf(QUESTION_1 to 1, QUESTION_2 to 0), newId, STAMP, STAMP))
    }

    @Test fun achievementAndPathRulesFollowTheContract() {
        val ok = AchievementEdit("Primer curso", "", 10, 1, true)
        assertTrue(AchievementRules.validate(ok).isEmpty())
        assertTrue(AchievementRules.validate(ok.copy(name = "x")).isNotEmpty())
        assertTrue(AchievementRules.validate(ok.copy(points = 1001)).isNotEmpty())
        assertTrue(AchievementRules.validate(ok.copy(criterionThreshold = 0)).isNotEmpty())
        assertTrue(AchievementRules.validate(ok.copy(description = "x".repeat(301))).isNotEmpty())
        val path = PathInput("p", "Inducción", "", listOf(COURSE_ID))
        assertTrue(PathRules.validate(path).isEmpty())
        assertTrue(PathRules.validate(path.copy(courseIds = listOf(COURSE_ID, COURSE_ID.uppercase()))).isNotEmpty())
        assertTrue(PathRules.validate(path.copy(courseIds = List(31) { "id$it" })).isNotEmpty())
        assertTrue(PathRules.validate(path.copy(title = "a")).isNotEmpty())
    }

    @Test fun certificateCodesAreNormalizedAndValidatedBeforeAnyRequest() = runTest {
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWX", CertificateCodeRules.normalize(" abcd-efgh ijkl-mnop-qrst-uvwx "))
        assertTrue(CertificateCodeRules.isValid("ABCDEFGHIJKLMNOPQRSTUVWX"))
        assertFalse(CertificateCodeRules.isValid("ABCDEFGHIJKLMNOPQRSTUVW1"))
        var calls = 0
        val repo = object : CertificateVerificationRepository {
            override suspend fun verify(code: String): TrainingResult<CertificateVerification> {
                calls++
                return TrainingResult.Success(CertificateVerification(CertificateStatus.VALID, "Curso", 1, STAMP, "Org", "Ana P."))
            }
        }
        assertEquals(TrainingResult.Failure(TrainingFailure.NOT_FOUND), VerifyTrainingCertificate(repo)("corto"))
        assertEquals(0, calls)
        assertIs<TrainingResult.Success<CertificateVerification>>(VerifyTrainingCertificate(repo)("abcdefghijklmnopqrstuvwx"))
        assertEquals(1, calls)
    }

    @Test fun assignmentDueDateRules() {
        assertEquals(null, AssignmentFormRules.dueAt("", now).getOrThrow())
        // 23:59 en Lima (UTC-5) equivale a las 04:59 UTC del día siguiente, también en fin de mes y de año.
        assertEquals("2027-01-01T04:59:00Z", AssignmentFormRules.dueAt("2026-12-31", now).getOrThrow())
        assertEquals("2026-12-01T04:59:00Z", AssignmentFormRules.dueAt("2026-11-30", now).getOrThrow())
        assertEquals("2028-03-01T04:59:00Z", AssignmentFormRules.dueAt("2028-02-29", now).getOrThrow())
        assertEquals("2026-11-21T04:59:00Z", AssignmentFormRules.dueAt("2026-11-20", now).getOrThrow())
        listOf("31/12/2026", "2026-13-40", "2026-11-09", "2040-01-01").forEach { assertTrue(AssignmentFormRules.dueAt(it, now).isFailure, it) }
        val form = AssignmentForm(courseId = COURSE_ID, kind = TargetKind.USER)
        assertEquals(setOf("target"), AssignmentFormRules.issues(form, now).keys)
        assertTrue(AssignmentFormRules.issues(form.copy(userId = "uid"), now).isEmpty())
        assertEquals(setOf("course", "target"), AssignmentFormRules.issues(AssignmentForm(kind = TargetKind.LOCATION), now).keys)
        assertTrue(AssignmentFormRules.issues(AssignmentForm(courseId = COURSE_ID, kind = TargetKind.ROLE), now).isEmpty())
        assertIs<AssignmentTarget.Role>(AssignmentFormRules.target(AssignmentForm(kind = TargetKind.ROLE, role = "CHEF", locationId = "l")))
    }

    @Test fun invalidAssignmentTargetsNeverReachTheRepository() = runTest {
        val repo = FakePlusRepository()
        val create = CreateTrainingAssignment(repo)
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID), create("org", NewAssignment("a", COURSE_ID, AssignmentTarget.Role("DUEÑO"), null)))
        assertEquals(TrainingResult.Failure(TrainingFailure.INVALID), create("org", NewAssignment("a", COURSE_ID, AssignmentTarget.User(" "), null)))
        assertTrue(repo.created.isEmpty())
        assertIs<TrainingResult.Success<Assignment>>(create("org", NewAssignment("a", COURSE_ID, AssignmentTarget.Role("CHEF"), null)))
        assertEquals(1, repo.created.size)
    }
}
