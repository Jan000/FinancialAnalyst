package net.jandev.financialanalyst.data.backup

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.jandev.financialanalyst.FinanceApp
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class DriveFile(val id: String, val name: String = "", val modifiedTime: String? = null, val size: String? = null)

@Serializable
private data class DriveFileList(val files: List<DriveFile> = emptyList())

/** Ergebnis der Google-Anmeldung: entweder ein Zugriffstoken oder ein Dialog, den der Nutzer bestätigen muss. */
sealed interface DriveAccess {
    data class Granted(val token: String) : DriveAccess
    data class NeedsConsent(val pendingIntent: PendingIntent) : DriveAccess
}

/**
 * Legt die verschlüsselte Sicherung im versteckten App-Ordner von Google Drive ab (Scope `drive.appdata`).
 * Die App sieht damit ausschließlich ihre eigenen Dateien, nichts sonst im Drive des Nutzers.
 */
class DriveBackup(
    private val context: Context,
    private val backups: BackupManager,
    private val http: OkHttpClient = OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    suspend fun access(): DriveAccess = suspendCancellableCoroutine { continuation ->
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { result ->
                val pending = result.pendingIntent
                val token = result.accessToken
                when {
                    result.hasResolution() && pending != null -> continuation.resume(DriveAccess.NeedsConsent(pending))
                    token != null -> continuation.resume(DriveAccess.Granted(token))
                    else -> continuation.resumeWithException(IllegalStateException("Google hat keinen Zugriff erteilt."))
                }
            }
            .addOnFailureListener { error -> continuation.resumeWithException(describe(error)) }
    }

    /** Wertet die Rückgabe des Zustimmungsdialogs aus. */
    fun accessFromConsent(data: Intent?): DriveAccess.Granted {
        val result = try {
            Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            throw describe(e)
        }
        return DriveAccess.Granted(result.accessToken ?: error("Google hat keinen Zugriff erteilt."))
    }

    private fun describe(error: Exception): Exception {
        val code = (error as? ApiException)?.statusCode
        return IllegalStateException(
            when (code) {
                // DEVELOPER_ERROR: Paketname/SHA-1 passen zu keinem OAuth-Client im Google-Cloud-Projekt.
                10 -> "Die Google-Anmeldung ist für diese App noch nicht eingerichtet (OAuth-Client fehlt oder SHA-1 passt nicht). Siehe README."
                7 -> "Keine Verbindung zu Google."
                12501, 16 -> "Die Anmeldung wurde abgebrochen."
                else -> "Google-Anmeldung fehlgeschlagen (${code ?: error.javaClass.simpleName})."
            },
        )
    }

    private suspend fun <T> call(request: Request, read: (ByteArray) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val bytes = response.body.bytes()
            if (!response.isSuccessful) {
                error(
                    when (response.code) {
                        401 -> "Der Google-Zugriff ist abgelaufen. Bitte erneut verbinden."
                        403 -> "Google Drive hat den Zugriff abgelehnt. Ist die Drive-API im Google-Cloud-Projekt aktiviert?"
                        else -> "Google Drive antwortet mit Fehler ${response.code}."
                    },
                )
            }
            read(bytes)
        }
    }

    suspend fun latest(token: String): DriveFile? {
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '$FILE_NAME'")
            .addQueryParameter("orderBy", "modifiedTime desc")
            .addQueryParameter("fields", "files(id,name,modifiedTime,size)")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        return call(request) { json.decodeFromString(DriveFileList.serializer(), String(it)).files.firstOrNull() }
    }

    suspend fun download(token: String, fileId: String): ByteArray {
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files/$fileId?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        return call(request) { it }
    }

    /** Überschreibt die vorhandene Sicherung oder legt sie neu an. */
    suspend fun upload(token: String, data: ByteArray) {
        val binary = "application/octet-stream".toMediaType()
        val existing = latest(token)
        val request = if (existing != null) {
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/${existing.id}?uploadType=media")
                .header("Authorization", "Bearer $token")
                .patch(data.toRequestBody(binary))
                .build()
        } else {
            val metadata = """{"name":"$FILE_NAME","parents":["appDataFolder"]}"""
            val body = MultipartBody.Builder().setType("multipart/related".toMediaType())
                .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addPart(data.toRequestBody(binary))
                .build()
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()
        }
        call(request) { }
    }

    /** Erstellt eine Sicherung mit dem gespeicherten Passwort und lädt sie hoch. */
    suspend fun backupNow(token: String) {
        val password = backups.storedPassword() ?: error("Bitte zuerst ein Sicherungspasswort festlegen.")
        upload(token, backups.create(password))
        backups.recordDriveResult(null)
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
        const val FILE_NAME = "finanzanalyst.fabackup"
        private const val WORK_NAME = "drive-backup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DriveBackupWorker>(24, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

class DriveBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FinanceApp).container
        if (!container.backups.state.value.driveEnabled) return Result.success()
        return try {
            when (val access = container.driveBackup.access()) {
                is DriveAccess.Granted -> container.driveBackup.backupNow(access.token)
                is DriveAccess.NeedsConsent -> container.backups.recordDriveResult("Google verlangt eine erneute Zustimmung. Bitte die Sicherung in der App öffnen.")
            }
            Result.success()
        } catch (e: Exception) {
            container.backups.recordDriveResult(e.message ?: "Sicherung fehlgeschlagen")
            Result.success()
        }
    }
}
