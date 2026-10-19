package pe.bpm.features.workspace.presentation

data class WorkspacePlaceholder(val title: String, val message: String, val canRetry: Boolean, val canCreate: Boolean)

/** La falta de permisos y los errores de carga nunca se presentan como una organización vacía. */
fun workspacePlaceholder(state: WorkspaceState, platformAdmin: Boolean): WorkspacePlaceholder {
    return when {
        state.errors.any { it.code == "ACCESS_DENIED" } -> WorkspacePlaceholder("Acceso restringido",
            "Tu cuenta no tiene permiso para abrir esta organización. Solicita al administrador que revise tu acceso.", false, false)
        state.errors.any { it.code == "UNAUTHENTICATED" } -> WorkspacePlaceholder("Tu sesión terminó",
            "Inicia sesión nuevamente para consultar tu espacio de trabajo.", false, false)
        state.errors.isNotEmpty() -> WorkspacePlaceholder("No pudimos cargar tu espacio",
            "No pudimos consultar los datos. Comprueba tu conexión y vuelve a intentarlo.", true, false)
        state.organizations.isEmpty() -> WorkspacePlaceholder("Tu cuenta está lista",
            if (platformAdmin) "Crea una organización para comenzar a configurar sus sedes y equipo."
            else "Tu cuenta aún no está asociada a una organización. Pide a tu administrador que te agregue usando tu correo.", false, platformAdmin)
        else -> WorkspacePlaceholder("No pudimos abrir tu organización",
            "Vuelve a intentarlo o selecciona otra organización disponible.", true, false)
    }
}
