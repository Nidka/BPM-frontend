package pe.bpm.features.checklists.domain

import pe.bpm.core.domain.InputRules

object ChecklistInputRules {
    val name = InputRules.text(2, 180)
    val area = InputRules.text(2, 80)
    val description = InputRules.text(0, 500)
    val section = InputRules.text(2, 120)
    val label = InputRules.text(2, 300)
    val answer = InputRules.text(0, 2000, maxBytes = 2000)
    val unit = InputRules.text(0, 20, maxBytes = 20)
}
