package pe.bpm.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Archivos privados sin backup, cifrados con AES/GCM y clave no exportable de Android Keystore. */
class AndroidDraftStorage(context: Context) : DraftStorage {
    private val directory = File(context.noBackupFilesDir, "drafts-v1")
    private val alias = "${context.packageName}.drafts.v1"
    private val mutex = Mutex()
    private fun file(key: String) = AtomicFile(File(directory,
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    override suspend fun read(key: String): String? = withContext(Dispatchers.IO) { mutex.withLock {
        val file = file(key)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withLock null
        val bytes = file.openRead().use { input ->
            require(file.baseFile.length() <= MAX_DRAFT_BYTES + 28)
            input.readBytes()
        }
        require(bytes.size in 29..MAX_DRAFT_BYTES + 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(key.toByteArray())
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).decodeToString()
    } }
    override suspend fun write(key: String, value: String) = withContext(Dispatchers.IO) { mutex.withLock {
        val bytes = value.encodeToByteArray(); require(bytes.size in 1..MAX_DRAFT_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(key.toByteArray())
        val encrypted = cipher.iv + cipher.doFinal(bytes)
        val file = file(key); val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    } }
    override suspend fun remove(key: String) = withContext(Dispatchers.IO) { mutex.withLock { file(key).delete() } }
}
