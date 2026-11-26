package pe.bpm.features.notifications.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import pe.bpm.MainActivity
import pe.bpm.features.notifications.domain.acceptedAlertId
import pe.bpm.features.notifications.domain.acceptedPushRoute

/**
 * Presenta los avisos de la cuenta vinculada con el texto que manda el servidor (sede, plantilla, medición; nunca datos
 * personales) y abre la pantalla relacionada. No guarda el texto: solo los identificadores ya vistos.
 */
class BpmMessagingService : FirebaseMessagingService() {
  // Se conserva el protocolo de tokens FCM del backend; el modo FID no está habilitado.
  @Suppress("OVERRIDE_DEPRECATION")
  override fun onNewToken(token: String) {
    // Una rotación suspende la vinculación hasta confirmarla de nuevo desde una sesión activa.
    // El token queda exclusivamente dentro del SDK; no se escribe en preferencias ni logs.
    synchronized(BpmMessagingService::class.java) {
      val prefs = getSharedPreferences(PUSH_PREFERENCES, Context.MODE_PRIVATE)
      prefs
        .edit()
        .remove("recipientHash")
        .remove("organizationId")
        .putLong("rotationRevision", prefs.getLong("rotationRevision", 0) + 1)
        .commit()
      NotificationManagerCompat.from(this).cancelAll()
    }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    if (
      Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    )
      return
    val prefs = getSharedPreferences(PUSH_PREFERENCES, Context.MODE_PRIVATE)
    val org = prefs.getString("organizationId", null)
    val hash = prefs.getString("recipientHash", null)
    val id = acceptedAlertId(message.data, org, hash) ?: return
    synchronized(BpmMessagingService::class.java) {
      val seen = prefs.getStringSet("seen", emptySet()).orEmpty()
      if (id in seen) return
      val manager = getSystemService(NotificationManager::class.java)
      if (Build.VERSION.SDK_INT >= 26)
        manager.createNotificationChannel(
          NotificationChannel("bpm-alerts", "Avisos BPM", NotificationManager.IMPORTANCE_DEFAULT)
        )
      // El servidor manda el texto específico (tipo de aviso, sede, medición); sin él se conserva el genérico.
      val title = message.notification?.title?.takeIf { it.isNotBlank() } ?: "Tienes un aviso en BPM"
      val body = message.notification?.body?.takeIf { it.isNotBlank() } ?: "Abre la aplicación para consultar tus avisos."
      val route = acceptedPushRoute(message.data["route"]) ?: "avisos"
      val intent =
        Intent(this, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
          .putExtra("route", route)
      val pending =
        PendingIntent.getActivity(
          this,
          id.hashCode(),
          intent,
          PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
      val notification =
        NotificationCompat.Builder(this, "bpm-alerts")
          .setSmallIcon(android.R.drawable.ic_dialog_info)
          .setContentTitle(title)
          .setContentText(body)
          .setStyle(NotificationCompat.BigTextStyle().bigText(body))
          .setContentIntent(pending)
          .setAutoCancel(true)
          .build()
      // Releer el vínculo cierra la ventana entre una recepción y un logout concurrente.
      if (
        org != prefs.getString("organizationId", null) ||
          hash != prefs.getString("recipientHash", null)
      )
        return
      NotificationManagerCompat.from(this).notify(id, id.hashCode(), notification)
      prefs.edit().putStringSet("seen", (seen.toList() + id).takeLast(200).toSet()).commit()
    }
  }
}
