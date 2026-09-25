package com.translateoverlay.translate

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * API keys encrypted with an AES-GCM key held in the Android Keystore (never exported, never in
 * backups: the ciphertext is useless on another device). Stored apart from the settings.
 */
class SecretStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrBlank()) {
            prefs.edit().remove(name).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.iv + cipher.doFinal(value.trim().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return runCatching {
            val data = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_SIZE))
            String(cipher.doFinal(data, IV_SIZE, data.size - IV_SIZE), Charsets.UTF_8)
        }.getOrNull() // key lost (e.g. restored on another device): behave as "no key"
    }

    fun has(name: String): Boolean = prefs.contains(name)

    companion object {
        const val AZURE_KEY = "azure_key"
        const val GOOGLE_KEY = "google_cloud_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "translateoverlay-api-keys"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
