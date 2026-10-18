package pe.bpm.core.network

/** Sanitized authentication failure shared by HTTP adapters, without provider details. */
class SessionAccessException(val expired: Boolean) : Exception("Session access unavailable")
