package pe.bpm.features.training

import pe.bpm.features.training.presentation.groupVerificationCode
import pe.bpm.features.training.presentation.reviewOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewAndCodeTest {
    @Test
    fun `la revision pone primero las preguntas sin responder y conserva el orden de cada grupo`() {
        assertEquals(listOf(1, 3, 0, 2), reviewOrder(listOf(true, false, true, false)))
        assertEquals(listOf(0, 1, 2), reviewOrder(listOf(true, true, true)))
        assertEquals(emptyList(), reviewOrder(emptyList()))
    }

    @Test
    fun `el codigo de constancia se agrupa de cuatro en cuatro`() {
        assertEquals("K7M2 QXD4 WPLR 5TNB 3HVZ 6CJA", groupVerificationCode("K7M2QXD4WPLR5TNB3HVZ6CJA"))
        assertEquals("ABCD EF", groupVerificationCode("ABCDEF"))
    }
}
