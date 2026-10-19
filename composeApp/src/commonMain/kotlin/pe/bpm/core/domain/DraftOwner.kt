package pe.bpm.core.domain

/** Contexto autenticado que delimita un borrador; nunca representa una autorización. */
data class DraftOwner(val userId: String, val organizationId: String)
