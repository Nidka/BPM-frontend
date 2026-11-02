package pe.bpm.features.media.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pe.bpm.features.media.domain.*

data class MediaEvidenceState(
    val userId: String? = null, val organizationId: String? = null, val runId: String? = null, val itemId: String? = null,
    val canEdit: Boolean = false, val files: List<EvidenceFile> = emptyList(), val policy: EvidencePolicy? = null,
    val loading: Boolean = false, val busy: Boolean = false, val progress: Float? = null,
    val failure: MediaFailure? = null, val selectionFailure: PhotoSelectionFailure? = null,
    val pendingId: String? = null, val pendingName: String? = null, val pendingNeedsConfirmation: Boolean = false,
    val preview: EvidencePreview? = null, val removalCandidate: EvidenceFile? = null,
    val revision: Long = 0,
) {
    val confirmedCount: Int get() = files.count { it.status == EvidenceStatus.AVAILABLE }
    val context: EvidenceContext? get() = organizationId?.let { org -> runId?.let { run -> itemId?.let { EvidenceContext(org, run, it) } } }
    val canSelect: Boolean get() = canEdit && !loading && !busy && pendingId == null && policy?.uploadAvailable == true &&
        files.count { it.status != EvidenceStatus.DELETED } < policy.maxFiles && failure !in setOf(MediaFailure.SESSION, MediaFailure.DENIED)
}

