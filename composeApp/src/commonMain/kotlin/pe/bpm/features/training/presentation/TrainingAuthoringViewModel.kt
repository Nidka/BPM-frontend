package pe.bpm.features.training.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pe.bpm.features.training.domain.*

/** Estado del editor de borrador; [draft] es la copia local editable. */
data class DraftEditorState(
    val draft: TrainingDraft,
    val dirty: Boolean = false,
    /** Los problemas de validación se muestran tras intentar guardar o publicar. */
    val showIssues: Boolean = false,
    val saving: Boolean = false,
    val publishing: Boolean = false,
    val failure: TrainingFailure? = null,
    val uploadLessonId: String? = null,
    val uploadProgress: Float = 0f,
    val uploadFailure: String? = null,
    /** Hay un archivo en memoria que se puede reintentar con la misma reserva. */
    val canRetryUpload: Boolean = false,
) {
    val issues: List<DraftIssue> get() = TrainingDraftRules.validate(draft)
    val uploading: Boolean get() = uploadLessonId != null && !canRetryUpload && uploadFailure == null
    val busy: Boolean get() = saving || publishing || uploading
}

data class NewCourseForm(val code: String = "", val title: String = "", val description: String = "", val minutes: String = "15", val showIssues: Boolean = false) {
    fun toNewCourse(): NewCourse = NewCourse(code.trim(), title.trim(), description, minutes.trim().toIntOrNull() ?: 0)
    val issues: List<DraftIssue> get() = TrainingDraftRules.validateNewCourse(toNewCourse())
}

data class AuthoringState(
    val userId: String? = null,
    val organizationId: String? = null,
    val allowed: Boolean = false,
    val loading: Boolean = false,
    val failure: TrainingFailure? = null,
    val courses: List<AdminCourse> = emptyList(),
    val statusFilter: CourseStatus? = null,
    val creating: NewCourseForm? = null,
    val creatingBusy: Boolean = false,
    val createFailure: TrainingFailure? = null,
    val editor: DraftEditorState? = null,
    val loadingDraft: Boolean = false,
    val notice: String? = null,
)

