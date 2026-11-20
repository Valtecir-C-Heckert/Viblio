package br.com.centralcasa.data

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Token encrypted on this device, excluded from backups and library exports. */
class HomeAssistantCredentials(context: Context, private val preferenceName: String = "ha_credentials", private val keyAlias: String = "central_home_assistant") {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    val supported get() = Build.VERSION.SDK_INT >= 23
    fun clear() { prefs.edit().clear().apply() }
    @TargetApi(23)
    fun load(): String {
        if (!supported) return ""
        return try {
            val encrypted = prefs.getString("token", null) ?: return ""
            val iv = prefs.getString("iv", null) ?: return ""
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            if (isIrrecoverable(e)) clear()
            ""
        }
    }
    @TargetApi(23)
    fun save(token: String) {
        check(supported)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }
    @TargetApi(23)
    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    companion object {
        internal fun isIrrecoverable(error: Throwable): Boolean = when {
            error is AEADBadTagException ||
                error is BadPaddingException ||
                error is IllegalBlockSizeException ||
                error is IllegalArgumentException ||
                error.javaClass.name == "android.security.keystore.KeyPermanentlyInvalidatedException" -> true
            else -> error.cause?.let { it !== error && isIrrecoverable(it) } == true
        }
    }
}
