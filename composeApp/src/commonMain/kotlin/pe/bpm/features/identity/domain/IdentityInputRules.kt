package pe.bpm.features.identity.domain

import pe.bpm.core.domain.InputRule
import pe.bpm.core.domain.InputRules

object IdentityInputRules {
    val personName = InputRules.personName(80)
    val email = InputRules.email()
    val registrationPassword = InputRule { if (it.length in 8..128) null else "Usa entre 8 y 128 caracteres." }
    // El acceso admite contraseñas existentes sin imponerles la política de un registro nuevo.
    val loginPassword = InputRule { when {
        it.isEmpty() -> "Ingresa tu contraseña."
        it.length > 4096 -> "La contraseña es demasiado larga."
        else -> null
    } }
}
