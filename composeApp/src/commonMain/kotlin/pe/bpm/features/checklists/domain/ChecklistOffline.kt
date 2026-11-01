package pe.bpm.features.checklists.domain

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pe.bpm.core.domain.DraftOwner

class OfflineInputConflict : IllegalStateException("La copia local cambió")

enum class OfflineRunStatus { LOCAL, PENDING, SENDING, CONFIRMED, ATTENTION }

/** La intención de envío queda fija hasta recibir y conservar su confirmación. */
data class OfflineRunUpload(
    val answers: List<ChecklistAnswer>, val saveKey: String, val finalize: Boolean,
    val submitKey: String, val savedRun: ChecklistRun? = null,
)

data class OfflineChecklist(
    val run: ChecklistRun, val inputs: Map<String, RunAnswerInput>,
    val status: OfflineRunStatus = OfflineRunStatus.LOCAL, val upload: OfflineRunUpload? = null,
    val failure: RunFailure? = null, val attempts: Int = 0,
) {
    val editable get() = run.status == "DRAFT" && upload == null
}

interface ChecklistOfflineRepository {
    suspend fun read(owner: DraftOwner): List<OfflineChecklist>
    /** La transformación debe ser pura: puede repetirse ante escritores concurrentes. */
    suspend fun update(owner: DraftOwner, transform: (List<OfflineChecklist>) -> List<OfflineChecklist>): List<OfflineChecklist>
}

class ManageOfflineChecklists(
    private val repository: ChecklistOfflineRepository,
    private val load: LoadChecklistRun,
    private val newKey: () -> String,
) {
    suspend fun list(owner: DraftOwner) = repository.read(owner)

    suspend fun prepare(owner: DraftOwner, id: String): RunResult {
        val result = load(owner.organizationId, id)
        currentCoroutineContext().ensureActive()
        if (result !is RunResult.Success) return result
        val run = result.run
        if (!run.belongsTo(owner) || run.status != "DRAFT") return RunResult.Failure(RunFailure.DENIED)
        repository.update(owner) { entries ->
            if (entries.any { it.run.id == id }) entries
            else entries + OfflineChecklist(run, run.answerInputs())
        }
        return result
    }

    /** Se llama en una nueva sesión antes de mostrar cualquier respuesta conservada. */
    suspend fun authorize(owner: DraftOwner, entry: OfflineChecklist): RunResult {
        val result = load(owner.organizationId, entry.run.id)
        currentCoroutineContext().ensureActive()
        return if (result is RunResult.Success && !result.run.belongsTo(owner)) RunResult.Failure(RunFailure.DENIED) else result
    }

    suspend fun edit(owner: DraftOwner, id: String, item: String, input: RunAnswerInput) = repository.update(owner) { entries ->
        entries.replace(id) { entry ->
            check(entry.editable)
            require(entry.run.items.any { it.id == item } && input.value.length <= 2000 && input.observation.length <= 2000)
            entry.copy(inputs = entry.inputs + (item to input), status = OfflineRunStatus.LOCAL, failure = null)
        }
    }

    suspend fun replaceInputs(owner: DraftOwner, id: String, inputs: Map<String, RunAnswerInput>, expected: Map<String, RunAnswerInput>) = repository.update(owner) { entries ->
        entries.replace(id) { entry ->
            check(entry.editable)
            require(inputs.size <= 100 && inputs.all { (item, input) -> entry.run.items.any { it.id == item } && input.value.length <= 2000 && input.observation.length <= 2000 })
            val changes = inputs.filter { (item, input) -> expected[item] != input }
            if (changes.any { (item, input) -> entry.inputs[item] != expected[item] && entry.inputs[item] != input }) throw OfflineInputConflict()
            entry.copy(inputs = entry.inputs + changes, status = OfflineRunStatus.LOCAL, failure = null)
        }
    }

    suspend fun enqueue(owner: DraftOwner, id: String, finalize: Boolean, expected: Map<String, RunAnswerInput>? = null): List<OfflineChecklist> {
        val saveKey = newKey(); val submitKey = newKey()
        return repository.update(owner) { entries -> entries.replace(id) { entry ->
            check(entry.editable)
            if (expected != null && entry.inputs != expected) throw OfflineInputConflict()
            val answers = requireNotNull(checklistAnswers(entry.run, entry.inputs))
            require(validRunAnswers(entry.run.items, answers, complete = finalize))
            entry.copy(status = OfflineRunStatus.PENDING, upload = OfflineRunUpload(answers, saveKey, finalize, submitKey), failure = null, attempts = 0)
        } }
    }

    /** Solo tras confirmar el descarte local: consulta, no modifica el registro del servidor. */
    suspend fun useServerVersion(owner: DraftOwner, id: String): RunResult {
        val previous = repository.read(owner).single { it.run.id == id }
        check(previous.status == OfflineRunStatus.ATTENTION)
        val result = authorize(owner, previous)
        if (result is RunResult.Success) repository.update(owner) { entries -> entries.replace(id) {
            check(it == previous)
            OfflineChecklist(result.run, result.run.answerInputs(), OfflineRunStatus.CONFIRMED)
        } }
        return result
    }

    suspend fun retry(owner: DraftOwner) = repository.update(owner) { entries -> entries.map { entry ->
        if (entry.status == OfflineRunStatus.PENDING) entry.copy(attempts = 0) else entry
    } }

    suspend fun remove(owner: DraftOwner, id: String) = repository.update(owner) { entries ->
        check(entries.none { it.run.id == id && it.upload != null })
        entries.filterNot { it.run.id == id }
    }
}

