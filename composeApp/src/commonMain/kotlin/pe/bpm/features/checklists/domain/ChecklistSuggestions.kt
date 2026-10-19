package pe.bpm.features.checklists.domain

/** Ejemplos de estructura editables; no sustituyen un procedimiento aprobado ni una norma sanitaria. */
data class ChecklistSuggestion(val id: String, val name: String, val area: String, val description: String,
    val questions: List<Pair<String, String>>)

val checklistSuggestions = listOf(
    ChecklistSuggestion("opening", "Control de apertura", "Cocina", "Ejemplo para adaptar al procedimiento de apertura de la organización.", listOf(
        "Higiene del personal" to "¿El personal cumple las medidas de higiene definidas para comenzar el turno?",
        "Limpieza de superficies" to "¿Las superficies de trabajo están limpias según el procedimiento del establecimiento?",
        "Preparación del área" to "¿Los utensilios necesarios están limpios y disponibles?")),
    ChecklistSuggestion("cleaning", "Revisión de limpieza", "Área de preparación", "Ejemplo para adaptar al programa de limpieza de la organización.", listOf(
        "Limpieza de superficies" to "¿Se realizó la limpieza programada de las superficies de trabajo?",
        "Utensilios" to "¿Los utensilios quedaron limpios y almacenados en el lugar correspondiente?",
        "Registro" to "¿Se completó el registro de limpieza del turno?")),
)
