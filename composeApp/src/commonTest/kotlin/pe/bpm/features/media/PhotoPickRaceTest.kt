package pe.bpm.features.media

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import pe.bpm.features.media.domain.*
import pe.bpm.features.media.presentation.MediaEvidenceViewModel
import kotlin.test.*

/**
 * Regresión: en Android, cerrar el selector de fotos dispara «volver a primer plano» y con él una consulta de la lista.
 * La foto elegida llegaba mientras `loading` estaba activo y se descartaba sin error ni envío.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoPickRaceTest {
    private val id = "49d000f4-e487-41cf-a166-741c98cd7e04"
    private val photo = PickedPhoto("foto.jpg", "image/jpeg", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte()))
    private val available = EvidenceFile(id, "", "", "foto.jpg", "image/jpeg", 4, EvidenceStatus.AVAILABLE, "2026-11-01T00:00:00Z", "2026-11-01T00:01:00Z", incidentId = "inc", stage = EvidenceStage.BEFORE)
    private val policy = EvidencePolicy(100, setOf("image/jpeg"), 3, true)

    private inner class Fake : MediaReader, MediaWriter {
        var listGate: CompletableDeferred<Unit>? = null
        var lists = 0
        val uploads = mutableListOf<EvidenceUpload>()
        override suspend fun list(context: EvidenceContext): MediaResult<EvidenceListing> {
            lists++
            listGate?.let { withContext(NonCancellable) { it.await() } }
            return MediaResult.Success(EvidenceListing(emptyList(), policy))
        }
        override suspend fun upload(context: EvidenceContext, input: EvidenceUpload, onProgress: (Float) -> Unit): MediaResult<EvidenceFile> {
            uploads += input
            return MediaResult.Success(available)
        }
        override suspend fun remove(context: EvidenceContext, file: EvidenceFile) = MediaResult.Success(file)
        override suspend fun preview(context: EvidenceContext, file: EvidenceFile): MediaResult<EvidencePreview> = MediaResult.Failure(MediaFailure.UNAVAILABLE)
    }

    private fun vm(repo: Fake) = MediaEvidenceViewModel(LoadEvidence(repo), UploadEvidence(repo), RemoveEvidence(repo), LoadEvidencePreview(repo)) { id }

    @Test fun aPhotoChosenWhileTheListIsLoadingIsStillUploadedAndTheStaleListIsDiscarded() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Fake(); val vm = vm(repo)
        try {
            vm.openIncident("user", "org", "inc", null, EvidenceStage.BEFORE, true); advanceUntilIdle()
            repo.listGate = CompletableDeferred()
            vm.refresh(); runCurrent()
            assertTrue(vm.state.value.loading)
            vm.select(PhotoSelection.Selected(photo)); runCurrent()
            assertEquals(1, repo.uploads.size)
            repo.listGate!!.complete(Unit); advanceUntilIdle()
            assertEquals(1, vm.state.value.confirmedCount, "la lista vieja no debe borrar la foto recién enviada")
            assertFalse(vm.state.value.loading)
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun aSilentForegroundRefreshNeverShowsLoadingNorBlocksSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Fake(); val vm = vm(repo)
        try {
            vm.openIncident("user", "org", "inc", null, EvidenceStage.BEFORE, true); advanceUntilIdle()
            assertEquals(1, repo.lists)
            // Segunda revalidación inmediata: se omite (retroceso), no consulta de nuevo.
            vm.refresh(silent = true); advanceUntilIdle()
            assertEquals(1, repo.lists)
            assertFalse(vm.state.value.loading)
            vm.select(PhotoSelection.Selected(photo)); advanceUntilIdle()
            assertEquals(1, repo.uploads.size)
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }

    @Test fun aSelectionThatCannotBeAcceptedIsReportedInsteadOfSilentlyIgnored() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val repo = Fake(); val vm = vm(repo)
        try {
            // Sin consulta resuelta no hay política: antes la foto desaparecía sin aviso.
            vm.openIncident("user", "org", "inc", null, EvidenceStage.BEFORE, true)
            vm.select(PhotoSelection.Selected(photo)); runCurrent()
            assertEquals(PhotoSelectionFailure.UNAVAILABLE, vm.state.value.selectionFailure)
            assertTrue(repo.uploads.isEmpty())
        } finally { vm.reset(); vm.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
}
