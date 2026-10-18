package pe.bpm.features.identity

import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import pe.bpm.features.identity.domain.*

/** Los dobles de formulario no anuncian sesiones del servidor ni aceptan actividad implícita. */
abstract class FakeIdentityRepository : IdentityRepository {
    override suspend fun restoreSession(): IdentityResult<Profile> = error("Restauración no esperada")
    override fun sessionInvalidations() = emptyFlow<IdentityError>()
    override fun sessionWindow() = flowOf<SessionWindow?>(null)
    override suspend fun recordActivity(): IdentityResult<Unit> = error("Actividad no esperada")
    override suspend fun endSession(): IdentityResult<Unit> = error("Cierre remoto no esperado")
}
