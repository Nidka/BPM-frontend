package pe.bpm.features.research.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pe.bpm.core.offline.LocalStore
import pe.bpm.features.research.domain.SurveyDraftStore
import pe.bpm.features.research.domain.SurveyMoment

/** Borradores de la encuesta en el `LocalStore` de la cuenta activa (colección `survey-drafts`); se borran con el cierre de sesión. */
class LocalSurveyDraftStore(private val store: LocalStore) : SurveyDraftStore {
    private fun key(moment: SurveyMoment, version: String) = "${moment.name}:${version.lowercase()}"

    override suspend fun read(moment: SurveyMoment, instrumentVersionId: String): Map<String, Int>? = runCatching {
        val json = store.get(COLLECTION, key(moment, instrumentVersionId))?.json ?: return@runCatching null
        Json.parseToJsonElement(json).jsonObject.mapValues { it.value.jsonPrimitive.int }
    }.getOrNull()

    override suspend fun write(moment: SurveyMoment, instrumentVersionId: String, answers: Map<String, Int>?) {
        runCatching {
            if (answers.isNullOrEmpty()) store.remove(COLLECTION, key(moment, instrumentVersionId))
            else store.put(COLLECTION, key(moment, instrumentVersionId), JsonObject(answers.mapValues { JsonPrimitive(it.value) }).toString())
        }
    }

    private companion object { const val COLLECTION = "survey-drafts" }
}
