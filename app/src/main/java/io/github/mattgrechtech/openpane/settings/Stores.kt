package io.github.mattgrechtech.openpane.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import io.github.mattgrechtech.openpane.diag.AppLog

class PrefsStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putAll(values: Map<String, String?>) {
        val editor = prefs.edit()
        for ((key, value) in values) if (value == null) editor.remove(key) else editor.putString(key, value)
        editor.commit()
    }
}

/**
 * Secrets encrypted with an AES-GCM key held in the Android Keystore. The key never leaves the
 * Keystore, so a copy of the app's data folder alone doesn't reveal the tokens. If the key is lost
 * (for example after the user clears credentials), the values read as empty and must be set again.
 */
class EncryptedStore(private val prefs: SharedPreferences) : KeyValueStore {

    override fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.w(TAG, "Can't decrypt $key; treating it as unset", e)
            null
        }
    }

    override fun putAll(values: Map<String, String?>) {
        val editor = prefs.edit()
        for ((name, value) in values) {
            if (value.isNullOrEmpty()) {
                editor.remove(name)
                continue
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            editor.putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
        editor.commit()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "EncryptedStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "openpane_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}

fun createSettings(context: Context): Settings = Settings(
    PrefsStore(context.getSharedPreferences("settings", Context.MODE_PRIVATE)),
    EncryptedStore(context.getSharedPreferences("secrets", Context.MODE_PRIVATE)),
)
