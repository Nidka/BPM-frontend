package pe.bpm.features.identity.presentation

import pe.bpm.features.identity.domain.EmailActionFailure
import pe.bpm.features.identity.domain.EmailActionMode

/** Textos de la página de acciones de correo: español claro, sin códigos del proveedor. */
internal data class EmailActionCopy(val title: String, val message: String)

internal object EmailActionTexts {
    fun working(mode: EmailActionMode): EmailActionCopy = when (mode) {
        EmailActionMode.VerifyEmail -> EmailActionCopy("Verificando tu correo…", "Esto toma solo unos segundos.")
        EmailActionMode.ResetPassword -> EmailActionCopy("Comprobando tu enlace…", "Un momento, por favor.")
        EmailActionMode.RecoverEmail -> EmailActionCopy("Recuperando tu correo…", "Esto toma solo unos segundos.")
    }

    fun failure(mode: EmailActionMode, failure: EmailActionFailure): EmailActionCopy = when (failure) {
        EmailActionFailure.InvalidOrExpired -> when (mode) {
            EmailActionMode.VerifyEmail -> EmailActionCopy("Este enlace ya no es válido",
                "Puede que ya lo hayas usado o que haya vencido. Si ya confirmaste tu correo, solo inicia sesión. " +
                    "Si no, entra con tu cuenta y te enviamos un enlace nuevo.")
            EmailActionMode.ResetPassword -> EmailActionCopy("Este enlace ya no es válido",
                "Los enlaces para crear una contraseña vencen pronto y sirven una sola vez. " +
                    "Pide uno nuevo con «¿Olvidaste tu contraseña?» en la pantalla de inicio de sesión.")
            EmailActionMode.RecoverEmail -> EmailActionCopy("Este enlace ya no es válido",
                "Puede que ya lo hayas usado o que haya vencido. Si no reconoces el cambio de correo de tu cuenta, " +
                    "inicia sesión y cambia tu contraseña, o contacta a tu administrador.")
        }
        EmailActionFailure.UserDisabled -> EmailActionCopy("Tu cuenta está desactivada",
            "No podemos completar esta acción. Contacta a tu administrador.")
        EmailActionFailure.WeakPassword -> EmailActionCopy("Elige una contraseña más segura",
            "Combina letras, números y símbolos, y usa al menos 8 caracteres.")
        EmailActionFailure.RateLimited -> EmailActionCopy("Demasiados intentos",
            "Espera un momento y vuelve a intentarlo.")
        EmailActionFailure.Network -> EmailActionCopy("No pudimos conectar",
            "Revisa tu conexión a internet e inténtalo de nuevo.")
        EmailActionFailure.Unavailable -> EmailActionCopy("El servicio no está disponible",
            "Es un problema nuestro, no tuyo. Inténtalo de nuevo en unos minutos.")
    }

    val invalidLink = EmailActionCopy("Este enlace está incompleto",
        "Abre el enlace directamente desde el correo que te enviamos, sin cortarlo. " +
            "Si el problema continúa, inicia sesión y pide un correo nuevo.")
}
