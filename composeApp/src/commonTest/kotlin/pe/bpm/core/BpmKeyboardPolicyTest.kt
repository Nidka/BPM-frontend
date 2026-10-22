package pe.bpm.core

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import pe.bpm.core.designsystem.*
import kotlin.test.*

class BpmKeyboardPolicyTest {
    @Test fun libraryDefaultsDoNotOverrideNextOrDoneAndExplicitSearchIsPreserved() {
        assertEquals(ImeAction.Next, BpmInputKind.Text.keyboardOptions().forBpmField(true, true).imeAction)
        assertEquals(ImeAction.Done, BpmInputKind.Password.keyboardOptions().forBpmField(true, false).imeAction)
        assertEquals(ImeAction.Next, KeyboardOptions(imeAction = ImeAction.Default).forBpmField(true, true).imeAction)
        assertEquals(ImeAction.Search, KeyboardOptions(imeAction = ImeAction.Search).forBpmField(true, true).imeAction)
        assertEquals(ImeAction.Default, KeyboardOptions().forBpmField(false, null).imeAction)
        assertEquals(KeyboardType.Decimal, BpmInputKind.Decimal.keyboardOptions().forBpmField(true, true).keyboardType)
        assertEquals(KeyboardType.Phone, BpmInputKind.Phone.keyboardOptions().forBpmField(true, false).keyboardType)
    }
}
