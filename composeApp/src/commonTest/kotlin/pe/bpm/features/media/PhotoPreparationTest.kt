package pe.bpm.features.media

import pe.bpm.features.media.domain.PhotoPreparation
import pe.bpm.features.media.domain.PhotoPreparation.Plan
import kotlin.test.*

class PhotoPreparationTest {
    private val allowed = setOf("image/jpeg", "image/png")
    private val limit = 10 * 1024 * 1024

    @Test fun smallJpegAndPngPassThroughUntouched() {
        assertEquals(Plan.PassThrough, PhotoPreparation.plan("image/jpeg", 3_000_000, 4000, 3000, limit, allowed))
        assertEquals(Plan.PassThrough, PhotoPreparation.plan("image/png", 500_000, 1080, 1920, limit, allowed))
    }

    @Test fun heicAndWebpAreConvertedInsteadOfRejected() {
        assertTrue(PhotoPreparation.plan("image/heic", 2_000_000, 4000, 3000, limit, allowed) is Plan.Recode)
        assertTrue(PhotoPreparation.plan("image/webp", 800_000, 2000, 1500, limit, allowed) is Plan.Recode)
    }

    @Test fun oversizedFilesAndHugeSensorsAreDownscaled() {
        val big = PhotoPreparation.plan("image/jpeg", 14_000_000, 4000, 3000, limit, allowed)
        assertEquals(Plan.Recode(1), big)
        val sensor = PhotoPreparation.plan("image/jpeg", 9_000_000, 12000, 9000, limit, allowed) as Plan.Recode
        assertEquals(4, sensor.sampleSize)
        assertEquals(4, PhotoPreparation.sampleSizeFor(16000, 12000))
    }

    @Test fun undecodableOrNonJpegPoliciesAreUnsupported() {
        assertEquals(Plan.Unsupported, PhotoPreparation.plan(null, 1000, 0, 0, limit, allowed))
        assertEquals(Plan.Unsupported, PhotoPreparation.plan("image/heic", 1000, 100, 100, limit, setOf("image/png")))
    }

    @Test fun fileNamesAreSafeAndMatchTheSentFormat() {
        assertEquals("IMG_2026.jpg", PhotoPreparation.fileName("IMG_2026.heic", "image/jpeg"))
        assertEquals("evidencia.jpg", PhotoPreparation.fileName(null, "image/jpeg"))
        assertEquals("ab.png", PhotoPreparation.fileName("a/b\\.png", "image/png"))
        assertTrue(PhotoPreparation.fileName("x".repeat(500), "image/jpeg").length <= 100)
    }
}
