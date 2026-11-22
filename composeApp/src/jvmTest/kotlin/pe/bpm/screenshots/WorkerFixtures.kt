package pe.bpm.screenshots

import pe.bpm.core.domain.Page
import pe.bpm.core.domain.PageRequest
import pe.bpm.core.domain.DraftOwner
import pe.bpm.features.incidents.data.prepareIncident
import pe.bpm.features.operations.data.encodeOperation
import pe.bpm.features.incidents.domain.*
import pe.bpm.features.operations.domain.*
import pe.bpm.features.training.domain.*
import pe.bpm.features.workspace.domain.Entry
import pe.bpm.features.workspace.domain.Location
import pe.bpm.features.workspace.domain.Organization
import pe.bpm.features.workspace.domain.Result
import pe.bpm.features.workspace.domain.Workspace
import pe.bpm.features.workspace.domain.WorkspaceItems
import pe.bpm.features.workspace.domain.WorkspaceRepository
import pe.bpm.features.workspace.domain.WorkspaceResource

/**
 * Datos de ejemplo (español, restaurante) y repositorios falsos para el arnés de capturas del personal.
 * Todo es en memoria y responde al instante: no hay red ni almacenamiento.
 */
internal object WorkerFixtures {
    const val USER = "a1000000-0000-4000-8000-000000000001"
    const val ORG = "b2000000-0000-4000-8000-000000000001"
    const val SITE = "c3000000-0000-4000-8000-000000000001"
    const val DISPLAY_NAME = "Lucía Quispe"

    val organization = Organization(ORG, "Inversiones Gastronómicas Andina S.A.C.", "Cocina Andina")
    val location = Location(SITE, "Sede Miraflores", "Av. Larco 345", "Miraflores", "01 555 0142", "America/Lima")

    // ---- Curso principal (HIG-101) ---------------------------------------------------------------

    const val COURSE = "10000000-0000-4000-8000-0000000000a1"
    const val VERSION = "20000000-0000-4000-8000-0000000000a1"
    val lessonIds = (1..5).map { "30000000-0000-4000-8000-00000000000$it" }
    val questionIds = (1..8).map { "40000000-0000-4000-8000-00000000000$it" }

    private val lessonTexts = listOf(
        "Lavado de manos correcto" to "Las manos son la principal vía de contaminación de los alimentos.\nMójalas, aplica jabón y frota palmas, dorso, entre los dedos y uñas durante 20 segundos.\nEnjuaga con agua corriente y sécalas con papel desechable.",
        "Contaminación cruzada" to "La contaminación cruzada ocurre cuando las bacterias pasan de un alimento crudo a otro listo para comer.\nUsa tablas y cuchillos distintos para carnes crudas, pescados y verduras.\nGuarda los alimentos crudos siempre debajo de los cocidos.",
        "Temperaturas de seguridad" to "Las bacterias se multiplican con rapidez entre 5 °C y 60 °C: la llamada zona de peligro.\nMantén los alimentos fríos a 5 °C o menos y los calientes a 60 °C o más.\nCocina las aves hasta que el centro alcance 74 °C y mide con un termómetro limpio.\n\nNunca dejes alimentos perecibles más de dos horas a temperatura ambiente.",
        "Limpieza y desinfección" to "Limpiar quita la suciedad visible; desinfectar reduce los microbios.\nPrimero lava con detergente, enjuaga y luego aplica el desinfectante autorizado respetando el tiempo de contacto.",
        "Almacenamiento PEPS" to "PEPS significa primero en entrar, primero en salir.\nRotula cada producto con la fecha de ingreso y coloca lo más antiguo al frente.",
    )

    private val questionTexts = listOf(
        Triple("¿Cuánto tiempo debe durar como mínimo el lavado de manos?", listOf("5 segundos", "10 segundos", "20 segundos", "1 minuto"), 2),
        Triple("¿Cuándo debes lavarte las manos?", listOf("Solo al empezar el turno", "Después de tocar basura, dinero o el celular", "Solo si se ven sucias"), 1),
        Triple("¿Qué es la contaminación cruzada?", listOf("El paso de bacterias de un alimento crudo a otro listo para comer", "Mezclar dos recetas distintas", "Cocinar con demasiado aceite"), 0),
        Triple("¿Qué tabla usarías para cortar pollo crudo?", listOf("La verde, de verduras", "La roja, de carnes crudas", "Cualquiera, si la enjuago"), 1),
        Triple("¿Cuál es la zona de peligro de temperatura para los alimentos?", listOf("Entre 5 °C y 60 °C", "Entre -18 °C y 0 °C", "Entre 70 °C y 90 °C"), 0),
        Triple("¿A qué temperatura interna debe cocinarse el pollo?", listOf("55 °C", "74 °C", "40 °C"), 1),
        Triple("¿Cuál es el orden correcto para limpiar una superficie?", listOf("Desinfectar y luego lavar", "Lavar con detergente, enjuagar y desinfectar", "Solo pasar un paño húmedo"), 1),
        Triple("¿Qué significa PEPS en el almacenamiento?", listOf("Primero en entrar, primero en salir", "Productos de empaque seguro", "Pedido especial por semana"), 0),
    )

