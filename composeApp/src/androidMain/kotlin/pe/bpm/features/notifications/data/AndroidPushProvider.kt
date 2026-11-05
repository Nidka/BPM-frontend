package pe.bpm.features.notifications.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pe.bpm.core.config.*
import pe.bpm.features.notifications.domain.*

/** Inicializa el SDK sin generar tokens ni pedir permisos durante el arranque. */
class BpmFirebaseApplication : Application() {
  override fun onCreate() {
    super.onCreate()
    firebaseMessaging(this)
  }
}

internal fun firebaseMessaging(context: Context): FirebaseMessaging? {
  if (
    FIREBASE_ANDROID_APP_ID.isBlank() ||
      FIREBASE_MESSAGING_SENDER_ID.isBlank() ||
      FIREBASE_PROJECT_ID.isBlank() ||
      FIREBASE_API_KEY.isBlank()
  )
    return null
  FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
    ?: FirebaseApp.initializeApp(
      context,
      FirebaseOptions.Builder()
        .setApplicationId(FIREBASE_ANDROID_APP_ID)
        .setGcmSenderId(FIREBASE_MESSAGING_SENDER_ID)
        .setProjectId(FIREBASE_PROJECT_ID)
        .setApiKey(FIREBASE_API_KEY)
        .build(),
    )
  return FirebaseMessaging.getInstance().also { it.isAutoInitEnabled = false }
}

internal const val PUSH_PREFERENCES = "bpm-private-push"

internal fun recipientHash(user: String) =
  MessageDigest.getInstance("SHA-256").digest(user.toByteArray(Charsets.UTF_8)).joinToString("") {
    "%02x".format(it.toInt() and 255)
  }

class AndroidPushProvider(private val application: Application) : PushProvider, AutoCloseable {
  override val available
    get() =
      FIREBASE_ANDROID_APP_ID.isNotBlank() &&
        FIREBASE_MESSAGING_SENDER_ID.isNotBlank() &&
        FIREBASE_PROJECT_ID.isNotBlank() &&
        FIREBASE_API_KEY.isNotBlank()

  override val unavailableReason =
    "No está configurado el registro público de notificaciones Android. La bandeja sigue funcionando."
  private val prefs = application.getSharedPreferences(PUSH_PREFERENCES, Context.MODE_PRIVATE)
  override val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
  private val rotationListener =
    android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
      if (key == "rotationRevision") invalidations.tryEmit(Unit)
    }

  init {
    prefs.registerOnSharedPreferenceChangeListener(rotationListener)
  }

  override fun tokenCurrent(token: PushToken) =
    token.revision == prefs.getLong("rotationRevision", 0)

  override fun close() {
    detach()
    prefs.unregisterOnSharedPreferenceChangeListener(rotationListener)
  }

  private var launcher: ActivityResultLauncher<String>? = null
  private var permission: CancellableContinuation<Boolean>? = null
  private val mutex = Mutex()

  fun attach(activity: ComponentActivity) {
    launcher?.unregister()
    launcher =
      activity.activityResultRegistry.register(
        "bpm-push-permission",
        activity,
        ActivityResultContracts.RequestPermission(),
      ) { granted ->
        permission?.let {
          permission = null
          if (it.isActive) it.resume(granted)
        }
      }
  }

  fun detach() {
    launcher?.unregister()
    launcher = null
    permission?.cancel()
    permission = null
  }

  private suspend fun permissionGranted(): Boolean {
    if (
      Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(application, Manifest.permission.POST_NOTIFICATIONS) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      val current = launcher ?: return false
      val granted =
        suspendCancellableCoroutine<Boolean> { next ->
          permission = next
          next.invokeOnCancellation { if (permission === next) permission = null }
          current.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      if (!granted) return false
    }
    return NotificationManagerCompat.from(application).areNotificationsEnabled()
  }

  // El contrato backend usa tokens FCM; no habilita el modo de envío directo por FID de SDK 26.
  @Suppress("DEPRECATION")
  override suspend fun requestToken(userId: String, organizationId: String): PushTokenResult =
    mutex.withLock {
      if (!available) return@withLock PushTokenResult.Failure(NotificationFailure.UNAVAILABLE)
      if (!permissionGranted())
        return@withLock PushTokenResult.Failure(NotificationFailure.PERMISSION)
      try {
        val messaging =
          firebaseMessaging(application)
            ?: return@withLock PushTokenResult.Failure(NotificationFailure.UNAVAILABLE)
        var token: String? = null
        var revision = 0L
        repeat(2) {
          if (token == null) {
            val before = prefs.getLong("rotationRevision", 0)
            val candidate = messaging.token.awaitPush()
            val after = prefs.getLong("rotationRevision", 0)
            if (before == after) {
              token = candidate
              revision = after
            }
          }
        }
        val stableToken =
          token ?: return@withLock PushTokenResult.Failure(NotificationFailure.TOKEN_CHANGED)
        // IDs aleatorios privados por cuenta y empresa; no se reutilizan entre destinatarios.
        val scope =
          recipientHash("${userId.length}:$userId${organizationId.length}:$organizationId")
        val installationKey = "installation-$scope"
        val deviceKey = "device-$scope"
        val installation =
          prefs.getString(installationKey, null)
            ?: UUID.randomUUID().toString().also {
              check(prefs.edit().putString(installationKey, it).commit())
            }
        val id =
          prefs.getString(deviceKey, null)
            ?: UUID.randomUUID().toString().also {
              check(prefs.edit().putString(deviceKey, it).commit())
            }
        PushTokenResult.Success(PushToken(id, installation, "ANDROID", stableToken, revision))
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        PushTokenResult.Failure(NotificationFailure.CONNECTION)
      }
    }

  override fun bindCurrent(
    userId: String,
    organizationId: String,
    deviceId: String,
    token: PushToken,
  ): Boolean =
    synchronized(BpmMessagingService::class.java) {
      if (!tokenCurrent(token)) false
      else {
        bind(userId, organizationId, deviceId)
        true
      }
    }

  override fun bind(userId: String, organizationId: String, deviceId: String) {
    synchronized(BpmMessagingService::class.java) {
      check(
        prefs
          .edit()
          .putString("recipientHash", recipientHash(userId))
          .putString("organizationId", organizationId)
          .putString("deviceId", deviceId)
          .commit()
      )
    }
  }

  override fun clearBinding() {
    synchronized(BpmMessagingService::class.java) {
      // commit termina antes de devolver control: ningún push tardío usa el destinatario anterior.
      prefs
        .edit()
        .remove("recipientHash")
        .remove("organizationId")
        .remove("deviceId")
        .remove("seen")
        .commit()
      NotificationManagerCompat.from(application).cancelAll()
    }
  }

  @Suppress("DEPRECATION")
  override suspend fun deleteToken() {
    mutex.withLock { firebaseMessaging(application)?.deleteToken()?.awaitPush() }
  }
}

private suspend fun <T> Task<T>.awaitPush(): T = suspendCancellableCoroutine { continuation ->
  addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
  addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
}
