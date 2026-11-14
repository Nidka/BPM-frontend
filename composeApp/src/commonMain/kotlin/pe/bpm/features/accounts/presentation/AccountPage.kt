package pe.bpm.features.accounts.presentation

import pe.bpm.core.domain.PageRequest
import pe.bpm.features.accounts.domain.AccountFailure

/** Una sola página con cursor opaco; no acumula registros y reinicia el cursor al cambiar de consulta. */
data class AccountPage<T>(
    val items: List<T> = emptyList(),
    val request: PageRequest = PageRequest(),
    val previous: List<String> = emptyList(),
    val nextToken: String = "",
    val loading: Boolean = false,
    val failure: AccountFailure? = null,
) {
    val number get() = previous.size + 1
    fun search(query: String) = copy(request = PageRequest(query = query.take(160), size = request.size), previous = emptyList(), nextToken = "")
    fun forward() = if (nextToken.isEmpty() || loading) null else copy(request = request.copy(token = nextToken), previous = previous + request.token)
    fun backward() = if (previous.isEmpty() || loading) null else copy(request = request.copy(token = previous.last()), previous = previous.dropLast(1))
    /** Un cursor rechazado se descarta: se vuelve a la primera página de la misma búsqueda. */
    fun retry() = if (failure == AccountFailure.INVALID_PAGINATION) search(request.query) else this
    fun restart() = copy(request = request.copy(token = ""), previous = emptyList(), nextToken = "")
}

internal fun accountFailureMessage(failure: AccountFailure): String = when (failure) {
    AccountFailure.SESSION -> "Tu sesión terminó. Vuelve a iniciar sesión."
    AccountFailure.DENIED -> "No tienes permisos para realizar esta acción."
    AccountFailure.NOT_FOUND -> "El registro ya no está disponible. Actualiza la información."
    AccountFailure.VERSION_CONFLICT -> "Otra persona modificó este registro mientras lo editabas. Actualizamos los datos: revisa los cambios y vuelve a intentar."
    AccountFailure.LAST_ADMIN -> "Debe quedar al menos una persona administradora activa. Transfiere la administración a otra persona antes de continuar."
    AccountFailure.MEMBER_ALREADY_EXISTS -> "Esa persona ya pertenece a la organización. Si está inactiva, reactívala desde Equipo."
    AccountFailure.MEMBER_INACTIVE -> "Esa persona está inactiva. Reactívala antes de continuar."
    AccountFailure.MEMBER_NOT_AVAILABLE -> "La persona no tiene una cuenta BPM activa."
    AccountFailure.ALREADY_PENDING -> "Ya hay una invitación pendiente para ese correo. Puedes reenviarla desde la lista."
    AccountFailure.INVITATION_NOT_FOUND -> "No encontramos esa invitación. Pide que te la envíen de nuevo: un reenvío anula el enlace anterior."
    AccountFailure.INVITATION_EXPIRED -> "La invitación venció. Pide a tu administrador que la reenvíe."
    AccountFailure.INVITATION_USED -> "Esta invitación ya se usó."
    AccountFailure.INVITATION_REVOKED -> "Esta invitación fue revocada por la organización."
    AccountFailure.INVITATION_NOT_PENDING -> "La invitación ya no está pendiente. Actualiza la lista."
    AccountFailure.EMAIL_MISMATCH -> "La invitación se envió a otro correo. Inicia sesión con la cuenta que recibió la invitación."
    AccountFailure.RESEND_TOO_SOON -> "Espera un minuto antes de volver a reenviar esta invitación."
    AccountFailure.CONFLICT -> "El registro ya existe o cambió. Actualiza antes de volver a intentar."
    AccountFailure.VALIDATION -> "Revisa los campos indicados."
    AccountFailure.INVALID_REQUEST -> "No pudimos enviar la solicitud. Revisa los datos y vuelve a intentar."
    AccountFailure.INVALID_PAGINATION -> "La lista cambió. Actualízala para volver a empezar."
    AccountFailure.RATE_LIMITED -> "Espera un momento antes de volver a intentar."
    AccountFailure.INVALID_RESPONSE -> "No pudimos interpretar la información. Actualiza y vuelve a intentar."
    AccountFailure.CONNECTION -> "No pudimos conectar. Revisa tu conexión y vuelve a intentar."
    AccountFailure.UNAVAILABLE -> "El servicio no está disponible. Inténtalo de nuevo."
}
