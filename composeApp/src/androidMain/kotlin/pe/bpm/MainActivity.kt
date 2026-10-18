package pe.bpm

import android.os.Bundle
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pe.bpm.app.App
import androidx.compose.runtime.remember
import pe.bpm.features.identity.data.AndroidSessionStore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // La aplicación utiliza superficies claras; los íconos del sistema deben mantener contraste.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val sessionStore = remember { AndroidSessionStore(applicationContext) }
            App(sessionStore = sessionStore)
        }
    }
}
