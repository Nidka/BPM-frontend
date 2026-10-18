package pe.bpm.features.workspace.presentation

/** Existing top-level destinations. Feature detail flows own their future back stacks. */
internal enum class WorkspaceDestination(val label: String, val mark: String) {
    Home("Inicio", "⌂"),
    Learn("Aprender", "◇"),
    Operations("Control", "✓"),
    Team("Equipo", "◎"),
    Company("Empresa", "▦"),
}
