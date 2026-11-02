package pe.bpm.features.media

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.features.media.domain.*
import pe.bpm.features.media.presentation.MediaEvidenceViewModel
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MediaEvidenceViewModelTest {
    private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"
    private val photo = PickedPhoto("foto.jpg", "image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))
    private val available = EvidenceFile(id, "run", "item", "foto.jpg", "image/jpeg", 4, EvidenceStatus.AVAILABLE, "2026-11-01T00:00:00Z", "2026-11-01T00:01:00Z")
    private val policy = EvidencePolicy(100, setOf("image/jpeg"), 3, true)
    private fun vm(repo: Fake): MediaEvidenceViewModel = MediaEvidenceViewModel(LoadEvidence(repo), UploadEvidence(repo), RemoveEvidence(repo), LoadEvidencePreview(repo)) { id }

    @Test fun failedUploadRetainsBytesAndTheSameIdForConfirmationFirstRetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val remote = Fake()
        val vm = vm(remote)
        try {
            vm.open("user", "org", "run", "item", true); advanceUntilIdle()
            vm.select(PhotoSelection.Selected(photo)); advanceUntilIdle()
            assertEquals(id, vm.state.value.pendingId)
            assertEquals("foto.jpg", vm.state.value.pendingName)
            assertTrue(vm.state.value.pendingNeedsConfirmation)
            assertEquals(0, vm.state.value.confirmedCount)
            vm.retry(); advanceUntilIdle()
            assertEquals(listOf(id, id), remote.uploads.map { it.id })
            assertFalse(remote.uploads.first().confirmFirst); assertTrue(remote.uploads.last().confirmFirst)
            assertEquals(1, vm.state.value.confirmedCount)
            assertNull(vm.state.value.pendingId)
            assertTrue(vm.state.value.revision >= 2)
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun contextChangeAndLogoutDiscardLateResultsAndWipeRetainedPayload() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val remote = Fake()
        val vm = vm(remote)
        try {
            vm.open("user", "old", "run", "item", true); advanceUntilIdle()
            remote.uploadGate = CompletableDeferred()
            vm.select(PhotoSelection.Selected(photo)); runCurrent()
            val retained = remote.uploads.single().photo.bytes
            vm.open("user", "new", "run", "other", true); runCurrent()
            assertTrue(retained.all { it == 0.toByte() })
            assertContentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()), photo.bytes)
            remote.uploadGate!!.complete(Unit); advanceUntilIdle()
            assertEquals("new", vm.state.value.organizationId)
            assertTrue(vm.state.value.files.isEmpty()); assertNull(vm.state.value.pendingName)
            vm.close()
            assertNull(vm.state.value.organizationId); assertNull(vm.state.value.preview)
            assertTrue(vm.state.value.files.isEmpty())
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun oldReadCannotRestoreFilesIntoAnotherQuestion() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val remote = Fake()
        val gate = CompletableDeferred<Unit>()
        remote.listGate = gate
        val vm = vm(remote)
        try {
            vm.open("user", "org", "run", "item", true); runCurrent()
            remote.listGate = null
            vm.open("user", "org", "run", "other", false); runCurrent()
            gate.complete(Unit); advanceUntilIdle()
            assertEquals("other", vm.state.value.itemId)
            assertTrue(vm.state.value.files.isEmpty())
            vm.select(PhotoSelection.Selected(photo)); advanceUntilIdle()
            assertTrue(remote.uploads.isEmpty())
            vm.open("user", "org", "run", "other", true); advanceUntilIdle()
            vm.select(PhotoSelection.Selected(photo), EvidenceContext("org", "run", "item"), "user"); advanceUntilIdle()
            assertTrue(remote.uploads.isEmpty())
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun closingDuringAnUncancellableDownloadWipesItsLatePayload() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val remote = Fake()
        remote.files = listOf(available)
        remote.previewGate = CompletableDeferred()
        val vm = vm(remote)
        try {
            vm.open("user", "org", "run", "item", false); advanceUntilIdle()
            vm.view(id); runCurrent()
            vm.close()
            remote.previewGate!!.complete(Unit); advanceUntilIdle()
            assertNull(vm.state.value.preview)
            assertTrue(remote.previewPayload!!.all { it == 0.toByte() })
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun deletionRequiresConfirmationAndPreviewRequiresAvailableFile() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val remote = Fake()
        remote.files = listOf(available, available.copy(id = "pending", status = EvidenceStatus.PENDING_UPLOAD, uploadedAt = null))
        val vm = vm(remote)
        try {
            vm.open("user", "org", "run", "item", true); advanceUntilIdle()
            vm.view("pending"); advanceUntilIdle(); assertEquals(0, remote.previews)
            vm.requestRemove(id); advanceUntilIdle(); assertEquals(0, remote.removals)
            assertEquals(id, vm.state.value.removalCandidate?.id)
            vm.confirmRemove(); advanceUntilIdle()
            assertEquals(1, remote.removals); assertEquals(0, vm.state.value.confirmedCount)
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    private inner class Fake : MediaReader, MediaWriter {
        var files = emptyList<EvidenceFile>()
        var listGate: CompletableDeferred<Unit>? = null
        var uploadGate: CompletableDeferred<Unit>? = null
        val uploads = mutableListOf<EvidenceUpload>()
        var previewGate: CompletableDeferred<Unit>? = null
        var previewPayload: ByteArray? = null
        var previews = 0
        var removals = 0
        override suspend fun list(context: EvidenceContext): MediaResult<EvidenceListing> {
            val gate = listGate
            if (gate != null) withContext(NonCancellable) { gate.await() }
            return MediaResult.Success(EvidenceListing(if (context.itemId == "item") files else emptyList(), policy))
        }
        override suspend fun upload(context: EvidenceContext, input: EvidenceUpload, onProgress: (Float) -> Unit): MediaResult<EvidenceFile> {
            uploads += input
            uploadGate?.let { withContext(NonCancellable) { it.await() }; return MediaResult.Success(available) }
            return if (uploads.size == 1) MediaResult.Failure(MediaFailure.CONNECTION, mustConfirm = true) else MediaResult.Success(available)
        }
        override suspend fun remove(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidenceFile> { removals++; return MediaResult.Success(file.copy(status = EvidenceStatus.DELETED)) }
        override suspend fun preview(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidencePreview> {
            previews++
            previewGate?.let { withContext(NonCancellable) { it.await() } }
            val bytes = photo.bytes.copyOf()
            previewPayload = bytes
            return MediaResult.Success(EvidencePreview(file.id, file.originalName, file.mimeType, bytes))
        }
    }
}
