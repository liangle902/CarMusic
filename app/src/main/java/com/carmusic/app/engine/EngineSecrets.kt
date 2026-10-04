package com.carmusic.app.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The engine's data key is wrapped by a non-exportable Android Keystore key. */
internal object EngineSecrets {
    private const val ALIAS = "carmusic.engine.storage.v1"
    @Synchronized fun storageKey(context: Context): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val wrapped = AtomicFile(File(context.noBackupFilesDir, "engine-storage-key"))
        val existing = wrapped.baseFile.isFile || File(wrapped.baseFile.path + ".bak").isFile
        check(existing || !store.containsAlias(ALIAS) || !File(context.filesDir,"settings.db").exists()) {
            "账号存储密钥文件丢失，请恢复备份，或清除应用数据后重新关联账号"
        }
        val key = (store.getKey(ALIAS, null) as? SecretKey) ?: run {
            // Do not silently replace a key when encrypted account data exists.
            check(!existing) { "账号存储密钥不可用，请重新关联账号" }
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        val data = if (existing) {
            val bytes = wrapped.openRead().use { it.readBytes() }
            check(bytes.size == 60) { "账号存储密钥文件损坏" }
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                doFinal(bytes.copyOfRange(12, bytes.size))
            }
        } else {
            val generated = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val bytes = cipher.iv + cipher.doFinal(generated)
            val output = wrapped.startWrite()
            try { output.write(bytes); wrapped.finishWrite(output) }
            catch (error: Exception) { wrapped.failWrite(output); throw error }
            generated
        }
        check(data.size == 32) { "账号存储密钥无效" }
        return data.joinToString("") { "%02x".format(it) }.also { data.fill(0) }
    }
}
