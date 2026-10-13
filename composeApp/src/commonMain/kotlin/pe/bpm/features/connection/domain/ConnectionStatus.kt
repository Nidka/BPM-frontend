package pe.bpm.features.connection.domain

import pe.bpm.core.domain.AppResult

data class ConnectionStatus(val backendVersion: String)

interface ConnectionRepository {
    suspend fun check(): AppResult<ConnectionStatus>
}

class CheckConnection(private val repository: ConnectionRepository) {
    suspend operator fun invoke(): AppResult<ConnectionStatus> = repository.check()
}
