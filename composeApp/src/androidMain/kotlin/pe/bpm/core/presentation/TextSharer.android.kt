package pe.bpm.core.presentation

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Android: hoja nativa de compartir y portapapeles marcado como sensible (sin vista previa en Android 13+). */
@Composable
actual fun rememberTextSharer(): TextSharer {
    val context = LocalContext.current
    return remember(context) {
        object : TextSharer {
            override fun share(title: String, text: String, onResult: (ShareResult) -> Unit) {
                val ok = try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    val chooser = Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(chooser)
                    true
                } catch (_: Exception) {
                    false
                }
                onResult(if (ok) ShareResult.SHARED else ShareResult.FAILED)
            }

            override fun copy(text: String, onResult: (ShareResult) -> Unit) {
                val ok = try {
                    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("", text)
                    if (Build.VERSION.SDK_INT >= 33)
                        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                    manager.setPrimaryClip(clip)
                    true
                } catch (_: Exception) {
                    false
                }
                onResult(if (ok) ShareResult.COPIED else ShareResult.FAILED)
            }
        }
    }
}