    /** Lección de cada pregunta: dos por lección salvo la 4 y la 5 (una cada una). */
    private val questionLesson = listOf(0, 0, 1, 1, 2, 2, 3, 4)
    val correctOptions get() = questionTexts.map { it.third }
    val explanations = listOf(
        "El jabón necesita al menos 20 segundos de fricción para arrastrar los microbios.",
        "Las manos se contaminan con cada superficie que tocas; lávalas siempre entre tareas.",
        "Es la causa más frecuente de intoxicaciones en cocina.",
        "Los colores separan las tablas por tipo de alimento y evitan la contaminación cruzada.",
        "La zona de peligro va de 5 °C a 60 °C: ahí las bacterias se multiplican más rápido.",
        "A 74 °C en el centro se eliminan las bacterias más comunes del pollo.",
        "El detergente quita la grasa y la suciedad; el desinfectante actúa sobre una superficie limpia.",
        "Así se consume primero lo que ingresó antes y se evitan vencimientos.",
    )

    fun course(progress: TrainingProgress = inProgress): TrainingCourse = TrainingCourse(
        TrainingCourseInfo(COURSE, "HIG-101", "Higiene y manipulación de alimentos",
            "Buenas prácticas de higiene para quien prepara, almacena y sirve alimentos en cocina.", 30),
        TrainingVersionInfo(VERSION, 3, 80),
        lessonTexts.mapIndexed { i, (title, content) ->
            TrainingLesson(lessonIds[i], title, content, i + 1,
                video = if (i == 0) LessonVideo("70000000-0000-4000-8000-00000000000${i + 1}", "video/mp4", 18_400_000) else null,
                keyPoints = if (i == 2) listOf(
                    "Zona de peligro: entre 5 °C y 60 °C.",
                    "Frío a 5 °C o menos; caliente a 60 °C o más.",
                    "El pollo se cocina hasta 74 °C en el centro.",
                ) else emptyList())
        },
        questionTexts.mapIndexed { i, (text, options, _) -> TrainingQuestion(questionIds[i], lessonIds[questionLesson[i]], text, options, i + 1) },
        progress, null,
    )

    val inProgress = TrainingProgress(ProgressStatus.IN_PROGRESS, lessonIds[2], 72, 60, null,
        completedLessonIds = lessonIds.take(2), completedLessons = 2, totalLessons = 5, percent = 40)

    fun result(attempt: AttemptSubmission, wrong: Set<Int> = setOf(5)): TrainingResult<AttemptOutcome> {
        val results = attempt.answers.mapIndexed { i, a ->
            val index = questionIds.indexOf(a.questionId)
            QuestionResult(a.questionId, a.selectedOption, index !in wrong, correctOptions[index], explanations[index])
        }
        return TrainingResult.Success(AttemptOutcome(
            AttemptSummary(attempt.id, attempt.attemptType, 88, true, "2026-11-20T15:42:00Z", xpEarned = 120),
            results,
            TrainingProgress(ProgressStatus.COMPLETED, null, 88, 180, "2026-11-20T15:42:00Z", completedLessonIds = lessonIds,
                completedLessons = 5, totalLessons = 5, percent = 100),
            TrainingCertificate("80000000-0000-4000-8000-0000000000a1", "K7M2QXD4WPLR5TNB3HVZ6CJA", "2026-11-20T15:42:00Z"),
            achievements = listOf(Achievement("90000000-0000-4000-8000-000000000001", "FIRST_COURSE", "Primer curso completado",
                "Terminaste tu primera capacitación.", 50, AchievementCriterion.COURSES_COMPLETED, 1, true, "2026-11-20T15:42:00Z")),
            nextCourse = NextCourse("p0000000-0000-4000-8000-000000000001", "Ruta de inicio en cocina", "10000000-0000-4000-8000-0000000000a2", "Alérgenos: qué debes saber"),
        ))
    }

