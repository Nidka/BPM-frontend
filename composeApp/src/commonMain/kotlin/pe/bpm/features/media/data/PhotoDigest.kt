package pe.bpm.features.media.data

/** SHA-256 del contenido real con la implementación criptográfica de cada plataforma. */
internal expect suspend fun photoSha256(bytes: ByteArray): String
