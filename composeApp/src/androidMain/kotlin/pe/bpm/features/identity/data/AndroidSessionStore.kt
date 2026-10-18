package pe.bpm.features.identity.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private, non-backed-up ciphertext. The AES key never leaves Android Keystore. */
class AndroidSessionStore(context: Context) : SessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "bpm-session.enc"))
    private val alias = "${context.packageName}.session.v1"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun read(): String? {
        return try {
            if (!file.baseFile.exists()) return null
            val payload = file.openRead().use { it.readBytes() }
            require(payload.size in 29..16384)
            val key = keyStore().getKey(alias, null) as? SecretKey ?: return clear().let { null }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            cipher.doFinal(payload.copyOfRange(12, payload.size)).decodeToString()
        } catch (_: Exception) {
            clear()
            null
        }
    }

    override fun write(value: String) {
        require(value.encodeToByteArray().size <= 12000)
        val store = keyStore()
        val key = store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.iv + cipher.doFinal(value.encodeToByteArray())
        val output = file.startWrite()
        try { output.write(encrypted); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    override fun clear() { file.delete() }
}
