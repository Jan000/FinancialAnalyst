package net.jandev.financialanalyst.ui.banking

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.banking.Aspsp
import net.jandev.financialanalyst.data.banking.BankCredentials
import net.jandev.financialanalyst.data.banking.BankSync
import net.jandev.financialanalyst.data.banking.SyncReport
import net.jandev.financialanalyst.data.db.BankSessionEntity
import net.jandev.financialanalyst.domain.ConnectorType
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val countries = listOf(
    "DE" to "Deutschland", "AT" to "Österreich", "NL" to "Niederlande", "FR" to "Frankreich",
    "ES" to "Spanien", "IT" to "Italien", "BE" to "Belgien", "LU" to "Luxemburg", "IE" to "Irland",
    "FI" to "Finnland", "SE" to "Schweden", "DK" to "Dänemark", "PL" to "Polen", "LT" to "Litauen",
)
private val timeFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm", Locale.GERMAN)

private fun readPem(context: Context, uri: Uri): Pair<String, String?> {
    val text = context.contentResolver.openInputStream(uri)?.use { stream ->
        val bytes = stream.readBytes()
        if (bytes.size > 64 * 1024) error("Das ist keine Schlüsseldatei.")
        String(bytes)
    } ?: error("Die Datei konnte nicht geöffnet werden.")
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
    return text to name
}