    // ---- Asignaciones, logros y rutas -------------------------------------------------------------

    private fun info(n: Int, code: String, title: String, minutes: Int) =
        TrainingCourseInfo(if (n == 1) COURSE else "10000000-0000-4000-8000-0000000000a$n", code, title, "", minutes)

    val assignments = listOf(
        MyAssignment("d0000000-0000-4000-8000-000000000001", info(1, "HIG-101", "Higiene y manipulación de alimentos", 30), "2026-11-25T23:59:00Z", "2026-11-10T14:00:00Z",
            AssignmentProgress.IN_PROGRESS, ProgressStatus.IN_PROGRESS, 72, null, percent = 40),
        MyAssignment("d0000000-0000-4000-8000-000000000002", info(2, "ALE-110", "Alérgenos: qué debes saber", 20), "2026-11-15T23:59:00Z", "2026-11-05T14:00:00Z",
            AssignmentProgress.OVERDUE, ProgressStatus.NOT_STARTED, null, null),
        MyAssignment("d0000000-0000-4000-8000-000000000003", info(3, "TEM-120", "Control de temperaturas en cocina", 25), "2026-12-02T23:59:00Z", "2026-11-18T14:00:00Z",
            AssignmentProgress.PENDING, ProgressStatus.NOT_STARTED, null, null),
        MyAssignment("d0000000-0000-4000-8000-000000000004", info(4, "SEG-100", "Seguridad en el uso de cuchillos", 15), null, "2026-10-20T14:00:00Z",
            AssignmentProgress.COMPLETED, ProgressStatus.COMPLETED, 92, "2026-10-28T16:30:00Z", percent = 100),
    )

    private fun achievement(n: Int, name: String, points: Int, criterion: AchievementCriterion, threshold: Int, earned: Boolean) =
        Achievement("90000000-0000-4000-8000-00000000000$n", "ACH_$n", name, "", points, criterion, threshold, true, if (earned) "2026-11-0${n}T12:00:00Z" else null)

    val achievements = MyAchievements(
        ExperienceTotals(560, 400, 60, 100),
        listOf(
            achievement(1, "Primer curso completado", 50, AchievementCriterion.COURSES_COMPLETED, 1, true),
            achievement(2, "Puntaje perfecto", 50, AchievementCriterion.PERFECT_SCORE, 1, true),
            achievement(3, "Racha de 3 días", 30, AchievementCriterion.STREAK_DAYS, 3, true),
            achievement(4, "Racha de 7 días", 70, AchievementCriterion.STREAK_DAYS, 7, false),
            achievement(5, "Tres cursos completados", 100, AchievementCriterion.COURSES_COMPLETED, 3, false),
        ),
        streak = LearningStreak(6, 9, "2026-11-20", true, "America/Lima"),
    )

    val paths = listOf(LearningPath("p0000000-0000-4000-8000-000000000001", "Ruta de inicio en cocina",
        "Los cursos básicos para tu primer mes.", PathStatus.PUBLISHED, "v1", "2026-10-01T12:00:00Z",
        listOf(
            PathCourse(COURSE, "HIG-101", "Higiene y manipulación de alimentos", 1, CourseStatus.PUBLISHED, ProgressStatus.IN_PROGRESS),
            PathCourse("10000000-0000-4000-8000-0000000000a2", "ALE-110", "Alérgenos: qué debes saber", 2, CourseStatus.PUBLISHED, ProgressStatus.NOT_STARTED),
        ), PathProgress(ProgressStatus.IN_PROGRESS, 0, 2, 20, COURSE)))

    /** Un curso asignado con su propio avance: el principal en curso, el resto sin iniciar y el último completado. */
    fun courseFor(courseId: String): TrainingCourse {
        val base = course()
        val assignment = assignments.firstOrNull { it.course.id == courseId } ?: return base
        return when (assignment.progressStatus) {
            ProgressStatus.IN_PROGRESS -> base
            ProgressStatus.COMPLETED -> base.copy(course = assignment.course, progress = TrainingProgress(ProgressStatus.COMPLETED, null, 92, 120, assignment.completedAt,
                completedLessonIds = lessonIds, completedLessons = 5, totalLessons = 5, percent = 100))
            ProgressStatus.NOT_STARTED -> base.copy(course = assignment.course, lessons = base.lessons.take(3), progress = TrainingProgress(ProgressStatus.NOT_STARTED, null, null, 0, null,
                totalLessons = 3))
        }
    }

