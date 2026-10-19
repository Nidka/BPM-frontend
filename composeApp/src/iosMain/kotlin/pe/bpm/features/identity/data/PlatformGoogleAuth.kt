package pe.bpm.features.identity.data

// El host Swift inyecta IosGoogleAuthProvider con el SDK y el controlador visible.
actual fun platformGoogleAuthProvider(): GoogleAuthProvider = UnsupportedGoogleAuthProvider()
