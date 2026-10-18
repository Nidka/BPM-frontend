package pe.bpm.features.checklists.data

import kotlinx.serialization.Serializable
import pe.bpm.features.checklists.domain.*

@Serializable internal data class TemplateDto(
    val id: String,
    val name: String,
    val description: String,
    val area: String,
    val versionId: String,
    val versionNumber: Int,
    val items: List<ItemDto>,
) {
    fun domain(): ChecklistTemplate {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && versionId.isNotBlank() && name.isNotBlank() && area.isNotBlank())
        require(versionNumber > 0 && items.size in 1..100)
        require(items.map { it.id }.distinct().size == items.size)
        require(items.map { it.displayOrder }.distinct().size == items.size)
        return ChecklistTemplate(id, name, description, area, versionId, versionNumber,
            items.sortedBy { it.displayOrder }.map(ItemDto::domain))
    }
}

@Serializable internal data class ItemDto(
    val id: String,
    val sectionName: String,
    val label: String,
    val responseType: String,
    val displayOrder: Int,
    val required: Boolean,
    val unit: String = "",
    val minimumValue: Double? = null,
    val maximumValue: Double? = null,
    val evidenceRequiredOnFailure: Boolean = false,
) {
    fun domain(): ChecklistItem {
        val type = ChecklistResponseType.valueOf(responseType)
        require(id.isNotBlank() && sectionName.isNotBlank() && label.isNotBlank() && displayOrder > 0)
        if (type == ChecklistResponseType.TEMPERATURE || type == ChecklistResponseType.NUMBER) {
            require(minimumValue != null && maximumValue != null)
            require(minimumValue.isFinite() && maximumValue.isFinite() && minimumValue <= maximumValue)
        } else require(minimumValue == null && maximumValue == null && unit.isEmpty())
        return ChecklistItem(id, sectionName, label, type, displayOrder, required, unit,
            minimumValue, maximumValue, evidenceRequiredOnFailure)
    }
}

internal fun ChecklistItem.dto() = ItemDto(id, sectionName, label, responseType.name, displayOrder, required, unit,
    minimumValue, maximumValue, evidenceRequiredOnFailure)
