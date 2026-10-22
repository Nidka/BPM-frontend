package pe.bpm.features.workspace.domain

import pe.bpm.core.domain.InputRules

/** Los nombres comerciales, sedes y direcciones admiten números; el contacto es una persona. */
object WorkspaceInputRules {
    val legalName = InputRules.text(2, 200)
    val tradeName = InputRules.text(0, 200)
    val taxId = InputRules.text(0, 20)
    val contactName = InputRules.personName(160, required = false)
    val contactEmail = InputRules.email(required = false)
    val contactPhone = InputRules.phone()
    val locationName = InputRules.text(2, 160)
    val address = InputRules.text(3, 300)
    val district = InputRules.text(0, 120)
    val memberEmail = InputRules.email()

    fun organization(input: Organization): Map<String, String> = buildMap {
        legalName.error(input.legalName)?.let { put("legalName", it) }
        tradeName.error(input.tradeName)?.let { put("tradeName", it) }
        taxId.error(input.taxId)?.let { put("taxId", it) }
        contactName.error(input.contactName)?.let { put("contactName", it) }
        contactEmail.error(input.contactEmail)?.let { put("contactEmail", it) }
        contactPhone.error(input.contactPhone)?.let { put("contactPhone", it) }
        if (input.businessType !in setOf("RESTAURANT", "CAFETERIA", "CATERING", "RELATED_SERVICE")) put("businessType", "Selecciona un tipo de establecimiento.")
    }

    fun location(input: Location): Map<String, String> = buildMap {
        locationName.error(input.name)?.let { put("name", it) }
        address.error(input.address)?.let { put("address", it) }
        district.error(input.district)?.let { put("district", it) }
        contactPhone.error(input.contactPhone)?.let { put("contactPhone", it) }
    }
}

internal fun validationFailure(fields: Map<String, String>) = Result.Failure(listOf(WorkspaceError("VALIDATION_ERROR", "Revisa los campos indicados.", fields)))
