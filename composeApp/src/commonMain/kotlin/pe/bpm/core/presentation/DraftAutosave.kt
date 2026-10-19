package pe.bpm.core.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

enum class DraftSaveStatus { IDLE, SAVING, SAVED, FAILED }

/** Un escritor por documento: agrupa cambios rápidos y termina lo encolado aunque la vista se cierre. */
class DraftAutosave<T>(scope: CoroutineScope, private val persist: suspend (T) -> Unit,
    private val onStatus: (DraftSaveStatus) -> Unit = {}) {
    private data class Write<T>(val revision: Long, val value: T)
    private data class Completion(val revision: Long = 0, val success: Boolean = false)
    private val queue = Channel<Write<T>>(Channel.CONFLATED)
    private val completed = MutableStateFlow(Completion())
    private var revision = 0L
    private val worker = scope.launch {
        for (write in queue) {
            val success = try { persist(write.value); true }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { false }
            completed.value = Completion(write.revision, success)
            if (write.revision == revision) onStatus(if (success) DraftSaveStatus.SAVED else DraftSaveStatus.FAILED)
        }
    }
    fun schedule(value: T): Long {
        val current = ++revision
        check(queue.trySend(Write(current, value)).isSuccess)
        onStatus(DraftSaveStatus.SAVING)
        return current
    }
    suspend fun flush(value: T): Boolean {
        val requested = schedule(value)
        return completed.first { it.revision >= requested }.success
    }
    fun finish(): Job { queue.close(); return worker }
}