    /** Repositorio de cursos para el camino: devuelve el curso pedido con el avance de su asignación. */
    class CourseRepository : TrainingRepository {
        override suspend fun course(org: String, courseId: String) = TrainingResult.Success(courseFor(courseId))
        override suspend fun videoTicket(org: String, courseId: String, lessonId: String): TrainingResult<VideoTicket> = TrainingResult.Failure(TrainingFailure.UNAVAILABLE)
        override suspend fun saveProgress(org: String, courseId: String, lessonId: String, completedLessonId: String?): TrainingResult<TrainingProgress> = TrainingResult.Failure(TrainingFailure.UNAVAILABLE)
        override suspend fun checkStudyAnswer(org: String, courseId: String, questionId: String, selected: Int): TrainingResult<StudyFeedback> = TrainingResult.Failure(TrainingFailure.UNAVAILABLE)
        override suspend fun submitAttempt(org: String, courseId: String, attempt: AttemptSubmission): TrainingResult<AttemptOutcome> = TrainingResult.Failure(TrainingFailure.UNAVAILABLE)
    }

    class LearnerRepository : TrainingLearnerRepository {
        override suspend fun myAssignments(org: String, pageToken: String?) = TrainingResult.Success(TrainingPage(assignments, null))
        override suspend fun myAchievements(org: String) = TrainingResult.Success(achievements)
        override suspend fun paths(org: String, pageToken: String?) = TrainingResult.Success(TrainingPage(paths, null))
        override suspend fun path(org: String, pathId: String) = TrainingResult.Success(paths.first())
    }

    // ---- Espacio de trabajo (catálogo de cursos del WORKER) ---------------------------------------

    val catalog = listOf(
        Entry(COURSE, "Higiene y manipulación de alimentos", "Buenas prácticas de higiene.", "IN_PROGRESS"),
        Entry("10000000-0000-4000-8000-0000000000a2", "Alérgenos: qué debes saber", "Identifica y evita la contaminación con alérgenos.", "NOT_STARTED"),
        Entry("10000000-0000-4000-8000-0000000000a5", "Atención al cliente en sala", "Cómo recibir, atender y despedir a cada comensal con calidez.", "NOT_STARTED"),
        Entry("10000000-0000-4000-8000-0000000000a6", "Manejo de residuos y reciclaje", "Clasifica y retira los residuos de cocina sin riesgo de plagas.", "IN_PROGRESS"),
        Entry("10000000-0000-4000-8000-0000000000a7", "Primeros auxilios básicos", "Qué hacer ante cortes, quemaduras y atragantamientos.", "COMPLETED"),
        Entry("10000000-0000-4000-8000-0000000000a8", "Cadena de frío y recepción de mercadería", "Verifica temperaturas y estado de los productos al recibirlos del proveedor.", "NOT_STARTED"),
    )

    val workspace = Workspace(organization, "WORKER", listOf(location), emptyList(), catalog, emptyList(), emptyList())

    /** Solo se usa para construir el `WorkspaceViewModel` que `DailyOperationsContent` recibe sin consultarlo. */
    object IdleWorkspaceRepository : WorkspaceRepository {
        override suspend fun organizations(request: PageRequest) = Result.Success(Page(listOf(organization)))
        override suspend fun page(organizationId: String, resource: WorkspaceResource, request: PageRequest): Result<WorkspaceItems> = Result.Failure(emptyList())
        override suspend fun workspace(id: String) = Result.Success(workspace)
        override suspend fun createOrganization(input: Organization) = Result.Success(Unit)
        override suspend fun createLocation(organizationId: String, input: Location) = Result.Success(Unit)
        override suspend fun addMember(organizationId: String, email: String, role: String, locations: List<String>) = Result.Success(Unit)
    }

    // ---- Tareas del turno ---------------------------------------------------------------------------

    private fun today(done: Boolean = false, byMe: Boolean = false, overdue: Boolean = false, shift: String? = null, result: CleaningResult? = null, execution: Int = 0) =
        CleaningPlanToday("2026-11-20T05:00:00Z", "2026-11-21T05:00:00Z", "America/Lima", done, byMe,
            lastExecutionId = if (done) "e2000000-0000-4000-8000-00000000000$execution" else null,
            lastResult = result, lastExecutedAt = if (done) "2026-11-20T13:05:00Z" else null, overdue = overdue,
            shift = shift?.let { CleaningPlanShift("f0000000-0000-4000-8000-000000000001", it, "OPEN") })

