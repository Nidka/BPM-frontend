package pe.bpm.features.operations.domain

import kotlin.math.abs
import kotlin.time.Instant
import pe.bpm.core.domain.*

/** Cantidad exacta: se normaliza sin convertir a coma flotante. */
object QuantityInput : InputRule {
    fun normalize(value: String): String? {
        val text = value.trim().replace(',', '.')
        if (!Regex("[0-9]{1,9}(?:\\.[0-9]{1,3})?").matches(text)) return null
        val parts = text.split('.')
        val whole = parts[0].trimStart('0').ifEmpty { "0" }
        val fraction = parts.getOrElse(1) { "" }.trimEnd('0')
        if (whole == "0" && fraction.isEmpty()) return null
        return whole + if (fraction.isEmpty()) "" else ".$fraction"
    }

    override fun error(value: String) =
        if (normalize(value) == null)
            "Ingresa una cantidad mayor que cero, hasta 999999999,999 y tres decimales."
        else null
}

object OperationsRules {
    val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    val time = InputRule {
        if (it.isEmpty() || Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(it)) null
        else "Usa HH:mm."
    }
    val temperature = InputRule {
        if (DecimalInput.parse(it)?.let { v -> abs(v) <= 9999.99 } == true) null
        else "Ingresa temperatura con hasta dos decimales entre −9999,99 y 9999,99."
    }

    fun date(value: String): Boolean {
        if (!Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(value)) return false
        val y = value.take(4).toInt()
        val m = value.substring(5, 7).toInt()
        val d = value.takeLast(2).toInt()
        if (y !in 1..9999 || m !in 1..12) return false
        val max =
            when (m) {
                2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
                4,
                6,
                9,
                11 -> 30
                else -> 31
            }
        return d in 1..max
    }

    fun timestamps(value: String, created: String, now: String) = buildMap {
        val current = runCatching { Instant.parse(now) }.getOrNull()
        listOf("dateTime" to value, "clientCreatedAt" to created).forEach { (key, text) ->
            val parsed = runCatching { Instant.parse(text) }.getOrNull()
            if (
                parsed == null ||
                    current == null ||
                    parsed.epochSeconds - current.epochSeconds > 300
            )
                put(key, "Usa una fecha y hora válida que no esté en el futuro.")
        }
    }

    fun instant(value: String) =
        runCatching {
                Instant.parse(value)
                true
            }
            .getOrDefault(false)

    fun supplier(v: Supplier) = buildMap {
        checkText("name", v.name, 2, 200)
        checkText("taxId", v.taxId, 0, 20)
        checkText("address", v.address, 0, 300)
        InputRules.personName(160, false).error(v.contactName)?.let { put("contactName", it) }
        InputRules.phone().error(v.contactPhone)?.let { put("contactPhone", it) }
        InputRules.email(false).error(v.contactEmail)?.let { put("contactEmail", it) }
    }

    fun policy(v: TemperaturePolicy) = buildMap {
        if (v.rowVersion <= 0) put("rowVersion", "Actualiza el producto antes de configurar.")
        if (v.requiresTemperature) {
            if (
                v.minimumTemperature == null ||
                    !DecimalInput.isValid(v.minimumTemperature) ||
                    abs(v.minimumTemperature) > 9999.99
            )
                put("minimumTemperature", "Configura el mínimo válido.")
            if (
                v.maximumTemperature == null ||
                    !DecimalInput.isValid(v.maximumTemperature) ||
                    abs(v.maximumTemperature) > 9999.99
            )
                put("maximumTemperature", "Configura el máximo válido.")
            if (
                v.minimumTemperature != null &&
                    v.maximumTemperature != null &&
                    v.minimumTemperature > v.maximumTemperature
            )
                put("maximumTemperature", "El máximo debe ser igual o mayor que el mínimo.")
        } else if (v.minimumTemperature != null || v.maximumTemperature != null)
            put("minimumTemperature", "Quita los límites cuando no requiere temperatura.")
    }

    fun product(v: Product) = buildMap {
        checkText("name", v.name, 2, 200)
        checkText("category", v.category, 0, 100)
        checkText("unit", v.unit, 1, 24)
        putAll(
            policy(
                TemperaturePolicy(
                    v.rowVersion,
                    v.requiresTemperature,
                    v.minimumTemperature,
                    v.maximumTemperature,
                )
            )
        )
    }

