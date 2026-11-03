package pe.bpm.features.operations

import kotlin.test.*
import pe.bpm.features.operations.domain.*

class OperationsRulesTest {
    @Test
    fun newItemsRequireAnExplicitPackagingSelection() {
        val id = "11111111-1111-4111-8111-111111111111"
        val product = Product(id, "Producto", "", "kg", false)
        val item =
            ReceiptItemInput(
                id,
                id,
                "Lote",
                quantity = "1",
                decision = ReceiptDecision.OBSERVED,
                rejectionReason = "Revisar",
            )
        assertTrue("packagingCondition" in OperationsRules.item(item, product))
    }

    @Test
    fun quantitiesRemainExactAndRejectExtraPrecision() {
        assertEquals("999999999.999", QuantityInput.normalize("999999999,999"))
        assertNull(QuantityInput.normalize("0"))
        assertNull(QuantityInput.normalize("1.0001"))
        assertNull(QuantityInput.normalize("1e3"))
        assertNull(QuantityInput.normalize("1000000000"))
    }

    @Test
    fun acceptedReceiptRequiresSafeProductPolicyAndStorage() {
        val product =
            Product(
                "11111111-1111-4111-8111-111111111111",
                "Producto",
                "",
                "kg",
                true,
                null,
                null,
                1,
            )
        val item =
            ReceiptItemInput(
                "22222222-2222-4222-8222-222222222222",
                product.id,
                "Lote",
                "2026-11-01",
                "1",
                "5",
                ReceiptDecision.ACCEPTED,
                "",
                PackagingCondition.DAMAGED,
                "",
                "",
            )
        val errors = OperationsRules.item(item, product)
        assertTrue("temperatureCelsius" in errors)
        assertTrue("packagingCondition" in errors)
        assertFalse("expirationDate" in errors)
        assertTrue("storageLocation" in errors)
    }

    @Test
    fun rejectsFutureOperationalTimestamps() {
        assertTrue(
            OperationsRules.timestamps(
                    "2026-11-02T10:06:00Z",
                    "2026-11-02T10:00:00Z",
                    "2026-11-02T10:00:00Z",
                )
                .containsKey("dateTime")
        )
        assertTrue(
            OperationsRules.timestamps(
                    "2026-11-02T10:00:00Z",
                    "2026-11-02T10:06:00Z",
                    "2026-11-02T10:00:00Z",
                )
                .containsKey("clientCreatedAt")
        )
    }

    @Test
    fun workerOnlyCanReadPlansAndExecutions() {
        assertFalse(OperationsAccess.canRead("WORKER", OperationsResource.PRODUCTS))
        assertFalse(OperationsAccess.canCreate("WORKER", OperationsResource.RECEIPTS))
        assertTrue(OperationsAccess.canCreate("WORKER", OperationsResource.EXECUTIONS))
        assertFalse(OperationsAccess.canCreate("ADMIN", OperationsResource.EXECUTIONS))
    }
}
