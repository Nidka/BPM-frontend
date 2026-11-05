package pe.bpm.features.media

import kotlin.test.*
import pe.bpm.features.media.domain.*

class IncidentEvidenceTest {
    @Test
    fun targetsAreExclusiveAndStagesBindAction() {
        assertTrue(EvidenceContext("org", "run", "item").valid())
        assertTrue(
            EvidenceContext("org", incidentId = "incident", stage = EvidenceStage.BEFORE).valid()
        )
        assertTrue(
            EvidenceContext(
                    "org",
                    incidentId = "incident",
                    actionId = "action",
                    stage = EvidenceStage.AFTER,
                )
                .valid()
        )
        assertFalse(
            EvidenceContext("org", "run", "item", "incident", null, EvidenceStage.BEFORE).valid()
        )
        assertFalse(
            EvidenceContext("org", incidentId = "incident", stage = EvidenceStage.AFTER).valid()
        )
        assertFalse(
            EvidenceContext(
                    "org",
                    incidentId = "incident",
                    actionId = "action",
                    stage = EvidenceStage.BEFORE,
                )
                .valid()
        )
    }

    @Test
    fun fileNeverCrossesIncidentActionOrStage() {
        val target =
            EvidenceContext(
                "org",
                incidentId = "incident",
                actionId = "action",
                stage = EvidenceStage.AFTER,
            )
        val file =
            EvidenceFile(
                "id",
                "",
                "",
                "photo.jpg",
                "image/jpeg",
                4,
                EvidenceStatus.AVAILABLE,
                "2026-11-01T00:00:00Z",
                incidentId = "incident",
                actionId = "action",
                stage = EvidenceStage.AFTER,
            )
        assertTrue(file.belongsTo(target))
        assertFalse(file.copy(incidentId = "other").belongsTo(target))
        assertFalse(file.copy(actionId = "other").belongsTo(target))
        assertFalse(file.copy(stage = EvidenceStage.BEFORE).belongsTo(target))
        assertFalse(file.belongsTo(EvidenceContext("org", "run", "item")))
    }
}