    fun plan(v: CleaningPlan) = buildMap {
        checkText("area", v.area, 2, 100)
        checkText("task", v.task, 2, 240)
        checkText("instructions", v.instructions, 0, 2000)
        checkText("productName", v.productName, 0, 160)
        time.error(v.dueTime)?.let { put("dueTime", it) }
        if (!uuid.matches(v.locationId)) put("locationId", "Selecciona una sede.")
    }

    fun execution(v: CleaningExecutionInput) = buildMap {
        if (!uuid.matches(v.cleaningPlanId)) put("cleaningPlanId", "Selecciona un plan activo.")
        if (!uuid.matches(v.locationId)) put("locationId", "Selecciona una sede.")
        if (!instant(v.executedAt)) put("executedAt", "Usa una fecha y hora ISO 8601.")
        checkText(
            "observation",
            v.observation,
            if (v.result == CleaningResult.COMPLETED) 0 else 1,
            2000,
        )
    }

    fun item(v: ReceiptItemInput, p: Product?) = buildMap {
        if (!uuid.matches(v.productId) || p == null)
            put("productId", "Selecciona un producto disponible.")
        checkText("batchCode", v.batchCode, 1, 100)
        QuantityInput.error(v.quantity)?.let { put("quantity", it) }
        if (v.expirationDate != null && !date(v.expirationDate))
            put("expirationDate", "Usa una fecha válida yyyy-MM-dd.")
        if (!v.temperatureCelsius.isNullOrEmpty())
            temperature.error(v.temperatureCelsius)?.let { put("temperatureCelsius", it) }
        if (
            p?.requiresTemperature == true && v.temperatureCelsius?.let(DecimalInput::parse) == null
        )
            put("temperatureCelsius", "Registra la temperatura del producto.")
        checkText(
            "rejectionReason",
            v.rejectionReason,
            if (v.decision == ReceiptDecision.ACCEPTED) 0 else 1,
            2000,
        )
        checkText(
            "storageLocation",
            v.storageLocation,
            if (v.decision == ReceiptDecision.ACCEPTED) 1 else 0,
            120,
        )
        checkText("label", v.label, 0, 160)
        if (v.packagingCondition == null)
            put("packagingCondition", "Selecciona el estado del envase.")
        if (v.decision == ReceiptDecision.ACCEPTED) {
            if (v.packagingCondition == PackagingCondition.DAMAGED)
                put("packagingCondition", "No se puede aceptar un envase dañado.")
            if (p?.requiresTemperature == true) {
                val t = v.temperatureCelsius?.let(DecimalInput::parse)
                if (
                    p.policyMissing ||
                        t == null ||
                        t < p.minimumTemperature!! ||
                        t > p.maximumTemperature!!
                )
                    put(
                        "temperatureCelsius",
                        if (p.policyMissing) "El administrador debe configurar el criterio térmico."
                        else "La temperatura debe cumplir el criterio del producto.",
                    )
            }
        } else {
            if (v.storageLocation.isNotEmpty())
                put("storageLocation", "Quita la ubicación: este producto no genera stock.")
            if (v.label.isNotEmpty())
                put("label", "Quita la etiqueta: este producto no genera stock.")
        }
    }

    fun receipt(v: ReceiptInput, products: List<Product>) = buildMap {
        if (!uuid.matches(v.locationId)) put("locationId", "Selecciona una sede.")
        if (!uuid.matches(v.supplierId)) put("supplierId", "Selecciona un proveedor.")
        if (!instant(v.receivedAt)) put("receivedAt", "Usa una fecha y hora ISO 8601.")
        checkText("documentReference", v.documentReference, 0, 100)
        checkText("notes", v.notes, 0, 2000)
        if (v.items.size !in 1..50) put("items", "Agrega entre uno y cincuenta productos.")
        if (v.items.map { it.id }.distinct().size != v.items.size)
            put("items", "Los productos deben tener identificadores únicos.")
        v.items.forEachIndexed { i, item ->
            item(item, products.find { it.id == item.productId }).forEach { (key, error) ->
                put("items.$i.$key", error)
            }
        }
    }

    private fun MutableMap<String, String>.checkText(
        key: String,
        value: String,
        min: Int,
        max: Int,
    ) {
        InputRules.text(min, max).error(value)?.let { put(key, it) }
    }
}
