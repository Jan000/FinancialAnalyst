package net.jandev.financialanalyst.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jandev.financialanalyst.data.backup.DriveAccess
import net.jandev.financialanalyst.data.backup.DriveBackup
import net.jandev.financialanalyst.domain.BackupCrypto
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN)

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.backups.state.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    var changePassword by rememberSaveable { mutableStateOf(false) }
    /** Sicherung, die auf die Passworteingabe wartet, bevor sie alle Daten ersetzt. */
    var pendingRestore by remember { mutableStateOf<ByteArray?>(null) }
    /** Was nach erteilter Google-Zustimmung weiterlaufen soll. */
    var afterConsent by remember { mutableStateOf<(suspend (String) -> Unit)?>(null) }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
        message = null
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: "Unbekannter Fehler"
            } finally {
                busy = null
            }
        }
    }

    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val next = afterConsent
        afterConsent = null
        if (next == null) return@rememberLauncherForActivityResult
        run("Verbinde mit Google Drive …") {
            next(app.driveBackup.accessFromConsent(result.data).token)
        }
    }

    /** Holt ein Zugriffstoken; fragt bei Bedarf einmal die Zustimmung des Nutzers ab. */
    fun withDrive(label: String, action: suspend (String) -> Unit) = run(label) {
        when (val access = app.driveBackup.access()) {
            is DriveAccess.Granted -> action(access.token)
            is DriveAccess.NeedsConsent -> {
                afterConsent = action
                consent.launch(IntentSenderRequest.Builder(access.pendingIntent.intentSender).build())
            }
        }
    }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("Sicherung wird erstellt …") {
            val stored = app.backups.storedPassword() ?: error("Bitte zuerst ein Sicherungspasswort festlegen.")
            val data = app.backups.create(stored)
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(data) } ?: error("Die Datei konnte nicht geschrieben werden.")
            }
            message = "Die Sicherung wurde gespeichert."
        }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run("Sicherung wird gelesen …") {
            pendingRestore = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Die Datei konnte nicht geöffnet werden.")
            }
        }
    }

    Screen(title = "Sicherung", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            busy?.let { label ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.heightIn(max = 24.dp))
                    Text(label)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            SectionCard(title = "Sicherungspasswort") {
                if (state.hasPassword && !changePassword) {
                    Text("Ein Sicherungspasswort ist festgelegt. Alle Sicherungen werden damit verschlüsselt.")
                    Text(
                        "Merke es dir gut: Ohne das Passwort lässt sich eine Sicherung nicht wiederherstellen – auch nicht von dir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { changePassword = true }) { Text("Passwort ändern") }
                } else {
                    Text(
                        "Sicherungen enthalten alle Konten und Umsätze. Sie werden mit einem Passwort verschlüsselt, das nur du kennst; " +
                            "ohne das Passwort kann niemand die Sicherung lesen, auch Google nicht.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Neues Passwort (mindestens ${BackupCrypto.MIN_PASSWORD_LENGTH} Zeichen)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = repeat,
                        onValueChange = { repeat = it },
                        label = { Text("Passwort wiederholen") },
                        singleLine = true,
                        isError = repeat.isNotEmpty() && repeat != password,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        enabled = password.length >= BackupCrypto.MIN_PASSWORD_LENGTH && password == repeat,
                        onClick = {
                            app.backups.setPassword(password)
                            password = ""
                            repeat = ""
                            changePassword = false
                            message = "Das Sicherungspasswort wurde gespeichert."
                        },
                    ) { Text("Passwort festlegen") }
                }
            }

            SectionCard(title = "Google Drive") {
                Text(
                    "Die verschlüsselte Sicherung liegt in einem versteckten App-Ordner deines Google Drive. " +
                        "Die App kann dort nur ihre eigene Datei sehen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Täglich automatisch sichern")
                        Text("Nur im WLAN", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = state.driveEnabled,
                        enabled = state.hasPassword && busy == null,
                        onCheckedChange = { enable ->
                            if (enable) {
                                // Einschalten heißt: Zugriff einholen und sofort die erste Sicherung ablegen.
                                withDrive("Erste Sicherung wird hochgeladen …") { token ->
                                    app.driveBackup.backupNow(token)
                                    app.backups.setDriveEnabled(true)
                                    DriveBackup.schedule(context.applicationContext)
                                    message = "Google Drive ist verbunden, die erste Sicherung liegt dort."
                                }
                            } else {
                                app.backups.setDriveEnabled(false)
                                DriveBackup.cancel(context.applicationContext)
                            }
                        },
                    )
                }
                state.lastDriveBackupEpochSeconds?.let { seconds ->
                    Text(
                        "Letzte Sicherung: ${Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault()).format(timeFormat)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.lastDriveError?.let {
                    Text("Letzter Versuch fehlgeschlagen: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = state.hasPassword && busy == null,
                        onClick = {
                            withDrive("Sicherung wird hochgeladen …") { token ->
                                app.driveBackup.backupNow(token)
                                message = "Die Sicherung wurde in Google Drive abgelegt."
                            }
                        },
                    ) { Text("Jetzt sichern") }
                    OutlinedButton(
                        enabled = busy == null,
                        onClick = {
                            withDrive("Sicherung wird gesucht …") { token ->
                                val file = app.driveBackup.latest(token) ?: error("In Google Drive liegt noch keine Sicherung dieser App.")
                                pendingRestore = app.driveBackup.download(token, file.id)
                            }
                        },
                    ) { Text("Wiederherstellen") }
                }
                if (!state.hasPassword) {
                    Text("Lege zuerst ein Sicherungspasswort fest.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard(title = "Als Datei") {
                Text(
                    "Speichere die verschlüsselte Sicherung an einem Ort deiner Wahl, etwa auf einem USB-Stick oder in einer anderen Cloud.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = state.hasPassword && busy == null,
                        onClick = { saveFile.launch("finanzanalyst-${LocalDate.now()}.${BackupCrypto.FILE_EXTENSION}") },
                    ) { Text("Datei speichern") }
                    OutlinedButton(enabled = busy == null, onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Aus Datei laden") }
                }
            }
            Text(
                "Nicht enthalten sind die Zugangsdaten für den Bankabruf. Auf einem neuen Gerät richtest du die Bankverbindung einmal neu ein.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    pendingRestore?.let { data ->
        var restorePassword by remember { mutableStateOf(app.backups.storedPassword().orEmpty()) }
        var restoreError by remember { mutableStateOf<String?>(null) }
        var working by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!working) pendingRestore = null },
            title = { Text("Sicherung wiederherstellen?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Alle Konten, Umsätze und Einstellungen in der App werden durch den Stand der Sicherung ersetzt. Das lässt sich nicht rückgängig machen.")
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text("Sicherungspasswort") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    restoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && restorePassword.isNotEmpty(),
                    onClick = {
                        working = true
                        restoreError = null
                        scope.launch {
                            try {
                                app.backups.restore(data, restorePassword)
                                app.backups.setPassword(restorePassword)
                                app.backups.restartApp()
                            } catch (e: Exception) {
                                restoreError = e.message ?: "Die Sicherung konnte nicht geladen werden."
                                working = false
                            }
                        }
                    },
                ) { Text("Ersetzen und neu starten") }
            },
            dismissButton = { TextButton(enabled = !working, onClick = { pendingRestore = null }) { Text("Abbrechen") } },
        )
    }
}
