package pe.bpm.core

import pe.bpm.core.domain.*
import kotlin.test.*

class InputRulesTest {
    @Test fun personNamesPreserveUnicodeAndRejectDigitsWithoutConstrainingBusinessNames() {
        val name = InputRules.personName(80)
        listOf("María José", "Muñoz", "O’Connor", "D'Ávila", "Jean-Luc", "Jose\u0301", "李明").forEach { assertNull(name.error(it), it) }
        listOf("Ana2", "123", "Ana 🙂", "Ana\nMaría", "-Ana", "Ana_", "").forEach { assertNotNull(name.error(it), it) }
        assertNull(InputRules.text(2, 200).error("Restaurante 24 S.A.C."))
        assertNull(InputRules.text(2, 160).error("Sede 2 – Lima"))
        assertNull(InputRules.personName(160, required = false).error(""))
    }

    @Test fun formattedPhoneAndEmailStillValidatePastedOrPhysicalKeyboardInput() {
        listOf("+51 987 654 321", "(01) 234-5678", "").forEach { assertNull(InputRules.phone().error(it), it) }
        listOf("123", "llámame", "999+999999", "999 999 999\n", "1234567890123456").forEach { assertNotNull(InputRules.phone().error(it), it) }
        listOf("person+chef@example.com", "nombre.apellido@example.com").forEach { assertNull(InputRules.email().error(it), it) }
        listOf("person", "a@@b.com", "a b@example.com", "<a>@example.com", "a..b@example.com", "a@-example.com", "a@example.com\n").forEach { assertNotNull(InputRules.email().error(it), it) }
        assertNull(InputRules.email(required = false).error(""))
        assertNotNull(InputRules.email().error(""))
    }

    @Test fun decimalsUseTheSameParserForCommaSignAndPrecision() {
        assertEquals(-18.25, DecimalInput.parse("-18,25"))
        assertEquals(0.5, DecimalInput.parse(".5"))
        listOf("1e3", "NaN", "Infinity", "1.234", "1,2.3", "100000000", "", "--18").forEach { assertNull(DecimalInput.parse(it), it) }
        assertNull(DecimalInput.error("-18"))
        for (value in listOf(10000000.0, 99999999.99, -18.25, -0.5, 0.0)) {
            assertEquals(value, DecimalInput.parse(assertNotNull(DecimalInput.format(value))))
        }
        assertNull(DecimalInput.parse("18\n"))
        assertNotNull(InputRules.text(0, 20).error("A".repeat(21)))
        assertNull(InputRules.text(0, 2000, maxBytes = 2000).error("ñ".repeat(1000)))
        assertNotNull(InputRules.text(0, 2000, maxBytes = 2000).error("ñ".repeat(1001)))
    }
}
