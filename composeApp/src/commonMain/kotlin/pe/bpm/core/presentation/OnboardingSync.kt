package pe.bpm.core.presentation

/** Guarda en el servidor si la persona ya vio la introducción (PUT/DELETE /me/onboarding). Devuelve true si se confirmó. */
fun interface OnboardingSync {
    suspend fun setSeen(seen: Boolean): Boolean
}
