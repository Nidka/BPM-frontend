package pe.bpm.features.checklists.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.offline.HttpOperations
import pe.bpm.core.offline.OfflineJson
import pe.bpm.core.offline.Outbox
import pe.bpm.features.checklists.domain.ChecklistOfflineRepository
import pe.bpm.features.checklists.domain.OfflineRunStatus

/**
 * Traslada a la Outbox los envíos que la cola antigua de controles preparados dejó pendientes, para
 * que salgan con el mismo motor (red recuperada, primer plano, segundo plano). Conserva las claves
 * de idempotencia originales: si el servidor ya procesó el guardado o el envío, responde igual y no
 * se duplica nada. Los controles sin envío pedido y los que esperan una decisión del usuario
 * (ATTENTION) se quedan en la cola antigua, que sigue siendo su editor.
 */
class LegacyChecklistMigration(
    private val legacy: ChecklistOfflineRepository,
    private val outbox: Outbox,
    private val newId: () -> String,
    private val clock: () -> Long,
) {
    /** Devuelve cuántos controles se trasladaron. Es idempotente y puede repetirse tras un cierre inesperado. */
    suspend fun migrate(owner: DraftOwner): Int {
        var moved = 0
        for (entry in legacy.read(owner)) {
            val upload = entry.upload ?: continue
            if (entry.status == OfflineRunStatus.ATTENTION) continue
            val run = entry.run
            val base = "/api/v1/organizations/${owner.organizationId}/checklist-runs/${run.id}"
            if (upload.savedRun == null) {
                val body = buildJsonObject {
                    put("rowVersion", run.rowVersion)
                    put("answers", OfflineJson.encodeToJsonElement(ListSerializer(AnswerDto.serializer()), upload.answers.map { it.dto() }))
                }.toString()
                outbox.enqueue(HttpOperations.draft(newId(), owner.organizationId, "PUT", "$base/answers", body, upload.saveKey, run.id, clock()))
            }
            if (upload.finalize) {
                // La versión se lee del servidor justo antes de enviar: depende del guardado previo.
                outbox.enqueue(HttpOperations.draft(newId(), owner.organizationId, "POST", "$base/submit", """{"rowVersion":0}""",
                    upload.submitKey, run.id, clock(), refreshVersionFrom = base))
            }
            // Solo tras registrar todo: si el proceso muere aquí, repetir no duplica (claves iguales).
            legacy.update(owner) { list -> list.filterNot { it.run.id == run.id && it.upload?.saveKey == upload.saveKey } }
            moved++
        }
        return moved
    }
}
