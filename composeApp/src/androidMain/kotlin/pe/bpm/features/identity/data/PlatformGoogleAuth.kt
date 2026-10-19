package pe.bpm.features.identity.data

// MainActivity inyecta el proveedor con Activity visible; las composiciones sin host no abren OAuth.
actual fun platformGoogleAuthProvider(): GoogleAuthProvider = UnsupportedGoogleAuthProvider()