/** Autoría de cursos para ADMIN/SUPER_ADMIN: lista, alta, edición del borrador, videos y publicación. */
class TrainingAuthoringViewModel(
    private val listCourses: ListAdminCourses,
    private val createCourse: CreateTrainingCourse,
    private val loadDraft: LoadTrainingDraft,
    private val saveDraft: SaveTrainingDraft,
    private val publishCourse: PublishTrainingCourse,
    private val uploadVideo: UploadLessonVideo,
    private val newId: () -> String,
) : ViewModel() {
    private val mutable = MutableStateFlow(AuthoringState())
    val state: StateFlow<AuthoringState> = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private var uploadJob: Job? = null
    /** Archivo y reserva del último intento; se conservan para reintentar sin crear otro objeto. */
    private var pendingUpload: PendingUpload? = null

    private class PendingUpload(val lessonId: String, val mediaId: String, val video: PickedVideo)

    private fun wipeUpload() {
        pendingUpload?.video?.bytes?.fill(0)
        pendingUpload = null
    }

    fun reset() {
        generation++
        job?.cancel(); uploadJob?.cancel()
        wipeUpload()
        mutable.value = AuthoringState()
    }

    fun open(userId: String, organizationId: String, allowed: Boolean) {
        val s = mutable.value
        if (s.userId == userId && s.organizationId == organizationId && s.allowed == allowed) return
        reset()
        mutable.value = AuthoringState(userId, organizationId, allowed)
        if (allowed) refresh()
    }

    private fun scoped(): Pair<AuthoringState, String>? {
        val s = mutable.value
        return if (s.allowed && s.organizationId != null) s to s.organizationId else null
    }

    fun refresh() {
        val (s, org) = scoped() ?: return
        job?.cancel()
        val expected = ++generation
        mutable.value = s.copy(loading = true, failure = null)
        job = viewModelScope.launch {
            val result = listCourses(org, s.statusFilter)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(loading = false, courses = result.value)
                is TrainingResult.Failure -> mutable.value.copy(loading = false, failure = result.reason)
            }
        }
    }

    fun filter(status: CourseStatus?) {
        mutable.value = mutable.value.copy(statusFilter = status)
        refresh()
    }

    // ---- Alta de curso -----------------------------------------------------------------------

    fun startCreate() { mutable.value = mutable.value.copy(creating = NewCourseForm(), createFailure = null) }
    fun cancelCreate() { if (!mutable.value.creatingBusy) mutable.value = mutable.value.copy(creating = null, createFailure = null) }
    fun editCreate(change: (NewCourseForm) -> NewCourseForm) {
        val form = mutable.value.creating ?: return
        mutable.value = mutable.value.copy(creating = change(form), createFailure = null)
    }

    fun create() {
        val (s, org) = scoped() ?: return
        val form = s.creating ?: return
        if (s.creatingBusy) return
        if (form.issues.isNotEmpty()) { mutable.value = s.copy(creating = form.copy(showIssues = true)); return }
        mutable.value = s.copy(creatingBusy = true, createFailure = null)
        val expected = generation
        viewModelScope.launch {
            val result = createCourse(org, form.toNewCourse())
            if (expected != generation) return@launch
            when (result) {
                is TrainingResult.Success -> {
                    mutable.value = mutable.value.copy(creating = null, creatingBusy = false, notice = "Curso creado como borrador.")
                    refresh()
                    openDraft(result.value.id)
                }
                is TrainingResult.Failure -> mutable.value = mutable.value.copy(creatingBusy = false, createFailure = result.reason)
            }
        }
    }

    // ---- Borrador ----------------------------------------------------------------------------

    fun openDraft(courseId: String) {
        val (s, org) = scoped() ?: return
        job?.cancel()
        val expected = ++generation
        mutable.value = s.copy(loadingDraft = true, failure = null, notice = null)
        job = viewModelScope.launch {
            val result = loadDraft(org, courseId)
            if (expected != generation) return@launch
            mutable.value = when (result) {
                is TrainingResult.Success -> mutable.value.copy(loadingDraft = false, editor = DraftEditorState(result.value))
                is TrainingResult.Failure -> mutable.value.copy(loadingDraft = false, failure = result.reason)
            }
        }
    }

    /** Descarta la copia local y vuelve a pedir el borrador vigente (tras un conflicto). */
    fun reloadDraft() {
        val draft = mutable.value.editor?.draft ?: return
        if (mutable.value.editor?.busy == true) return
        wipeUpload()
        openDraft(draft.course.id)
    }

    fun edit(change: (TrainingDraft) -> TrainingDraft) {
        val editor = mutable.value.editor ?: return
        if (editor.saving || editor.publishing) return
        mutable.value = mutable.value.copy(editor = editor.copy(draft = change(editor.draft), dirty = true, failure = null), notice = null)
    }

    fun newLesson() = edit { TrainingDraftEdits.addLesson(it, newId()) }
    fun newQuestion(lessonId: String? = null) = edit { TrainingDraftEdits.addQuestion(it, newId(), lessonId) }

    fun closeDraft() {
        val editor = mutable.value.editor
        if (editor?.busy == true) return
        wipeUpload()
        mutable.value = mutable.value.copy(editor = null)
        refresh()
    }

    /** Guarda con control de concurrencia; un 409 deja el borrador local intacto y pide recargar. */
    fun save(afterSaved: (() -> Unit)? = null) {
        val (s, org) = scoped() ?: return
        val editor = s.editor ?: return
        if (editor.saving || editor.publishing || editor.uploading) return
        if (editor.issues.isNotEmpty()) { mutable.value = s.copy(editor = editor.copy(showIssues = true)); return }
        mutable.value = s.copy(editor = editor.copy(saving = true, failure = null, showIssues = false))
        val expected = generation
        viewModelScope.launch {
            val result = saveDraft(org, editor.draft)
            if (expected != generation) return@launch
            val current = mutable.value.editor ?: return@launch
            when (result) {
                is TrainingResult.Success -> {
                    mutable.value = mutable.value.copy(editor = current.copy(draft = result.value, dirty = false, saving = false), notice = "Borrador guardado.")
                    afterSaved?.invoke()
                }
                is TrainingResult.Failure -> mutable.value = mutable.value.copy(editor = current.copy(saving = false, failure = result.reason,
                    showIssues = result.reason == TrainingFailure.INVALID))
            }
        }
    }

    /** Publica; si hay cambios sin guardar los guarda primero. */
    fun publish() {
        val (s, org) = scoped() ?: return
        val editor = s.editor ?: return
        if (editor.busy) return
        val blockers = TrainingDraftRules.publishBlockers(editor.draft)
        if (blockers.isNotEmpty()) {
            mutable.value = s.copy(editor = editor.copy(showIssues = true, failure = TrainingFailure.DRAFT_INCOMPLETE))
            return
        }
        if (editor.dirty) { save { publish() }; return }
        mutable.value = s.copy(editor = editor.copy(publishing = true, failure = null))
        val expected = generation
        viewModelScope.launch {
            val result = publishCourse(org, editor.draft.course.id)
            if (expected != generation) return@launch
            val current = mutable.value.editor ?: return@launch
            when (result) {
                is TrainingResult.Success -> {
                    mutable.value = mutable.value.copy(editor = null, notice = "Curso publicado: ya está disponible para aprender.")
                    refresh()
                }
                is TrainingResult.Failure -> mutable.value = mutable.value.copy(editor = current.copy(publishing = false, failure = result.reason))
            }
        }
    }

    // ---- Video -------------------------------------------------------------------------------

    /** Sube el video elegido a la lección: reserva, PUT directo a R2 y confirmación. */
    fun attachVideo(lessonId: String, selection: VideoSelection) {
        val editor = mutable.value.editor ?: return
        if (editor.busy) return
        when (selection) {
            VideoSelection.Cancelled -> Unit
            is VideoSelection.Failed -> mutable.value = mutable.value.copy(editor = editor.copy(uploadFailure = when (selection.reason) {
                VideoSelectionFailure.TOO_LARGE -> "El video supera el tamaño permitido en este dispositivo."
                VideoSelectionFailure.UNSUPPORTED -> "Elige un video MP4 o WebM."
                VideoSelectionFailure.READ_FAILED -> "No se pudo leer el archivo."
                VideoSelectionFailure.UNAVAILABLE -> "La selección de archivos no está disponible aquí."
            }, uploadLessonId = null))
            is VideoSelection.Selected -> {
                wipeUpload()
                pendingUpload = PendingUpload(lessonId, newId(), selection.video)
                runUpload()
            }
        }
    }

    /** Reintenta con el mismo archivo y la misma reserva. */
    fun retryUpload() { if (pendingUpload != null && mutable.value.editor?.busy != true) runUpload() }

    fun cancelUpload() {
        uploadJob?.cancel()
        wipeUpload()
        val editor = mutable.value.editor ?: return
        mutable.value = mutable.value.copy(editor = editor.copy(uploadLessonId = null, uploadFailure = null, canRetryUpload = false, uploadProgress = 0f))
    }

    private fun runUpload() {
        val (s, org) = scoped() ?: return
        val editor = s.editor ?: return
        val upload = pendingUpload ?: return
        mutable.value = s.copy(editor = editor.copy(uploadLessonId = upload.lessonId, uploadProgress = 0f, uploadFailure = null, canRetryUpload = false))
        val expected = generation
        uploadJob?.cancel()
        uploadJob = viewModelScope.launch {
            val result = uploadVideo(org, upload.mediaId, upload.video) { progress ->
                val current = mutable.value.editor
                if (expected == generation && current?.uploadLessonId == upload.lessonId) mutable.value = mutable.value.copy(editor = current.copy(uploadProgress = progress))
            }
            if (expected != generation) return@launch
            val current = mutable.value.editor ?: return@launch
            when (result) {
                is TrainingResult.Success -> {
                    wipeUpload()
                    mutable.value = mutable.value.copy(editor = current.copy(
                        draft = TrainingDraftEdits.setLessonVideo(current.draft, upload.lessonId, result.value.id),
                        dirty = true, uploadLessonId = null, uploadProgress = 1f, uploadFailure = null, canRetryUpload = false),
                        notice = "Video listo. Guarda el borrador para asociarlo a la lección.")
                }
                is TrainingResult.Failure -> {
                    val retry = result.reason.retryable
                    if (!retry) wipeUpload()
                    mutable.value = mutable.value.copy(editor = current.copy(uploadLessonId = upload.lessonId.takeIf { retry }, canRetryUpload = retry,
                        uploadFailure = trainingFailureMessage(result.reason)))
                }
            }
        }
    }

    fun removeVideo(lessonId: String) = edit { TrainingDraftEdits.setLessonVideo(it, lessonId, null) }

    fun dismissNotice() { mutable.value = mutable.value.copy(notice = null) }

    override fun onCleared() { reset() }
}
