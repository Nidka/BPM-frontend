package pe.bpm

import android.os.Bundle
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pe.bpm.app.App
import androidx.compose.runtime.remember
import pe.bpm.features.identity.data.AndroidSessionStore
import androidx.activity.viewModels
import pe.bpm.app.AndroidIdentityOwner

class MainActivity : ComponentActivity() {
    private val identityOwner: AndroidIdentityOwner by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        identityOwner.googleAuth.attach(this)
        // La aplicación utiliza superficies claras; los íconos del sistema deben mantener contraste.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val sessionStore = remember { AndroidSessionStore(applicationContext) }
            App(sessionStore = sessionStore, googleAuthProvider = identityOwner.googleAuth)
        }
    }
    override fun onDestroy() {
        identityOwner.googleAuth.detach(this)
        super.onDestroy()
    }
}
