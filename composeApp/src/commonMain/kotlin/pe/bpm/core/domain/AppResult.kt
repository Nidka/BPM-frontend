package pe.bpm.core.domain

sealed interface AppResult<out T> {
    data class Success<T>(val value: T) : AppResult<T>
    data class Failure(val error: AppFailure) : AppResult<Nothing>
}

enum class AppFailure {
    NETWORK, TIMEOUT, UNAVAILABLE, INVALID_RESPONSE, UNKNOWN
}
