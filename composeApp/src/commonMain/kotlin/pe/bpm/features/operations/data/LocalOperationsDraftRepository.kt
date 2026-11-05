package pe.bpm.features.operations.data

import kotlinx.serialization.Serializable
import pe.bpm.core.domain.DraftOwner
import pe.bpm.core.storage.*
import pe.bpm.features.operations.domain.*

/**
 * Un documento por cuenta, empresa, origen, recurso y sede. Nunca revela datos antes de
 * reautorizar.
 */
class LocalOperationsDraftRepository(
    private val storage: DraftStorage,
    private val origin: String,
) : OperationsDraftRepository {
    private fun document(resource: OperationsResource, site: String?): JsonDraftDocument<DraftDto> {
        if (site != null) validId(site)
        return JsonDraftDocument(
            storage,
            origin,
            "operations-${resource.path}-${site?:"organization"}",
            DraftDto.serializer(),
        )
    }

    override suspend fun read(owner: DraftOwner, resource: OperationsResource, site: String?) =
        document(resource, site).read(owner)?.domain()?.also {
            require(it.resource == resource && it.siteId == site)
        }

    override suspend fun write(
        owner: DraftOwner,
        resource: OperationsResource,
        site: String?,
        draft: OperationsDraft?,
    ) {
        val doc = document(resource, site)
        if (draft == null) doc.remove(owner)
        else {
            require(draft.resource == resource && draft.siteId == site)
            doc.write(owner, DraftDto.from(draft).also { it.domain() })
        }
    }
}

@Serializable
private data class DraftItemDto(
    val id: String,
    val productId: String,
    val batchCode: String,
    val expirationDate: String?,
    val quantity: String,
    val temperature: String?,
    val decision: String,
    val reason: String,
    val packaging: String?,
    val storage: String,
    val label: String,
) {
    fun domain() =
        ReceiptItemInput(
                id,
                productId,
                batchCode,
                expirationDate,
                quantity,
                temperature,
                ReceiptDecision.valueOf(decision),
                reason,
                packaging?.let(PackagingCondition::valueOf),
                storage,
                label,
            )
            .also {
                validId(id)
                require(
                    listOf(
                            productId,
                            batchCode,
                            expirationDate.orEmpty(),
                            quantity,
                            temperature.orEmpty(),
                            storage,
                            label,
                        )
                        .all { v -> v.length <= 512 } && reason.operationCharacterCount() <= 2000
                )
            }

    companion object {
        fun from(v: ReceiptItemInput) =
            DraftItemDto(
                v.id,
                v.productId,
                v.batchCode,
                v.expirationDate,
                v.quantity,
                v.temperatureCelsius,
                v.decision.name,
                v.rejectionReason,
                v.packagingCondition?.name,
                v.storageLocation,
                v.label,
            )
    }
}

@Serializable
private data class PendingDto(
    val resource: String,
    val id: String,
    val key: String,
    val body: String,
    val policy: Boolean,
) {
    fun domain() =
        PendingOperation(OperationsResource.valueOf(resource), id, key, body, policy).also {
            validId(id)
            require(key.length in 8..100 && operationBodyFits(body))
        }
}

@Serializable
private data class DraftDto(
    val resource: String,
    val site: String?,
    val id: String,
    val createdAt: String,
    val values: Map<String, String>,
    val items: List<DraftItemDto>,
    val policyProductId: String?,
    val rowVersion: Long,
    val pending: PendingDto?,
) {
    fun domain(): OperationsDraft {
        validId(id)
        validInstant(createdAt)
        site?.let(::validId)
        policyProductId?.let(::validId)
        require(
            values.size <= 40 &&
                values.all { (k, v) -> k.length <= 64 && v.length <= 4000 } &&
                items.size <= 50 &&
                rowVersion > 0
        )
        val r = OperationsResource.valueOf(resource)
        val p = pending?.domain()
        require(p == null || (p.resource == r && p.id == (policyProductId ?: id)))
        val form =
            OperationsForm(
                id,
                createdAt,
                values,
                items.map { it.domain() },
                policyProductId,
                rowVersion,
            )
        if (p != null) require(encodeOperation(form.command(r, site), p.key) == p)
        return OperationsDraft(r, site, form, p)
    }

    companion object {
        fun from(v: OperationsDraft) =
            DraftDto(
                v.resource.name,
                v.siteId,
                v.form.id,
                v.form.createdAt,
                v.form.values,
                v.form.items.map(DraftItemDto::from),
                v.form.policyProductId,
                v.form.rowVersion,
                v.pending?.let { PendingDto(it.resource.name, it.id, it.key, it.body, it.policy) },
            )
    }
}
