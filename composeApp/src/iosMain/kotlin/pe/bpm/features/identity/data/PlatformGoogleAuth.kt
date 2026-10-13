package pe.bpm.features.identity.data

// Requiere Client ID iOS y esquema de retorno; no simular un proveedor nativo.
actual fun platformGoogleAuthProvider(): GoogleAuthProvider = UnsupportedGoogleAuthProvider()
