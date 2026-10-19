package pe.bpm.core.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pe.bpm.core.domain.DraftOwner

/** Infraestructura de documentos versionados; cada adaptador de feature define y valida su DTO. */
class JsonDraftDocument<T>(private val storage: DraftStorage, private val origin: String,
    private val kind: String, serializer: KSerializer<T>) {
    private val serializer = DraftEnvelope.serializer(serializer)
    private val json = Json { ignoreUnknownKeys = false }
    private fun key(owner: DraftOwner) = draftKey(origin, owner.userId, owner.organizationId, kind)
    suspend fun read(owner: DraftOwner): T? {
        val raw = storage.read(key(owner)) ?: return null
        require(raw.encodeToByteArray().size <= MAX_DRAFT_BYTES)
        val envelope = json.decodeFromString(serializer, raw)
        require(envelope.schema == 1 && envelope.user == owner.userId && envelope.organization == owner.organizationId)
        return envelope.document
    }
    suspend fun write(owner: DraftOwner, value: T) {
        val raw = json.encodeToString(serializer, DraftEnvelope(1, owner.userId, owner.organizationId, value))
        require(raw.encodeToByteArray().size <= MAX_DRAFT_BYTES)
        storage.write(key(owner), raw)
    }
    suspend fun remove(owner: DraftOwner) = storage.remove(key(owner))
}
@Serializable private data class DraftEnvelope<T>(val schema: Int, val user: String, val organization: String, val document: T)
