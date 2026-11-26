package pe.bpm.features.media.presentation

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pe.bpm.features.media.domain.EvidencePhotoSelectionRules
import pe.bpm.features.media.domain.PhotoPreparation
import pe.bpm.features.media.domain.PhotoSelection
import pe.bpm.features.media.domain.PhotoSelectionFailure

/** Selector de la galería (Photo Picker del sistema; no pide permisos de almacenamiento). */
@Composable
actual fun rememberEvidencePhotoPicker(
    maxBytes: Int,
    allowedMimeTypes: Set<String>,
    onResult: (PhotoSelection) -> Unit,
): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    val currentLimit = rememberUpdatedState(maxBytes)
    val currentTypes = rememberUpdatedState(allowedMimeTypes)
    val busy = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) {
            busy.set(false)
            callback.value(PhotoSelection.Cancelled)
        } else {
            val limit = currentLimit.value
            val types = currentTypes.value
            scope.launch {
                val result = readPhoto(resolver, uri, limit, types)
                busy.set(false)
                callback.value(result)
            }
        }
    }
    return remember(launcher, maxBytes, allowedMimeTypes) {
        {
            if (busy.compareAndSet(false, true)) {
                if (maxBytes <= 0 || allowedMimeTypes.intersect(EvidencePhotoSelectionRules.supportedMimeTypes).isEmpty()) {
                    busy.set(false)
                    callback.value(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE))
                } else try {
                    launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } catch (_: Exception) {
                    busy.set(false)
                    callback.value(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE))
                }
            }
        }
    }
}

/**
 * Cámara del dispositivo (`TakePicture` con un archivo temporal del proveedor de la app). No requiere el permiso
 * CAMERA porque la app no lo declara: la toma la aplicación de cámara del sistema. La ruta del archivo se conserva
 * al recrearse la Activity (poca memoria al abrir la cámara) para no perder la foto al volver.
 */
@Composable
actual fun rememberEvidenceCamera(
    maxBytes: Int,
    allowedMimeTypes: Set<String>,
    onResult: (PhotoSelection) -> Unit,
): (() -> Unit)? {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    val currentLimit = rememberUpdatedState(maxBytes)
    val currentTypes = rememberUpdatedState(allowedMimeTypes)
    var capturePath by rememberSaveable { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val file = capturePath.takeIf { it.isNotEmpty() }?.let(::File)
        capturePath = ""
        if (!saved || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            callback.value(PhotoSelection.Cancelled)
        } else {
            val limit = currentLimit.value
            val types = currentTypes.value
            scope.launch {
                val result = readPhoto(resolver, Uri.fromFile(file), limit, types, nameOverride = "foto")
                withContext(Dispatchers.IO) { file.delete() }
                callback.value(result)
            }
        }
    }
    return remember(launcher, maxBytes, allowedMimeTypes) {
        {
            if (maxBytes <= 0 || "image/jpeg" !in allowedMimeTypes) {
                callback.value(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE))
            } else try {
                val directory = File(context.cacheDir, "evidence-capture").apply { mkdirs() }
                directory.listFiles()?.forEach { it.delete() }
                val target = File.createTempFile("captura-", ".jpg", directory)
                capturePath = target.absolutePath
                launcher.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target))
            } catch (_: Exception) {
                capturePath = ""
                callback.value(PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE))
            }
        }
    }
}

/**
 * Lee la foto del [uri], la deja dentro de los límites de la política (reduce y convierte a JPEG si hace falta) y la valida.
 * Nunca lanza: cualquier problema de lectura es un [PhotoSelection.Failed] que la pantalla muestra.
 */
private suspend fun readPhoto(
    resolver: ContentResolver,
    uri: Uri,
    limit: Int,
    types: Set<String>,
    nameOverride: String? = null,
): PhotoSelection = withContext(Dispatchers.IO) {
    try {
        if (limit <= 0) return@withContext PhotoSelection.Failed(PhotoSelectionFailure.UNAVAILABLE)
        val original = readAll(resolver, uri) ?: return@withContext PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
        if (original.size > PhotoPreparation.MAX_SOURCE_BYTES) return@withContext PhotoSelection.Failed(PhotoSelectionFailure.TOO_LARGE)
        if (original.isEmpty()) return@withContext PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
        val displayName = nameOverride ?: displayName(resolver, uri)
        when (val plan = PhotoPreparation.plan(bounds.outMimeType, original.size.toLong(), bounds.outWidth, bounds.outHeight, limit, types)) {
            PhotoPreparation.Plan.PassThrough -> {
                val mime = bounds.outMimeType
                EvidencePhotoSelectionRules.validate(PhotoPreparation.fileName(displayName, mime), mime, original, limit, types)
            }
            PhotoPreparation.Plan.Unsupported -> PhotoSelection.Failed(PhotoSelectionFailure.UNSUPPORTED)
            is PhotoPreparation.Plan.Recode -> {
                val jpeg = recodeAsJpeg(original, plan.sampleSize, limit)
                    ?: return@withContext PhotoSelection.Failed(PhotoSelectionFailure.TOO_LARGE)
                EvidencePhotoSelectionRules.validate(PhotoPreparation.fileName(displayName, "image/jpeg"), "image/jpeg", jpeg, limit, types)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: OutOfMemoryError) {
        PhotoSelection.Failed(PhotoSelectionFailure.TOO_LARGE)
    } catch (_: Exception) {
        PhotoSelection.Failed(PhotoSelectionFailure.READ_FAILED)
    }
}

private suspend fun readAll(resolver: ContentResolver, uri: Uri): ByteArray? =
    resolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            kotlin.coroutines.coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            // Un byte por encima del tope basta para rechazar sin leer archivos enormes.
            if (output.size() > PhotoPreparation.MAX_SOURCE_BYTES) break
        }
        output.toByteArray()
    }

private fun displayName(resolver: ContentResolver, uri: Uri): String? = try {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
} catch (_: Exception) { null }

/** Decodifica (con reducción), corrige la orientación EXIF y guarda como JPEG bajando calidad y tamaño hasta que quepa en [limit]. */
private fun recodeAsJpeg(original: ByteArray, sampleSize: Int, limit: Int): ByteArray? {
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.ARGB_8888 }
    val decoded = BitmapFactory.decodeByteArray(original, 0, original.size, options) ?: return null
    var bitmap = flattened(oriented(decoded, orientationOf(original)))
    var attempts = 0
    while (attempts < 4) {
        for (quality in PhotoPreparation.JPEG_QUALITIES) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() in 1..limit) { bitmap.recycle(); return out.toByteArray() }
        }
        // Ni con la calidad mínima cabe: se reduce a tres cuartos de cada lado y se vuelve a intentar.
        val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width * 3 / 4), maxOf(1, bitmap.height * 3 / 4), true)
        if (smaller !== bitmap) bitmap.recycle()
        bitmap = smaller
        attempts++
    }
    bitmap.recycle()
    return null
}

private fun orientationOf(bytes: ByteArray): Int = try {
    ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
} catch (_: Exception) { ExifInterface.ORIENTATION_NORMAL }

private fun oriented(source: Bitmap, orientation: Int): Bitmap {
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
        ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
        ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
        else -> return source
    }
    val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    if (rotated !== source) source.recycle()
    return rotated
}

/** JPEG no tiene transparencia: PNG/WebP con alfa se pintan sobre blanco para que no queden zonas negras. */
private fun flattened(source: Bitmap): Bitmap {
    if (!source.hasAlpha()) return source
    val flat = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(source, 0f, 0f, null) }
    source.recycle()
    return flat
}
