package pe.bpm.features.normative

import pe.bpm.features.normative.domain.*
import pe.bpm.features.webhooks.domain.WebhookInput
import pe.bpm.features.webhooks.domain.WebhookRules
import kotlin.test.*

class NormativeAndWebhookRulesTest {
    @Test fun questionMatchesServerLimits() {
        assertNotNull(NormativeRules.questionIssue("  a "))
        assertNull(NormativeRules.questionIssue("¿Cómo lavo verduras?"))
        assertNotNull(NormativeRules.questionIssue("x".repeat(1001)))
    }

    @Test fun documentNeedsTitleAndText() {
        assertEquals(setOf("title", "content"), NormativeRules.documentIssues(NormativeDocumentInput(" ", "", " ")).keys)
        assertTrue(NormativeRules.documentIssues(NormativeDocumentInput("NTS 142", "", "Artículo 1.")).isEmpty())
        assertTrue("title" in NormativeRules.documentIssues(NormativeDocumentInput("a\nb", "", "x")))
    }

    @Test fun serverCodesMapToClearReasons() {
        assertEquals(NormativeFailure.DISABLED, pe.bpm.features.normative.data.failureFor(503, "NORMATIVE_ASSISTANT_DISABLED"))
        assertEquals(NormativeFailure.UNAVAILABLE, pe.bpm.features.normative.data.failureFor(503, "NORMATIVE_UNAVAILABLE"))
        assertEquals(NormativeFailure.NO_DOCUMENTS, pe.bpm.features.normative.data.failureFor(409, "NORMATIVE_NO_DOCUMENTS"))
        assertEquals(NormativeFailure.TOO_LARGE, pe.bpm.features.normative.data.failureFor(422, "NORMATIVE_TOO_LARGE"))
        assertEquals(NormativeFailure.RATE_LIMITED, pe.bpm.features.normative.data.failureFor(429, ""))
    }

    @Test fun webhookUrlMustBePublicHttps() {
        assertNull(WebhookRules.urlIssue("https://n8n.miempresa.pe/webhook/bpm"))
        for (bad in listOf("", "http://n8n.pe/x", "https://localhost/x", "https://192.168.1.4/x", "https://intranet/x", "https://u:p@n8n.pe/x", "https://n8n.local/x"))
            assertNotNull(WebhookRules.urlIssue(bad), bad)
        assertEquals(setOf("eventTypes"), WebhookRules.issues(WebhookInput("https://n8n.pe/x", emptySet())).keys)
    }
}
