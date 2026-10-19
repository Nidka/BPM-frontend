package pe.bpm.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import pe.bpm.core.config.GOOGLE_WEB_CLIENT_ID
import pe.bpm.features.identity.data.AndroidGoogleAuthProvider

/** Mantiene el proveedor durante recreaciones; la Activity visible se adjunta solo mientras existe. */
class AndroidIdentityOwner(application: Application) : AndroidViewModel(application) {
    val googleAuth = AndroidGoogleAuthProvider(application, GOOGLE_WEB_CLIENT_ID)
    init { addCloseable(googleAuth) }
}
