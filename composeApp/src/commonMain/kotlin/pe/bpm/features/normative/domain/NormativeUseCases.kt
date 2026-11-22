package pe.bpm.features.normative.domain

/** Casos de uso del buscador de normativa: validan la entrada antes de llamar al servidor, que vuelve a validar. */
class NormativeUseCases(private val repository: NormativeRepository) {
    suspend fun status() = repository.status()

    suspend fun search(query: String): NormativeResult<List<NormativeMatch>> {
        NormativeRules.queryIssue(query)?.let { return NormativeResult.Failure(NormativeFailure.INVALID, mapOf("q" to it)) }
        return repository.search(query.trim())
    }

    suspend fun documents(archived: Boolean) = repository.documents(archived)
    suspend fun document(id: String) = repository.document(id)

    suspend fun save(id: String?, input: NormativeDocumentInput): NormativeResult<NormativeDocument> {
        val issues = NormativeRules.documentIssues(input)
        if (issues.isNotEmpty()) return NormativeResult.Failure(NormativeFailure.INVALID, issues)
        return if (id == null) repository.create(input) else repository.update(id, input)
    }

    suspend fun setArchived(id: String, archived: Boolean) = repository.setArchived(id, archived)
}