class MediaEvidenceViewModel(private val load: LoadEvidence, private val upload: UploadEvidence, private val remove: RemoveEvidence,
    private val preview: LoadEvidencePreview, private val newId: () -> String) : ViewModel() {
    private val mutable = MutableStateFlow(MediaEvidenceState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var generation = 0L
    private var revision = 0L
    private var pending: EvidenceUpload? = null

    fun open(user: String, org: String, run: String, item: String, canEdit: Boolean) {
        val current = mutable.value
        if (current.userId == user && current.organizationId == org && current.runId == run && current.itemId == item && current.canEdit == canEdit) return
        reset()
        mutable.value = MediaEvidenceState(userId = user, organizationId = org, runId = run, itemId = item, canEdit = canEdit, revision = revision)
        refresh()
    }

    fun reset() {
        generation++
        job?.cancel()
        job = null
        clearPending()
        clearPreview()
        mutable.value = MediaEvidenceState(revision = revision)
    }
    fun close() = reset()
    override fun onCleared() { reset(); super.onCleared() }

    fun refresh() {
        val context = mutable.value.context ?: return
        if (mutable.value.busy || mutable.value.loading) return
        val expected = generation
        mutable.value = mutable.value.copy(loading = true, failure = null, selectionFailure = null, removalCandidate = null)
        job = viewModelScope.launch {
            val result = safely { load(context) }
            if (!isCurrent(expected)) return@launch
            when (result) {
                is MediaResult.Success -> mutable.value = mutable.value.copy(files = result.value.files, policy = result.value.policy, loading = false, revision = ++revision)
                is MediaResult.Failure -> fail(result.reason)
            }
        }
    }

    fun select(result: PhotoSelection, context: EvidenceContext? = mutable.value.context, user: String? = mutable.value.userId) {
        if (context != mutable.value.context || user != mutable.value.userId || !mutable.value.canSelect) return
        when (result) {
            PhotoSelection.Cancelled -> Unit
            is PhotoSelection.Failed -> mutable.value = mutable.value.copy(selectionFailure = result.reason, failure = null)
            is PhotoSelection.Selected -> {
                val policy = mutable.value.policy ?: return
                val checked = EvidencePhotoSelectionRules.validate(result.photo.name, result.photo.mimeType, result.photo.bytes, minOf(policy.maxBytes, MAX_EVIDENCE_BYTES), policy.allowedMimeTypes)
                if (checked is PhotoSelection.Failed) {
                    mutable.value = mutable.value.copy(selectionFailure = checked.reason, failure = null)
                    return
                }
                // Copia propia para poder borrarla sin modificar la memoria del selector ni del llamador.
                pending = EvidenceUpload(newId(), result.photo.copy(bytes = result.photo.bytes.copyOf()))
                mutable.value = mutable.value.copy(pendingId = pending!!.id, pendingName = pending!!.photo.name, selectionFailure = null, failure = null)
                retry()
            }
        }
    }

    fun retry() {
        val current = mutable.value
        val context = current.context ?: return
        val policy = current.policy ?: return
        val input = pending ?: return
        if (!current.canEdit || current.busy || current.loading || current.failure in setOf(MediaFailure.SESSION, MediaFailure.DENIED)) return
        val expected = generation
        clearPreview()
        mutable.value = current.copy(busy = true, progress = null, failure = null, selectionFailure = null, preview = null, removalCandidate = null)
        job = viewModelScope.launch {
            val result = safely {
                upload(context, policy, input) { progress ->
                    viewModelScope.launch {
                        if (generation == expected && pending?.id == input.id && mutable.value.busy) mutable.value = mutable.value.copy(progress = progress.coerceIn(0f, 1f))
                    }
                }
            }
            if (!isCurrent(expected)) return@launch
            when (result) {
                is MediaResult.Success -> {
                    clearPending()
                    mutable.value = mutable.value.copy(files = replace(result.value), busy = false, progress = null,
                        pendingId = null, pendingName = null, pendingNeedsConfirmation = false, failure = null, revision = ++revision)
                }
                is MediaResult.Failure -> {
                    pending = pending?.copy(confirmFirst = input.confirmFirst || result.mustConfirm)
                    if (result.reason in setOf(MediaFailure.CONTENT_INVALID, MediaFailure.CONFLICT, MediaFailure.NOT_FOUND, MediaFailure.INVALID)) clearPending()
                    mutable.value = mutable.value.copy(pendingId = pending?.id, pendingName = pending?.photo?.name, pendingNeedsConfirmation = pending?.confirmFirst == true)
                    fail(result.reason)
                }
            }
        }
    }

    fun discardPending() {
        if (mutable.value.busy || mutable.value.loading) return
        clearPending()
        mutable.value = mutable.value.copy(pendingId = null, pendingName = null, pendingNeedsConfirmation = false, failure = null, selectionFailure = null)
        // La reserva remota puede existir aunque una respuesta se haya perdido.
        refresh()
    }

    fun view(fileId: String) {
        val current = mutable.value
        val context = current.context ?: return
        val file = current.files.find { it.id == fileId && it.status == EvidenceStatus.AVAILABLE } ?: return
        if (current.busy || current.loading || current.failure in setOf(MediaFailure.SESSION, MediaFailure.DENIED)) return
        val expected = generation
        clearPreview()
        mutable.value = current.copy(busy = true, preview = null, failure = null, removalCandidate = null)
        job = viewModelScope.launch {
            val result = safely { preview(context, file) }
            if (generation != expected || !currentCoroutineContext().isActive) {
                if (result is MediaResult.Success) result.value.bytes.fill(0)
                return@launch
            }
            when (result) {
                is MediaResult.Success -> mutable.value = mutable.value.copy(preview = result.value, busy = false)
                is MediaResult.Failure -> fail(result.reason)
            }
        }
    }
    fun hidePreview() { clearPreview(); mutable.value = mutable.value.copy(preview = null) }

    fun requestRemove(fileId: String) {
        val current = mutable.value
        if (!current.canEdit || current.busy || current.loading || current.failure in setOf(MediaFailure.SESSION, MediaFailure.DENIED)) return
        val file = current.files.find { it.id == fileId && it.status != EvidenceStatus.DELETED } ?: return
        mutable.value = current.copy(removalCandidate = file)
    }
    fun cancelRemove() { if (!mutable.value.busy) mutable.value = mutable.value.copy(removalCandidate = null) }
    fun confirmRemove() {
        val current = mutable.value
        val context = current.context ?: return
        val file = current.removalCandidate ?: return
        if (!current.canEdit || current.busy || current.loading) return
        val expected = generation
        mutable.value = current.copy(busy = true, failure = null)
        job = viewModelScope.launch {
            val result = safely { remove(context, file) }
            if (!isCurrent(expected)) return@launch
            when (result) {
                is MediaResult.Success -> {
                    if (pending?.id == file.id) clearPending()
                    if (mutable.value.preview?.fileId == file.id) clearPreview()
                    mutable.value = mutable.value.copy(files = replace(result.value), busy = false, removalCandidate = null,
                        preview = mutable.value.preview?.takeUnless { it.fileId == file.id }, pendingId = pending?.id,
                        pendingName = pending?.photo?.name, pendingNeedsConfirmation = pending?.confirmFirst == true, revision = ++revision)
                }
                is MediaResult.Failure -> fail(result.reason)
            }
        }
    }

    private fun replace(file: EvidenceFile): List<EvidenceFile> =
        (mutable.value.files.filterNot { it.id == file.id } + file).filterNot { it.status == EvidenceStatus.DELETED }
    private fun clearPending() { pending?.photo?.bytes?.fill(0); pending = null }
    private fun clearPreview() { mutable.value.preview?.bytes?.fill(0) }
    private fun fail(reason: MediaFailure) {
        val locked = reason in setOf(MediaFailure.SESSION, MediaFailure.DENIED)
        if (locked) { clearPending(); clearPreview() }
        mutable.value = mutable.value.copy(loading = false, busy = false, progress = null, failure = reason,
            files = if (locked) emptyList() else mutable.value.files,
            preview = if (locked) null else mutable.value.preview,
            pendingId = pending?.id, pendingName = pending?.photo?.name, pendingNeedsConfirmation = pending?.confirmFirst == true,
            removalCandidate = null)
    }
    private suspend fun isCurrent(expected: Long): Boolean {
        currentCoroutineContext().ensureActive()
        return generation == expected
    }
    private suspend fun <T> safely(block: suspend () -> MediaResult<T>): MediaResult<T> = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { MediaResult.Failure(MediaFailure.CONNECTION) }
}
