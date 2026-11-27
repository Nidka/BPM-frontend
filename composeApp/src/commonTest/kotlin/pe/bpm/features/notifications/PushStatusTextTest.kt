package pe.bpm.features.notifications

import pe.bpm.features.notifications.domain.*
import pe.bpm.features.notifications.presentation.*
import kotlin.test.Test
import kotlin.test.assertTrue

class PushStatusTextTest {
  @Test fun statusLineShowsTheRealState() {
    assertTrue(pushStatusText(NotificationDevicesState(available = true, device = NotificationDevice("49d000f4-e487-41cf-a166-741c98cd7e04", "ANDROID", "ACTIVE"))).startsWith("Activadas"))
    assertTrue(pushStatusText(NotificationDevicesState(available = true, failure = NotificationFailure.PERMISSION)).startsWith("Bloqueadas en Android"))
    assertTrue(pushStatusText(NotificationDevicesState(available = true, failure = NotificationFailure.CONNECTION)).startsWith("Sin conexión"))
    assertTrue(pushStatusText(NotificationDevicesState(available = true)).startsWith("Desactivadas"))
  }
}
