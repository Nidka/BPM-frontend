package pe.bpm.features.workspace.domain

enum class MemberEligibility { READY, ALREADY_MEMBER, UNAVAILABLE }

data class MemberAccountMatches(val suggestions: List<String>, val exactStatus: MemberEligibility? = null)

interface MemberAccountFinder {
    suspend fun searchMemberAccounts(organizationId: String, query: String): Result<MemberAccountMatches>
}

class SearchMemberAccounts(private val finder: MemberAccountFinder) {
    fun inputError(query: String): String? = when {
        query.trim().length > 320 -> "El correo debe tener como máximo 320 caracteres."
        query.trim().any { it.isWhitespace() || it.isISOControl() } -> "Escribe el correo sin espacios."
        else -> null
    }
    fun canSearch(query: String): Boolean = query.trim().length >= 3 && inputError(query) == null

    suspend operator fun invoke(organizationId: String, query: String): Result<MemberAccountMatches> {
        val normalized = query.trim().lowercase()
        if (!canSearch(normalized)) return Result.Failure(listOf(WorkspaceError("VALIDATION_ERROR", inputError(normalized) ?: "Escribe al menos 3 caracteres del correo.")))
        return finder.searchMemberAccounts(organizationId, normalized)
    }
}
