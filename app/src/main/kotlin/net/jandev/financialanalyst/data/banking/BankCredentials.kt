package net.jandev.financialanalyst.data.banking

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import net.jandev.financialanalyst.domain.banking.ApiToken
import java.security.KeyStore
import java.security.PrivateKey
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class BankSetup(
    val applicationId: String,
    val redirectUrl: String,
    val hasKey: Boolean,
) {
    val isComplete: Boolean get() = applicationId.isNotBlank() && redirectUrl.isNotBlank() && hasKey
}

/** Eine begonnene, noch nicht abgeschlossene Bankfreigabe. */
data class PendingAuth(val state: String, val bankName: String, val country: String, val validUntilEpochSeconds: Long)

/**
 * Zugangsdaten für Enable Banking. Der private Schlüssel liegt nur AES-GCM-verschlüsselt auf dem Gerät;
 * der Schlüssel dafür steckt im Android Keystore und verlässt ihn nie. Von der Cloud-Sicherung ausgenommen.
 */
class BankCredentials(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val setup: StateFlow<BankSetup> = state

    @Volatile
    private var cachedKey: PrivateKey? = null

    private fun read() = BankSetup(
        applicationId = prefs.getString(KEY_APP_ID, "").orEmpty(),
        redirectUrl = prefs.getString(KEY_REDIRECT, DEFAULT_REDIRECT).orEmpty(),
        hasKey = prefs.contains(KEY_PEM),
    )

    fun save(applicationId: String, redirectUrl: String) {
        prefs.edit().putString(KEY_APP_ID, applicationId.trim()).putString(KEY_REDIRECT, redirectUrl.trim()).apply()
        state.value = read()
    }

    /** Prüft die PEM-Datei und legt sie verschlüsselt ab. Wirft [ApiToken.InvalidKeyException] bei unbrauchbaren Dateien. */
    fun importKey(pem: String) {
        val key = ApiToken.parsePrivateKey(pem)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        val encrypted = cipher.doFinal(pem.toByteArray())
        prefs.edit()
            .putString(KEY_PEM, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        cachedKey = key
        state.value = read()
    }

    /** Merker für einmalige Nacharbeiten an den abgerufenen Daten. */
    fun onceDone(name: String): Boolean = prefs.getBoolean("once_$name", false)

    fun markDone(name: String) = prefs.edit().putBoolean("once_$name", true).apply()

    fun clear() {
        prefs.edit().remove(KEY_PEM).remove(KEY_IV).remove(KEY_APP_ID).remove(KEY_PENDING).apply()
        cachedKey = null
        state.value = read()
    }

    private fun privateKey(): PrivateKey {
        cachedKey?.let { return it }
        val encrypted = prefs.getString(KEY_PEM, null) ?: error("Es ist keine Schlüsseldatei hinterlegt.")
        val iv = prefs.getString(KEY_IV, null) ?: error("Es ist keine Schlüsseldatei hinterlegt.")
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        }
        val pem = String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)))
        return ApiToken.parsePrivateKey(pem).also { cachedKey = it }
    }

    fun token(): String = ApiToken.create(state.value.applicationId, privateKey(), System.currentTimeMillis() / 1000)

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    var pendingAuth: PendingAuth?
        get() {
            val parts = prefs.getString(KEY_PENDING, null)?.split('\u0001') ?: return null
            if (parts.size != 4) return null
            return PendingAuth(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: return null)
        }
        set(value) {
            prefs.edit().apply {
                if (value == null) {
                    remove(KEY_PENDING)
                } else {
                    putString(KEY_PENDING, listOf(value.state, value.bankName, value.country, value.validUntilEpochSeconds).joinToString("\u0001"))
                }
            }.apply()
        }

    companion object {
        const val PREFS = "bank_credentials"
        const val DEFAULT_REDIRECT = "finanzanalyst://callback"
        private const val KEY_APP_ID = "application_id"
        private const val KEY_REDIRECT = "redirect_url"
        private const val KEY_PEM = "pem"
        private const val KEY_IV = "pem_iv"
        private const val KEY_PENDING = "pending_auth"
        private const val KEYSTORE_ALIAS = "enable_banking_key_wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
