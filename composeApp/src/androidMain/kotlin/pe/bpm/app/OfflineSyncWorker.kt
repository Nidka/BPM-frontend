package pe.bpm.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import pe.bpm.core.config.platformAppConfig
import pe.bpm.core.di.AppGraph
import pe.bpm.core.di.BackgroundSyncResult
import pe.bpm.core.offline.AndroidOffline
import pe.bpm.core.storage.AndroidDraftStorage
import pe.bpm.features.identity.data.AndroidSessionStore

/**
 * Envía la Outbox sin la interfaz. Construye un grafo propio sobre la misma base: restaura la
 * sesión con la credencial de renovación guardada (nunca persiste un token nuevo aparte) y deja
 * que el motor reintente con la misma clave de idempotencia, por lo que un envío simultáneo con la
 * app abierta no puede duplicar registros.
 */
class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        val graph = AppGraph(platformAppConfig(), sessionStore = AndroidSessionStore(app), draftStorage = AndroidDraftStorage(app),
            offline = AndroidOffline.platform(app))
        return try {
            when (graph.syncInBackground()) {
                BackgroundSyncResult.DONE -> Result.success()
                BackgroundSyncResult.RETRY -> Result.retry()
            }
        } finally {
            graph.close()
        }
    }
}
