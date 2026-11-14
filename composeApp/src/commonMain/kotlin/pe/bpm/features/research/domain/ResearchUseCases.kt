package pe.bpm.features.research.domain

import pe.bpm.core.domain.PageRequest

// ---- Participante -----------------------------------------------------------------------------

class LoadParticipation(private val repository: ParticipationRepository) {
    suspend operator fun invoke(org: String): ResearchResult<Participation> = repository.participation(org)
}

/** Acepta o revoca el consentimiento del documento vigente; repetir la operación es seguro. */
class RecordConsent(private val repository: ParticipationRepository) {
    suspend operator fun invoke(org: String, documentVersion: String, accepted: Boolean): ResearchResult<Participation> {
        if (!ResearchLimits.versionPattern.matches(documentVersion)) return ResearchResult.Failure(ResearchFailure.INVALID)
        return repository.recordConsent(org, documentVersion, accepted)
    }
}

/**
 * Prepara y envía una entrega. [prepare] se invoca una sola vez por intento de envío; el objeto
 * resultante se puede reenviar sin duplicar la entrega en el servidor.
 */
class SubmitSurvey(private val repository: ParticipationRepository) {
    /** Devuelve null si faltan respuestas, sobran o están fuera de rango, o si no se puede responder. */
    fun prepare(participation: Participation, moment: SurveyMoment, answers: Map<String, Int>, newId: () -> String, now: String): SurveySubmission? {
        val survey = participation.survey ?: return null
        if (!participation.consentValid || participation.participant?.status == ParticipantStatus.WITHDRAWN) return null
        if (moment in participation.completedMoments()) return null
        if (!SurveyRules.isComplete(survey, answers)) return null
        return SurveySubmission(newId(), newId(), survey.instrumentVersionId, moment, now,
            survey.questions.map { SurveyAnswer(it.id, answers.getValue(it.id)) })
    }

    suspend operator fun invoke(org: String, submission: SurveySubmission): ResearchResult<SubmissionReceipt> = repository.submit(org, submission)
}

// ---- Investigador -----------------------------------------------------------------------------

class LoadResearchAccess(private val repository: ResearchAccessRepository) {
    suspend operator fun invoke(): ResearchResult<ResearchAccess> = repository.access()
}

class ManageAccessGrants(private val repository: ResearchAccessRepository) {
    suspend fun list() = repository.grants()
    suspend fun grant(email: String): ResearchResult<AccessGrant> {
        val clean = email.trim()
        if (clean.length > 254 || !Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+").matches(clean)) return ResearchResult.Failure(ResearchFailure.INVALID)
        return repository.grant(clean)
    }
    suspend fun revoke(userId: String) = repository.revoke(userId)
}

class ManageConsentDocuments(private val repository: ResearchCatalogRepository) {
    suspend fun list() = repository.consentDocuments()
    suspend fun publish(document: NewConsentDocument): ResearchResult<ConsentDocumentItem> {
        val valid = ResearchLimits.versionPattern.matches(document.version) && document.title.isNotBlank() && document.title.length <= ResearchLimits.TITLE_MAX &&
            document.text.isNotBlank() && document.text.length <= ResearchLimits.CONSENT_TEXT_MAX &&
            document.responsibleInvestigator.isNotBlank() && document.responsibleInvestigator.length <= ResearchLimits.INVESTIGATOR_MAX
        return if (valid) repository.publishConsent(document) else ResearchResult.Failure(ResearchFailure.INVALID)
    }
}

class ManageInstruments(private val repository: ResearchCatalogRepository) {
    suspend fun list() = repository.instruments()
    suspend fun detail(id: String) = repository.instrument(id)
    suspend fun draft(id: String) = repository.draft(id)
    suspend fun create(instrument: NewInstrument, imported: Boolean): ResearchResult<InstrumentDraft> {
        val valid = ResearchLimits.codePattern.matches(instrument.code) && instrument.name.isNotBlank() && instrument.name.length <= ResearchLimits.INSTRUMENT_NAME_MAX &&
            instrument.description.length <= ResearchLimits.DESCRIPTION_MAX && InstrumentRules.saveProblems(instrument.questions).isEmpty()
        return if (valid) repository.createInstrument(instrument, imported) else ResearchResult.Failure(ResearchFailure.INVALID)
    }
    suspend fun save(id: String, rowVersion: String, questions: List<DraftQuestion>): ResearchResult<InstrumentDraft> =
        if (InstrumentRules.saveProblems(questions).isEmpty()) repository.saveDraft(id, rowVersion, questions) else ResearchResult.Failure(ResearchFailure.INVALID)
    suspend fun newVersion(id: String) = repository.newVersion(id)
    suspend fun publish(id: String) = repository.publish(id)
}

class AnalyzeResearch(private val repository: ResearchAnalysisRepository) {
    suspend fun organizations() = repository.organizations()
    suspend fun results(versionId: String?, request: PageRequest) = repository.results(versionId, request)
    suspend fun export(versionId: String?, organizationId: String?) = repository.export(versionId, organizationId)
    suspend fun needs(organizationId: String?, request: PageRequest) = repository.needs(organizationId, request)
    suspend fun createNeed(need: NewNeed): ResearchResult<DetectedNeed> =
        if (need.description.isBlank() || need.description.length > ResearchLimits.NEED_DESCRIPTION_MAX) ResearchResult.Failure(ResearchFailure.INVALID)
        else repository.createNeed(need)
}
