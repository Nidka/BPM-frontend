package pe.bpm.features.operations.data

/** Límite compartido por el API y el proxy; se mide en UTF-8, no en unidades UTF-16. */
internal const val MAX_OPERATIONS_REQUEST_BYTES = 512 * 1024

internal fun operationBodyFits(value: String) =
    value.encodeToByteArray().size <= MAX_OPERATIONS_REQUEST_BYTES

internal fun String.operationCharacterCount() = count { !it.isLowSurrogate() }
