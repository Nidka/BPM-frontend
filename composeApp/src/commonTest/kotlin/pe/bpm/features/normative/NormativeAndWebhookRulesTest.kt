package pe.bpm.features.normative

import pe.bpm.features.normative.domain.*
import pe.bpm.features.webhooks.domain.WebhookInput
import pe.bpm.features.webhooks.domain.WebhookRules
import kotlin.test.*

class NormativeAndWebhookRulesTest {
    @Test fun queryMatchesServerLimits() {
        assertNotNull(NormativeRules.queryIssue("  a "))
        assertNull(NormativeRules.queryIssue("pollo"))
        assertNotNull(NormativeRules.queryIssue("x".repeat(201)))
    }

    @Test fun highlightsFollowStemsWithoutAccents() {
        val text = "Los alimentos refrigerados se conservan en refrigeración entre 0 °C y 4 °C."
        val terms = NormativeRules.terms("Refrigeracion de alimento")
        assertEquals(listOf("refrigeracion", "alimento"), terms, "las palabras cortas como «de» no se resaltan")
        val words = NormativeRules.highlights(text, terms).map { text.substring(it) }
        assertEquals(listOf("alimentos", "refrigerados", "refrigeración"), words)
        assertTrue(NormativeRules.highlights(text, emptyList()).isEmpty())
    }

    @Test fun documentNeedsTitleAndText() {
        assertEquals(setOf("title", "content"), NormativeRules.documentIssues(NormativeDocumentInput(" ", "", " ")).keys)
        assertTrue(NormativeRules.documentIssues(NormativeDocumentInput("NTS 142", "", "Artículo 1.")).isEmpty())
        assertTrue("title" in NormativeRules.documentIssues(NormativeDocumentInput("a\nb", "", "x")))
    }

    @Test fun serverStatusesMapToClearReasons() {
        assertEquals(NormativeFailure.SESSION, pe.bpm.features.normative.data.failureFor(401))
        assertEquals(NormativeFailure.NOT_FOUND, pe.bpm.features.normative.data.failureFor(404))
        assertEquals(NormativeFailure.INVALID, pe.bpm.features.normative.data.failureFor(422))
        assertEquals(NormativeFailure.UNAVAILABLE, pe.bpm.features.normative.data.failureFor(503))
    }

    @Test fun webhookUrlMustBePublicHttps() {
        assertNull(WebhookRules.urlIssue("https://n8n.miempresa.pe/webhook/bpm"))
        for (bad in listOf("", "http://n8n.pe/x", "https://localhost/x", "https://192.168.1.4/x", "https://intranet/x", "https://u:p@n8n.pe/x", "https://n8n.local/x"))
            assertNotNull(WebhookRules.urlIssue(bad), bad)
        assertEquals(setOf("eventTypes"), WebhookRules.issues(WebhookInput("https://n8n.pe/x", emptySet())).keys)
    }
}