/** Procesa cada revisión en orden. Los errores de una sede no bloquean otras revisiones. */
class SyncOfflineChecklists(
    private val repository: ChecklistOfflineRepository,
    private val save: SaveChecklistRun,
    private val submit: SubmitChecklistRun,
) {
    private val mutex = Mutex()

    suspend operator fun invoke(owner: DraftOwner, allowedIds: Set<String>? = null): RunFailure? = mutex.withLock {
        for (snapshot in repository.read(owner)) {
            currentCoroutineContext().ensureActive()
            if (allowedIds != null && snapshot.run.id !in allowedIds || snapshot.upload == null || snapshot.status == OfflineRunStatus.ATTENTION || snapshot.attempts >= 3) continue
            val key = snapshot.upload.saveKey
            update(owner, snapshot.run.id, key) { it.copy(status = OfflineRunStatus.SENDING) }
            var entry = repository.read(owner).find { it.run.id == snapshot.run.id } ?: continue
            var upload = entry.upload ?: continue
            if (upload.saveKey != key) continue
            if (upload.savedRun == null) {
                val result = save(entry.run, upload.saveKey, upload.answers)
                currentCoroutineContext().ensureActive()
                if (result is RunResult.Failure) {
                    failed(owner, entry, result.reason)
                    if (result.reason == RunFailure.SESSION) return@withLock result.reason
                    continue
                }
                val saved = (result as RunResult.Success).run
                require(saved.belongsTo(owner) && saved.id == entry.run.id && saved.status == "DRAFT" &&
                    saved.rowVersion > entry.run.rowVersion && saved.answers.toSet() == upload.answers.toSet())
                // Si esta escritura falla, el próximo intento repite el guardado con la misma clave.
                update(owner, entry.run.id, key) { current ->
                    current.copy(upload = current.upload!!.copy(savedRun = saved))
                }
                entry = repository.read(owner).find { it.run.id == entry.run.id } ?: continue
                upload = entry.upload ?: continue
            }
            val saved = requireNotNull(upload.savedRun)
            if (upload.finalize) {
                val result = submit(saved, upload.submitKey)
                currentCoroutineContext().ensureActive()
                if (result is RunResult.Failure) {
                    failed(owner, entry, result.reason)
                    if (result.reason == RunFailure.SESSION) return@withLock result.reason
                    continue
                }
                val finished = (result as RunResult.Success).run
                require(finished.belongsTo(owner) && finished.id == entry.run.id && finished.status == "SUBMITTED" && finished.rowVersion > saved.rowVersion)
                confirmed(owner, entry, finished)
            } else confirmed(owner, entry, saved)
        }
        null
    }

    private suspend fun update(owner: DraftOwner, id: String, key: String, change: (OfflineChecklist) -> OfflineChecklist) =
        repository.update(owner) { entries -> entries.map { if (it.run.id == id && it.upload?.saveKey == key) change(it) else it } }

    private suspend fun confirmed(owner: DraftOwner, entry: OfflineChecklist, run: ChecklistRun) =
        update(owner, entry.run.id, entry.upload!!.saveKey) {
            it.copy(run = run, inputs = run.answerInputs(), status = OfflineRunStatus.CONFIRMED, upload = null, failure = null, attempts = 0)
        }

    private suspend fun failed(owner: DraftOwner, entry: OfflineChecklist, reason: RunFailure) =
        update(owner, entry.run.id, entry.upload!!.saveKey) { current -> current.copy(
            status = if (reason in setOf(RunFailure.CONNECTION, RunFailure.UNAVAILABLE, RunFailure.RATE_LIMITED, RunFailure.SESSION)) OfflineRunStatus.PENDING else OfflineRunStatus.ATTENTION,
            failure = reason, attempts = current.attempts + 1,
        ) }
}

private fun ChecklistRun.belongsTo(owner: DraftOwner) = organizationId == owner.organizationId && performedBy == owner.userId
internal fun ChecklistRun.answerInputs() = answers.associate { it.itemId to RunAnswerInput(
    it.complianceResult.ifEmpty { it.numericValue?.let(pe.bpm.core.domain.DecimalInput::format) ?: it.textValue }, it.observation,
) }
private fun List<OfflineChecklist>.replace(id: String, change: (OfflineChecklist) -> OfflineChecklist): List<OfflineChecklist> {
    check(any { it.run.id == id })
    return map { if (it.run.id == id) change(it) else it }
}

/** La edición en línea y sin conexión usa la misma interpretación y validación de respuestas. */
fun checklistAnswers(run: ChecklistRun, inputs: Map<String, RunAnswerInput>): List<ChecklistAnswer>? {
    val result = mutableListOf<ChecklistAnswer>()
    for (item in run.items) {
        val input = inputs[item.id] ?: continue
        if (ChecklistInputRules.answer.error(input.observation) != null ||
            item.responseType == ChecklistResponseType.TEXT && ChecklistInputRules.answer.error(input.value) != null) return null
        if (input.value.isBlank()) { if (input.observation.isNotBlank()) return null else continue }
        result += when (item.responseType) {
            ChecklistResponseType.COMPLIANCE -> ChecklistAnswer(item.id, complianceResult = input.value, observation = input.observation)
            ChecklistResponseType.TEXT -> ChecklistAnswer(item.id, textValue = input.value.trim(), observation = input.observation)
            else -> ChecklistAnswer(item.id, numericValue = pe.bpm.core.domain.DecimalInput.parse(input.value) ?: return null, observation = input.observation)
        }
    }
    return result
}
