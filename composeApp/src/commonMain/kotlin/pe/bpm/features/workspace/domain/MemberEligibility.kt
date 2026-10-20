package pe.bpm.features.workspace.domain

enum class MemberEligibility { READY, ALREADY_MEMBER, UNAVAILABLE }

interface MemberEligibilityReader {
    suspend fun checkMember(organizationId: String, email: String): Result<MemberEligibility>
}

class CheckMember(private val reader: MemberEligibilityReader) {
    suspend operator fun invoke(organizationId: String, email: String): Result<MemberEligibility> {
        val normalized = email.trim()
        if (normalized.length > 320 || !normalized.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")))
            return Result.Failure(listOf(WorkspaceError("VALIDATION_ERROR", "Ingresa un correo válido.", mapOf("email" to "Revisa el correo electrónico."))))
        return reader.checkMember(organizationId, normalized)
    }
}