@Composable
fun BankScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sync = app.bankSync
    val setup by sync.credentials.setup.collectAsStateWithLifecycle()
    val sessions by app.repository.bankSessions.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val callback by app.pendingBankCallback.collectAsStateWithLifecycle()
    val backgroundReport by app.lastSyncReport.collectAsStateWithLifecycle()

    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<SyncReport?>(null) }
    var pickBank by remember { mutableStateOf(false) }
    var waitingForBank by rememberSaveable { mutableStateOf(sync.credentials.pendingAuth != null) }
    var pasted by rememberSaveable { mutableStateOf("") }
    var disconnect by remember { mutableStateOf<BankSessionEntity?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        error = null
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

    fun complete(uri: Uri) = run("Konten und Umsätze werden abgerufen …") {
        report = sync.completeConnection(uri)
        waitingForBank = false
        pasted = ""
        BankSync.schedule(context.applicationContext)
    }

    LaunchedEffect(callback) {
        callback?.let {
            app.pendingBankCallback.value = null
            complete(it)
        }
    }

    Screen(title = "Bankverbindungen", onBack = onBack) { padding ->
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
            (report ?: backgroundReport)?.let { ReportCard(it) }

            if (!setup.isComplete) {
                SetupCard(
                    applicationId = setup.applicationId,
                    redirectUrl = setup.redirectUrl,
                    hasKey = setup.hasKey,
                    onError = { error = it },
                )
                return@Column
            }

            SectionCard(title = "Verbundene Banken") {
                if (sessions.isEmpty()) {
                    Text("Noch keine Bank verbunden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val now = Instant.now().epochSecond
                sessions.forEachIndexed { index, session ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    val daysLeft = (session.validUntilEpochSeconds - now) / 86400
                    val until = Instant.ofEpochSecond(session.validUntilEpochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
                    val linked = accounts.filter { it.account.sessionId == session.id }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(session.bankName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                daysLeft < 0 -> "Freigabe abgelaufen am ${until.shortLabel()}"
                                daysLeft <= 14 -> "Freigabe läuft in $daysLeft Tagen ab (${until.shortLabel()})"
                                else -> "Freigabe gültig bis ${until.shortLabel()}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (daysLeft <= 14) LocalFinanceColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        linked.forEach { item ->
                            val last = item.account.lastSync?.let {
                                Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()).format(timeFormat)
                            }
                            Text(
                                "${item.account.name} · ${last?.let { "zuletzt abgerufen $it" } ?: "noch nicht abgerufen"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                enabled = busy == null,
                                onClick = {
                                    run("Freigabe wird vorbereitet …") {
                                        val bank = Aspsp(session.bankName, session.country)
                                        val known = runCatching { sync.banks(session.country) }.getOrNull()
                                            ?.firstOrNull { it.name == session.bankName } ?: bank
                                        openBrowser(context, sync.startConnection(known))
                                        waitingForBank = true
                                    }
                                },
                            ) { Text("Freigabe erneuern") }
                            TextButton(enabled = busy == null, onClick = { disconnect = session }) { Text("Trennen") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = busy == null, onClick = { pickBank = true }) { Text("Bank verbinden") }
                    if (accounts.any { it.account.connector == ConnectorType.ENABLE_BANKING }) {
                        OutlinedButton(
                            enabled = busy == null,
                            onClick = { run("Umsätze werden abgerufen …") { report = sync.syncAll() } },
                        ) { Text("Jetzt abrufen") }
                    }
                }
                Text(
                    "Die App ruft dreimal täglich automatisch ab. Banken erlauben meist nur vier Abrufe pro Tag ohne erneute Bestätigung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (waitingForBank) {
                SectionCard(title = "Freigabe abschließen") {
                    Text(
                        "Bestätige den Zugriff im Browser bei deiner Bank. Danach springt der Browser automatisch zurück in die App. " +
                            "Falls das nicht klappt, kopiere die Adresse der letzten Seite aus dem Browser und füge sie hier ein.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it.trim() },
                        label = { Text("Adresse mit code=… einfügen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = busy == null && "code=" in pasted,
                            onClick = { complete(Uri.parse(pasted)) },
                        ) { Text("Abschließen") }
                        TextButton(onClick = {
                            sync.credentials.pendingAuth = null
                            waitingForBank = false
                        }) { Text("Abbrechen") }
                    }
                }
            }

            SectionCard(title = "Zugang zu Enable Banking") {
                LabeledRow("App-ID") { Text(setup.applicationId.take(13) + "…") }
                LabeledRow("Rücksprung-Adresse") { Text(setup.redirectUrl, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { confirmReset = true }, modifier = Modifier.align(Alignment.End)) {
                    Text("Zugang entfernen")
                }
            }
        }
    }

    if (pickBank) {
        BankPicker(
            onDismiss = { pickBank = false },
            onPick = { bank ->
                pickBank = false
                run("Freigabe wird vorbereitet …") {
                    openBrowser(context, sync.startConnection(bank))
                    waitingForBank = true
                }
            },
        )
    }
    disconnect?.let { session ->
        ConfirmDialog(
            title = "${session.bankName} trennen?",
            text = "Die Freigabe wird beendet. Konten und bereits abgerufene Umsätze bleiben in der App.",
            confirmLabel = "Trennen",
            onConfirm = {
                disconnect = null
                run("Verbindung wird getrennt …") { sync.disconnect(session) }
            },
            onDismiss = { disconnect = null },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Zugang entfernen?",
            text = "App-ID und Schlüssel werden von diesem Gerät gelöscht. Automatische Abrufe sind dann nicht mehr möglich; Konten und Umsätze bleiben erhalten.",
            confirmLabel = "Entfernen",
            onConfirm = {
                confirmReset = false
                sync.credentials.clear()
                BankSync.cancel(context.applicationContext)
            },
            onDismiss = { confirmReset = false },
        )
    }
}

private fun openBrowser(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun ReportCard(report: SyncReport) {
    SectionCard(title = "Letzter Abruf") {
        if (report.accounts > 0) {
            Text(
                when (report.newTransactions) {
                    0 -> "${report.accounts} Konten abgerufen, keine neuen Umsätze."
                    1 -> "${report.accounts} Konten abgerufen, 1 neuer Umsatz."
                    else -> "${report.accounts} Konten abgerufen, ${report.newTransactions} neue Umsätze."
                },
            )
        }
        report.errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SetupCard(applicationId: String, redirectUrl: String, hasKey: Boolean, onError: (String?) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    var appId by rememberSaveable { mutableStateOf(applicationId) }
    var redirect by rememberSaveable { mutableStateOf(redirectUrl.ifBlank { BankCredentials.DEFAULT_REDIRECT }) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val (pem, name) = readPem(context, uri)
            app.bankSync.credentials.importKey(pem)
            // Enable Banking benennt die Schlüsseldatei nach der App-ID.
            val fromName = name?.removeSuffix(".pem")?.takeIf { Regex("[0-9a-fA-F-]{36}").matches(it) }
            if (appId.isBlank() && fromName != null) appId = fromName
            app.bankSync.credentials.save(appId, redirect)
            onError(null)
        } catch (e: Exception) {
            onError(e.message ?: "Die Schlüsseldatei konnte nicht gelesen werden.")
        }
    }

    SectionCard(title = "Einmalige Einrichtung") {
        Text(
            "Der Abruf läuft über den lizenzierten Dienst Enable Banking, für eigene Konten kostenlos:",
            style = MaterialTheme.typography.bodyMedium,
        )
        listOf(
            "1. Auf enablebanking.com anmelden und im Control Panel eine Anwendung in der Umgebung „Production“ anlegen.",
            "2. Als Redirect-URL genau die Rücksprung-Adresse unten eintragen.",
            "3. Die erzeugte Schlüsseldatei (.pem) herunterladen und hier auswählen.",
            "4. Im Control Panel „Activate by linking accounts“ wählen und dort jedes Konto verknüpfen, das die App abrufen soll.",
        ).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(
            value = redirect,
            onValueChange = { redirect = it.trim() },
            label = { Text("Rücksprung-Adresse") },
            supportingText = { Text("Lehnt Enable Banking diese Adresse ab, nutze die Weiterleitungsseite aus der README") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = appId,
            onValueChange = { appId = it.trim() },
            label = { Text("App-ID") },
            supportingText = { Text("Wird aus dem Namen der Schlüsseldatei übernommen, falls leer") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(if (hasKey) "Andere Schlüsseldatei wählen" else "Schlüsseldatei (.pem) wählen")
        }
        if (hasKey) {
            Text("Schlüssel ist hinterlegt und auf dem Gerät verschlüsselt gespeichert.", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            enabled = hasKey && appId.isNotBlank() && redirect.isNotBlank(),
            onClick = { app.bankSync.credentials.save(appId, redirect) },
        ) { Text("Einrichtung speichern") }
    }
}

@Composable
private fun BankPicker(onDismiss: () -> Unit, onPick: (Aspsp) -> Unit) {
    val app = LocalApp.current
    var country by rememberSaveable { mutableStateOf("DE") }
    var query by rememberSaveable { mutableStateOf("") }
    var banks by remember { mutableStateOf<List<Aspsp>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(country) {
        banks = null
        error = null
        try {
            banks = app.bankSync.banks(country)
        } catch (e: Exception) {
            error = e.message ?: "Die Bankenliste konnte nicht geladen werden."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bank wählen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = "Land",
                    options = countries,
                    selected = countries.firstOrNull { it.first == country },
                    onSelect = { country = it.first },
                    optionLabel = { it.second },
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Suchen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val list = banks
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    list == null -> CircularProgressIndicator()
                    else -> {
                        val filtered = list.filter { it.name.contains(query.trim(), ignoreCase = true) }
                        if (filtered.isEmpty()) {
                            Text("Keine passende Bank gefunden. Für sie bleibt der Datei-Import.")
                        }
                        LazyColumn(Modifier.heightIn(max = 320.dp)) {
                            items(filtered, key = { it.name }) { bank ->
                                Text(
                                    bank.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPick(bank) }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
