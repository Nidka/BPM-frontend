package pe.bpm.features.media

import kotlinx.coroutines.test.runTest
import pe.bpm.core.offline.InMemoryPrivateFileStore
import pe.bpm.core.offline.ManualConnectivityMonitor
import pe.bpm.core.offline.OfflineHarness
import pe.bpm.core.offline.OutboxStatus
import pe.bpm.core.offline.PHOTO_KIND
import pe.bpm.core.offline.SendOutcome
import pe.bpm.features.media.data.OfflineMediaWriter
import pe.bpm.features.media.data.PhotoOutboxHandler
import pe.bpm.features.media.domain.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OfflinePhotoTest {
    private val context = EvidenceContext("org", cleaningExecutionId = "exec1")
    private val photo = PickedPhoto("foto.jpg", "image/jpeg", byteArrayOf(1, 2, 3))

    private class Writer : MediaWriter {
        var result: MediaResult<EvidenceFile> = MediaResult.Failure(MediaFailure.CONNECTION)
        val uploads = mutableListOf<EvidenceUpload>()
        override suspend fun upload(context: EvidenceContext, input: EvidenceUpload, onProgress: (Float) -> Unit): MediaResult<EvidenceFile> {
            uploads += input; return result
        }
        override suspend fun remove(context: EvidenceContext, file: EvidenceFile) = MediaResult.Failure(MediaFailure.CONNECTION)
    }

    private fun file(id: String) = EvidenceFile(id, "", "", "foto.jpg", "image/jpeg", 3, EvidenceStatus.AVAILABLE, "2026-01-01T00:00:00Z", null, null, null, null, "exec1")

    @Test fun offlinePhotoIsCopiedToPrivateStorageAndQueuedWithoutSignedUrl() = runTest {
        val h = OfflineHarness(connectivity = ManualConnectivityMonitor(false)); h.login()
        val files = InMemoryPrivateFileStore(durable = true)
        val remote = Writer()
        val writer = OfflineMediaWriter(remote, files, h.runtime.outbox, h.connectivity, h.runtime.newId) { h.now }
        val result = assertIs<MediaResult.Success<EvidenceFile>>(writer.upload(context, EvidenceUpload("photo-1", photo)) {})
        assertEquals(EvidenceStatus.PENDING_UPLOAD, result.value.status)
        assertTrue(files.read("photo-1")!!.contentEquals(photo.bytes))
        val op = h.runtime.outbox.snapshot().single()
        assertEquals(PHOTO_KIND, op.kind); assertEquals("exec1", op.entityId); assertEquals("photo-photo-1", op.idempotencyKey)
        assertFalse(op.payload.contains("http")); assertFalse(op.payload.contains("Bearer"))
        assertTrue(remote.uploads.isEmpty())
    }

    @Test fun photoWaitsForItsParentRecordAndThenUploadsOnceAndDeletesTheFile() = runTest {
        val h = OfflineHarness(); h.login()
        val files = InMemoryPrivateFileStore(durable = true)
        val remote = Writer()
        // El alta de la limpieza sigue pendiente: la foto se encola detrás de ella aunque haya red.
        h.enqueue("key-exec-000001", entity = "exec1")
        val writer = OfflineMediaWriter(remote, files, h.runtime.outbox, h.connectivity, h.runtime.newId) { h.now }
        assertIs<MediaResult.Success<EvidenceFile>>(writer.upload(context, EvidenceUpload("photo-2", photo)) {})
        assertTrue(remote.uploads.isEmpty())
        val ops = h.runtime.outbox.snapshot()
        assertEquals(listOf(ops[0].id), ops[1].dependsOn)
        remote.result = MediaResult.Success(file("photo-2"))
        h.runtime.register(PHOTO_KIND, PhotoOutboxHandler(remote, files))
        h.runtime.syncNow(); h.runtime.syncNow()
        assertEquals(listOf("photo-2"), remote.uploads.map { it.id })
        assertTrue(h.runtime.outbox.snapshot().all { it.status == OutboxStatus.SENT })
        assertNull(files.read("photo-2"))
    }

    @Test fun connectionFailureWhileOnlineQueuesAndRetryConfirmsTheSameReservationFirst() = runTest {
        val h = OfflineHarness(); h.login()
        val files = InMemoryPrivateFileStore(durable = true)
        val remote = Writer()
        val writer = OfflineMediaWriter(remote, files, h.runtime.outbox, h.connectivity, h.runtime.newId) { h.now }
        assertIs<MediaResult.Success<EvidenceFile>>(writer.upload(context, EvidenceUpload("photo-3", photo)) {})
        val handler = PhotoOutboxHandler(remote, files)
        val op = h.runtime.outbox.snapshot().single()
        remote.uploads.clear()
        assertIs<SendOutcome.Retry>(handler.send(op))
        assertFalse(remote.uploads.single().confirmFirst)
        assertIs<SendOutcome.Retry>(handler.send(op.copy(attempts = 1)))
        assertTrue(remote.uploads.last().confirmFirst)
        remote.result = MediaResult.Failure(MediaFailure.CONTENT_INVALID)
        assertIs<SendOutcome.Rejected>(handler.send(op))
    }

    @Test fun hostsWithoutDurableFilesNeverPretendToQueue() = runTest {
        val h = OfflineHarness(connectivity = ManualConnectivityMonitor(false)); h.login()
        val remote = Writer()
        val writer = OfflineMediaWriter(remote, InMemoryPrivateFileStore(durable = false), h.runtime.outbox, h.connectivity, h.runtime.newId) { h.now }
        assertIs<MediaResult.Failure>(writer.upload(context, EvidenceUpload("photo-4", photo)) {})
        assertEquals(0, h.runtime.outbox.snapshot().size)
    }
}
