package net.jandev.financialanalyst.data.backup

import android.content.Context
import android.content.Intent
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jandev.financialanalyst.data.SettingsStore
import net.jandev.financialanalyst.data.db.AppDatabase
import net.jandev.financialanalyst.domain.BackupCrypto
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
private data class BackupMeta(val schemaVersion: Int, val createdAtEpochSeconds: Long, val budgetStartDay: Int, val reserveCents: Long)

data class BackupState(
    val hasPassword: Boolean,
    val driveEnabled: Boolean,
    val lastDriveBackupEpochSeconds: Long?,
    val lastDriveError: String?,
)

/**
 * Erstellt und lädt verschlüsselte Sicherungen der App-Daten (Datenbank und Budgeteinstellungen).
 * Die Zugangsdaten für den Bankabruf sind bewusst nicht enthalten; sie bleiben an das Gerät gebunden.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsStore,
) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(readState())
    val state: StateFlow<BackupState> = _state

    private fun readState() = BackupState(
        hasPassword = prefs.contains(KEY_PASSWORD),
        driveEnabled = prefs.getBoolean(KEY_DRIVE, false),
        lastDriveBackupEpochSeconds = prefs.getLong(KEY_LAST, 0).takeIf { it > 0 },
        lastDriveError = prefs.getString(KEY_ERROR, null),
    )

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    /** Merkt sich das Sicherungspasswort (im Keystore verschlüsselt), damit automatische Sicherungen laufen können. */
    fun setPassword(password: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, wrappingKey()) }
        prefs.edit()
            .putString(KEY_PASSWORD, Base64.encodeToString(cipher.doFinal(password.toByteArray()), Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        _state.value = readState()
    }

    fun storedPassword(): String? {
        val encrypted = prefs.getString(KEY_PASSWORD, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)))
        }.getOrNull()
    }

    fun setDriveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DRIVE, enabled).apply()
        _state.value = readState()
    }

    fun recordDriveResult(error: String?) {
        prefs.edit().apply {
            if (error == null) {
                putLong(KEY_LAST, System.currentTimeMillis() / 1000)
                remove(KEY_ERROR)
            } else {
                putString(KEY_ERROR, error)
            }
        }.apply()
        _state.value = readState()
    }

    suspend fun create(password: String): ByteArray = withContext(Dispatchers.IO) {
        // Alle ausstehenden Schreibvorgänge in die Hauptdatei übernehmen, damit die Kopie vollständig ist.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val current = settings.settings.first()
        val meta = BackupMeta(
            schemaVersion = db.openHelper.readableDatabase.version,
            createdAtEpochSeconds = System.currentTimeMillis() / 1000,
            budgetStartDay = current.budgetStartDay,
            reserveCents = current.reserveCents,
        )
        val files = mapOf(
            DB_ENTRY to context.getDatabasePath(AppDatabase.FILE_NAME).readBytes(),
            META_ENTRY to json.encodeToString(BackupMeta.serializer(), meta).toByteArray(),
        )
        BackupCrypto.encrypt(BackupCrypto.zip(files), password)
    }

    /**
     * Ersetzt alle App-Daten durch die Sicherung. Danach muss die App neu starten ([restartApp]),
     * weil die Datenbankverbindung geschlossen wird.
     */
    suspend fun restore(data: ByteArray, password: String) = withContext(Dispatchers.IO) {
        val files = BackupCrypto.unzip(BackupCrypto.decrypt(data, password))
        val database = files[DB_ENTRY] ?: throw BackupCrypto.NotABackupException()
        if (!String(database, 0, minOf(15, database.size), Charsets.US_ASCII).startsWith("SQLite format 3")) {
            throw BackupCrypto.NotABackupException()
        }
        val meta = files[META_ENTRY]?.let { runCatching { json.decodeFromString(BackupMeta.serializer(), String(it)) }.getOrNull() }
        val currentVersion = db.openHelper.readableDatabase.version
        if (meta != null && meta.schemaVersion > currentVersion) {
            error("Die Sicherung stammt aus einer neueren Version der App. Bitte die App zuerst aktualisieren.")
        }
        db.close()
        val target = context.getDatabasePath(AppDatabase.FILE_NAME)
        target.parentFile?.mkdirs()
        listOf("-wal", "-shm", "-journal").forEach { suffix -> java.io.File(target.path + suffix).delete() }
        target.writeBytes(database)
        if (meta != null) {
            val current = settings.settings.first()
            settings.update(current.copy(budgetStartDay = meta.budgetStartDay, reserveCents = meta.reserveCents))
        }
    }

    fun restartApp() {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (intent != null) context.startActivity(intent)
        Runtime.getRuntime().exit(0)
    }

    companion object {
        private const val DB_ENTRY = "finance.db"
        private const val META_ENTRY = "meta.json"
        private const val KEY_PASSWORD = "password"
        private const val KEY_IV = "password_iv"
        private const val KEY_DRIVE = "drive_enabled"
        private const val KEY_LAST = "last_drive_backup"
        private const val KEY_ERROR = "last_drive_error"
        private const val ALIAS = "backup_password_wrap"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
