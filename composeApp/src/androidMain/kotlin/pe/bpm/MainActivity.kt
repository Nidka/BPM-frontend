package pe.bpm

import android.os.Bundle
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import pe.bpm.app.App
import pe.bpm.core.storage.AndroidDraftStorage
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import pe.bpm.features.notifications.domain.acceptedPushRoute
import pe.bpm.features.identity.data.AndroidSessionStore
import androidx.activity.viewModels
import pe.bpm.app.AndroidIdentityOwner

class MainActivity : ComponentActivity() {
    private val identityOwner: AndroidIdentityOwner by viewModels()
    /** Destino pedido por el aviso que abrió la app; la raíz lo consume cuando la sesión y la empresa están listas. */
    private val pendingRoute = MutableStateFlow<String?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute.value = routeOf(intent) ?: pendingRoute.value
    }
    private fun routeOf(intent: Intent?): String? = acceptedPushRoute(intent?.getStringExtra("route"))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) pendingRoute.value = routeOf(intent)
        identityOwner.googleAuth.attach(this)
        identityOwner.pushProvider.attach(this)
        // La aplicación utiliza superficies claras; los íconos del sistema deben mantener contraste.
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            val sessionStore = remember { AndroidSessionStore(applicationContext) }
            val drafts = remember { AndroidDraftStorage(applicationContext) }
            val route by pendingRoute.collectAsState()
            App(externalRoute = route, onExternalRouteHandled = { pendingRoute.value = null }, draftStorage = drafts, sessionStore = sessionStore, googleAuthProvider = identityOwner.googleAuth, pushProvider = identityOwner.pushProvider,
                offline = remember { pe.bpm.core.offline.AndroidOffline.platform(applicationContext) })
        }
    }
    override fun onDestroy() {
        identityOwner.googleAuth.detach(this)
        identityOwner.pushProvider.detach()
        super.onDestroy()
    }
}
