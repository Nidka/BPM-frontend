package pe.bpm.features.operations.domain

import pe.bpm.core.domain.*

sealed interface OperationsCommand {
    data class AddSupplier(val input: Supplier) : OperationsCommand

    data class AddProduct(val input: Product) : OperationsCommand

    data class SetTemperaturePolicy(val productId: String, val input: TemperaturePolicy) :
        OperationsCommand

    data class Receive(val input: ReceiptInput) : OperationsCommand

    data class AddPlan(val input: CleaningPlan) : OperationsCommand

    data class Execute(val input: CleaningExecutionInput) : OperationsCommand
}

/** El cuerpo serializado permanece inmutable hasta confirmar la respuesta. */
data class PendingOperation(
    val resource: OperationsResource,
    val id: String,
    val key: String,
    val body: String,
    val policy: Boolean = false,
)

sealed interface OperationsEntry {
    val id: String

    data class SupplierEntry(val value: Supplier) : OperationsEntry {
        override val id
            get() = value.id
    }

    data class ProductEntry(val value: Product) : OperationsEntry {
        override val id
            get() = value.id
    }

    data class ReceiptEntry(val value: SupplyReceipt) : OperationsEntry {
        override val id
            get() = value.input.id
    }

    data class LotEntry(val value: StorageLot) : OperationsEntry {
        override val id
            get() = value.id
    }

    data class PlanEntry(val value: CleaningPlan) : OperationsEntry {
        override val id
            get() = value.id
    }

    data class ExecutionEntry(val value: CleaningExecution) : OperationsEntry {
        override val id
            get() = value.input.id
    }
}

interface OperationsTransport {
    fun prepare(command: OperationsCommand, key: String): PendingOperation

    suspend fun submit(org: String, pending: PendingOperation): OperationsResult<OperationsEntry>
}

class ManageOperations(
    private val repository: OperationsRepository,
    private val transport: OperationsTransport,
) {
    suspend fun authorize(org: String, site: String?, role: String, resource: OperationsResource) =
        repository.authorize(org, site, role, resource)

    suspend fun list(
        org: String,
        site: String?,
        role: String,
        resource: OperationsResource,
        page: PageRequest,
    ): OperationsResult<Page<OperationsEntry>> {
        if (!OperationsAccess.canRead(role, resource))
            return OperationsResult.Failure(OperationsFailure.DENIED)
        if (resource.needsSite && site == null)
            return OperationsResult.Failure(
                OperationsFailure.INVALID,
                mapOf("locationId" to "Selecciona una sede."),
            )
        fun <T> OperationsResult<Page<T>>.entries(
            convert: (T) -> OperationsEntry
        ): OperationsResult<Page<OperationsEntry>> =
            when (this) {
                is OperationsResult.Success ->
                    OperationsResult.Success(Page(value.items.map(convert), value.nextToken))
                is OperationsResult.Failure -> this
            }
        return when (resource) {
            OperationsResource.SUPPLIERS ->
                repository.suppliers(org, page).entries { OperationsEntry.SupplierEntry(it) }
            OperationsResource.PRODUCTS ->
                repository.products(org, page).entries { OperationsEntry.ProductEntry(it) }
            OperationsResource.RECEIPTS ->
                repository.receipts(org, site!!, page).entries { OperationsEntry.ReceiptEntry(it) }
            OperationsResource.LOTS ->
                repository.lots(org, site!!, page).entries { OperationsEntry.LotEntry(it) }
            OperationsResource.PLANS ->
                repository.plans(org, site!!, page).entries { OperationsEntry.PlanEntry(it) }
            OperationsResource.EXECUTIONS ->
                repository.executions(org, site!!, page).entries {
                    OperationsEntry.ExecutionEntry(it)
                }
        }
    }

    fun errors(command: OperationsCommand, products: List<Product>): Map<String, String> =
        when (command) {
            is OperationsCommand.AddSupplier -> OperationsRules.supplier(command.input)
            is OperationsCommand.AddProduct -> OperationsRules.product(command.input)
            is OperationsCommand.SetTemperaturePolicy -> OperationsRules.policy(command.input)
            is OperationsCommand.Receive -> OperationsRules.receipt(command.input, products)
            is OperationsCommand.AddPlan -> OperationsRules.plan(command.input)
            is OperationsCommand.Execute -> OperationsRules.execution(command.input)
        }

    fun prepare(
        role: String,
        command: OperationsCommand,
        key: String,
        products: List<Product> = emptyList(),
    ): PendingOperation {
        require(errors(command, products).isEmpty())
        val pending = transport.prepare(command, key)
        require(OperationsAccess.canCreate(role, pending.resource))
        return pending
    }

    suspend fun submit(org: String, pending: PendingOperation) = transport.submit(org, pending)

    suspend fun receipt(org: String, id: String) = repository.receipt(org, id)

    suspend fun execution(org: String, id: String) = repository.execution(org, id)
}

data class OperationsForm(
    val id: String,
    val createdAt: String,
    val values: Map<String, String> = emptyMap(),
    val items: List<ReceiptItemInput> = emptyList(),
    val policyProductId: String? = null,
    val rowVersion: Long = 1,
)

data class OperationsDraft(
    val resource: OperationsResource,
    val siteId: String?,
    val form: OperationsForm,
    val pending: PendingOperation? = null,
)

interface OperationsDraftRepository {
    suspend fun read(
        owner: DraftOwner,
        resource: OperationsResource,
        site: String?,
    ): OperationsDraft?

    suspend fun write(
        owner: DraftOwner,
        resource: OperationsResource,
        site: String?,
        draft: OperationsDraft?,
    )
}

class ManageOperationsDraft(private val repository: OperationsDraftRepository) {
    suspend fun read(owner: DraftOwner, resource: OperationsResource, site: String?) =
        repository.read(owner, resource, site)

    suspend fun write(
        owner: DraftOwner,
        resource: OperationsResource,
        site: String?,
        draft: OperationsDraft?,
    ) = repository.write(owner, resource, site, draft)
}
