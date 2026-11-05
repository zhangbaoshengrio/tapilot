package lifeos.md.tapilot.bridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One atomic encrypted value per key, protected by an app-private Android Keystore key. */
class EncryptedPreferences(context: Context, name: String, private val keyAlias: String) {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    @Synchronized fun get(key: String): String? = preferences.getString(key, null)?.let { stored ->
        try {
            decrypt(stored)
        } catch (error: java.security.GeneralSecurityException) {
            discardUnreadable(key, error)
        } catch (error: IllegalArgumentException) {
            discardUnreadable(key, error)
        }
    }

    /** The Keystore key no longer matches this value (e.g. it was reset), so it can never be read again. */
    private fun discardUnreadable(key: String, error: Exception): String? {
        android.util.Log.w("TapilotCredentials", "Dropping unreadable credential: $key", error)
        preferences.edit().remove(key).commit()
        return null
    }
    @Synchronized fun keys(): Set<String> = preferences.all.keys
    @Synchronized fun set(key: String, value: String) {
        check(preferences.edit().putString(key, encrypt(value)).commit()) { "Unable to save credential" }
    }
    @Synchronized fun remove(key: String) {
        check(preferences.edit().remove(key).commit()) { "Unable to delete credential" }
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val ciphertext = Base64.encodeToString(
            cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP,
        )
        return "$iv.$ciphertext"
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2) { "Invalid encrypted model credential" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object { const val TRANSFORMATION = "AES/GCM/NoPadding" }
}