    private fun plan(n: Int, area: String, task: String, due: String, frequency: CleaningFrequency, today: CleaningPlanToday? = null) =
        CleaningPlan("e1000000-0000-4000-8000-00000000000$n", SITE, area, task, "", "", frequency, due, true, today)

    val todayPlans = listOf(
        plan(1, "Cocina", "Limpiar y desinfectar tablas de corte", "10:00", CleaningFrequency.DAILY, today(done = true, byMe = true, result = CleaningResult.COMPLETED, execution = 1)),
        plan(2, "Cocina", "Desinfectar superficies de preparación", "08:30", CleaningFrequency.DAILY, today(overdue = true)),
        plan(3, "Cocina", "Limpieza de campana extractora", "", CleaningFrequency.WEEKLY, today()),
        plan(4, "Almacén", "Limpiar refrigeradora de la cámara", "17:00", CleaningFrequency.DAILY, today()),
        plan(5, "Salón", "Lavar y desinfectar baños", "", CleaningFrequency.PER_SHIFT, today(shift = "Turno tarde")),
        plan(6, "Salón", "Barrer y trapear el comedor", "15:30", CleaningFrequency.DAILY, today(done = true, result = CleaningResult.OBSERVED, execution = 2)),
        plan(7, "Almacén", "Descongelar y limpiar la congeladora", "", CleaningFrequency.MONTHLY, today()),
    )

    private fun execution(n: Int, plan: CleaningPlan, hour: String, result: CleaningResult, observation: String = "") = CleaningExecution(
        CleaningExecutionInput("e2000000-0000-4000-8000-00000000000$n", SITE, plan.id, "2026-11-20T${hour}:00Z", result, observation, "op-$n", "2026-11-20T${hour}:00Z"),
        plan.area, plan.task, USER, DISPLAY_NAME, "2026-11-20T${hour}:05:00Z")

    val executions = listOf(
        execution(1, todayPlans[0], "13:05", CleaningResult.COMPLETED),
        execution(2, todayPlans[5], "12:40", CleaningResult.OBSERVED, "Falta detergente en el dispensador."),
        execution(3, todayPlans[2], "11:15", CleaningResult.COMPLETED),
        execution(4, todayPlans[3], "09:50", CleaningResult.NOT_COMPLETED, "La cámara estaba llena; se hará mañana."),
        execution(5, todayPlans[4], "08:10", CleaningResult.COMPLETED),
    )

    /** [empty] simula una sede sin tareas para hoy («¡Todo al día!»). */
    class OperationsFixtureRepository(private val empty: Boolean = false) : OperationsRepository {
        override suspend fun authorize(org: String, site: String?, role: String, resource: OperationsResource) = OperationsResult.Success(Unit)
        override suspend fun suppliers(org: String, page: PageRequest): OperationsResult<Page<Supplier>> = OperationsResult.Success(Page(emptyList()))
        override suspend fun products(org: String, page: PageRequest): OperationsResult<Page<Product>> = OperationsResult.Success(Page(emptyList()))
        override suspend fun receipts(org: String, site: String, page: PageRequest): OperationsResult<Page<SupplyReceipt>> = OperationsResult.Success(Page(emptyList()))
        override suspend fun lots(org: String, site: String, page: PageRequest): OperationsResult<Page<StorageLot>> = OperationsResult.Success(Page(emptyList()))
        override suspend fun plans(org: String, site: String, page: PageRequest) = OperationsResult.Success(Page(todayPlans.map { it.copy(today = null) }))
        override suspend fun todayPlans(org: String, site: String, page: PageRequest) = OperationsResult.Success(Page(if (empty) emptyList() else WorkerFixtures.todayPlans))
        override suspend fun executions(org: String, site: String, page: PageRequest) = OperationsResult.Success(Page(WorkerFixtures.executions))
        override suspend fun receipt(org: String, id: String): OperationsResult<SupplyReceipt> = OperationsResult.Failure(OperationsFailure.NOT_FOUND)
        override suspend fun execution(org: String, id: String): OperationsResult<CleaningExecution> = OperationsResult.Failure(OperationsFailure.NOT_FOUND)
        override suspend fun createSupplier(org: String, input: Supplier, key: String): OperationsResult<Supplier> = OperationsResult.Failure(OperationsFailure.DENIED)
        override suspend fun createProduct(org: String, input: Product, key: String): OperationsResult<Product> = OperationsResult.Failure(OperationsFailure.DENIED)
        override suspend fun updatePolicy(org: String, id: String, input: TemperaturePolicy, key: String): OperationsResult<Product> = OperationsResult.Failure(OperationsFailure.DENIED)
        override suspend fun createReceipt(org: String, input: ReceiptInput, key: String): OperationsResult<SupplyReceipt> = OperationsResult.Failure(OperationsFailure.DENIED)
        override suspend fun createPlan(org: String, input: CleaningPlan, key: String): OperationsResult<CleaningPlan> = OperationsResult.Failure(OperationsFailure.DENIED)
        override suspend fun createExecution(org: String, input: CleaningExecutionInput, key: String): OperationsResult<CleaningExecution> = OperationsResult.Failure(OperationsFailure.DENIED)
    }

