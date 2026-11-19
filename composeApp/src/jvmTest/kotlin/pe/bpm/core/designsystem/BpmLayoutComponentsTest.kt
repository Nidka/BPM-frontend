package pe.bpm.core.designsystem

import kotlin.test.Test
import kotlin.test.assertEquals

/** Iniciales del avatar de `BpmListRow`. */
class BpmLayoutComponentsTest {
    @Test fun dosPalabrasDanDosIniciales() = assertEquals("AR", initialsOf("Ana Ríos"))
    @Test fun masDeDosPalabrasUsaLasDosPrimeras() = assertEquals("MD", initialsOf("María del Carmen García"))
    @Test fun unaPalabraDaUnaInicial() = assertEquals("L", initialsOf("luis"))
    @Test fun espaciosSobrantesSeIgnoran() = assertEquals("JP", initialsOf("  Juan   Pérez "))
    @Test fun vacioDaSignoDeInterrogacion() = assertEquals("?", initialsOf("   "))
}
