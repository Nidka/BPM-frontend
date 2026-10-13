package pe.bpm.features.identity.data

// Requiere registrar la app Android, SHA y Credential Manager antes de habilitarlo.
actual fun platformGoogleAuthProvider(): GoogleAuthProvider = UnsupportedGoogleAuthProvider()