    /**
     * Transporte que confirma el envío de una limpieza al instante (la celebración de «¡Tarea hecha!» sale del flujo
     * real: validar, guardar el borrador, enviar y recibir la ejecución). El resto de operaciones se rechaza.
     */
    object NoopTransport : OperationsTransport {
        private val prepared = mutableMapOf<String, OperationsCommand>()
        override fun prepare(command: OperationsCommand, key: String): PendingOperation {
            prepared[key] = command
            // El borrador valida que el envío pendiente sea exactamente la codificación real del comando.
            return encodeOperation(command, key)
        }
        override suspend fun submit(org: String, pending: PendingOperation): OperationsResult<OperationsEntry> {
            val input = (prepared[pending.key] as? OperationsCommand.Execute)?.input ?: return OperationsResult.Failure(OperationsFailure.DENIED)
            val plan = todayPlans.first { it.id == input.cleaningPlanId }
            return OperationsResult.Success(OperationsEntry.ExecutionEntry(
                CleaningExecution(input, plan.area, plan.task, USER, DISPLAY_NAME, input.executedAt)))
        }
    }

    // ---- Incidencias (Reportar) ---------------------------------------------------------------------

    private fun catalogItem(n: Int, name: String, area: String, risk: IncidentRisk, evidence: Boolean = true) =
        IncidentCatalog("i1000000-0000-4000-8000-00000000000$n", "CAT-$n", name, area, "Criterio normativo de $name.", risk, evidence, 1)

    val incidentCatalog = listOf(
        catalogItem(1, "Temperatura fuera de rango", "Cocina", IncidentRisk.HIGH),
        catalogItem(2, "Plagas", "General", IncidentRisk.CRITICAL),
        catalogItem(3, "Equipo malogrado", "Cocina", IncidentRisk.MEDIUM, evidence = false),
        catalogItem(4, "Insumo en mal estado", "Almacén", IncidentRisk.HIGH),
        catalogItem(5, "Agua o desagüe", "General", IncidentRisk.MEDIUM),
        catalogItem(6, "Otro problema", "General", IncidentRisk.LOW, evidence = false),
    )

    /** Repositorio, transporte y borradores de incidencias en memoria para el arnés de capturas. */
    class IncidentsFixture : IncidentsRepository, IncidentTransport, IncidentDraftRepository {
        override suspend fun authorize(org: String, site: String, role: String) = IncidentsResult.Success(Unit)
        override suspend fun catalog(org: String, page: PageRequest) = IncidentsResult.Success(Page(incidentCatalog))
        override suspend fun incidents(org: String, site: String, status: IncidentStatus?, page: PageRequest) = IncidentsResult.Success(Page<IncidentSummary>(emptyList()))
        override suspend fun detail(org: String, id: String): IncidentsResult<IncidentDetail> = IncidentsResult.Failure(IncidentsFailure.NOT_FOUND)
        override suspend fun assignees(org: String, id: String, page: PageRequest) = IncidentsResult.Success(Page<IncidentAssignee>(emptyList()))
        override suspend fun history(org: String, id: String, page: PageRequest) = IncidentsResult.Success(Page<IncidentEvent>(emptyList()))
        override suspend fun read(owner: DraftOwner, site: String): IncidentDraft? = null
        override suspend fun write(owner: DraftOwner, site: String, draft: IncidentDraft?) = Unit
        override fun prepare(form: IncidentForm, site: String, key: String) = prepareIncident(form, site, key)
        override suspend fun submit(org: String, site: String, form: IncidentForm, pending: PendingIncident): IncidentsResult<IncidentSaved> = IncidentsResult.Failure(IncidentsFailure.UNAVAILABLE)
    }
}
