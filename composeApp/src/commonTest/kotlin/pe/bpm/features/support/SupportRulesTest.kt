package pe.bpm.features.support

import pe.bpm.features.support.domain.SupportRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupportRulesTest {
    @Test fun validatesEmailAndOptionalWhatsapp() {
        assertNull(SupportRules.emailError("proyectobpmupn@gmail.com"))
        assertNull(SupportRules.emailError("  soporte@bpm.pe "))
        listOf("", "soporte", "a@b", "a b@c.pe", "<a@b.pe>").forEach { assertNotNull(SupportRules.emailError(it), it) }
        assertNull(SupportRules.whatsappError(""))
        assertNull(SupportRules.whatsappError("+51 987-654-321"))
        assertNotNull(SupportRules.whatsappError("123"))
        assertNotNull(SupportRules.whatsappError("51abc987654"))
        assertEquals("51987654321", SupportRules.whatsappDigits("+51 987 654 321"))
    }

    @Test fun mailtoCarriesSubjectAndContextButNoSecrets() {
        val body = SupportRules.messageBody("Jefe de cocina", "Pollería Don Pepe", "0.1.0", "Android")
        assertTrue("Rol: Jefe de cocina" in body && "Organización: Pollería Don Pepe" in body && "versión 0.1.0 (Android)" in body)
        assertFalse(body.contains("token", ignoreCase = true) || body.contains("Bearer"))
        val url = SupportRules.mailtoUrl("proyectobpmupn@gmail.com", body)
        assertTrue(url.startsWith("mailto:proyectobpmupn@gmail.com?subject=Soporte%20BPM&body="))
        assertFalse(' ' in url || '\n' in url)
        assertTrue("Poller%C3%ADa" in url)
        assertFalse("Organización" in SupportRules.messageBody("Personal", null, "0.1.0", "Web"))
    }

    @Test fun whatsappLinkUsesOnlyDigits() {
        assertTrue(SupportRules.whatsappUrl("+51 987 654 321", "hola").startsWith("https://wa.me/51987654321?text=hola"))
    }
}
