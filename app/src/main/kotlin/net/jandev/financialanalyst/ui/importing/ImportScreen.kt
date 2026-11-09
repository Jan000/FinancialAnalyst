package net.jandev.financialanalyst.ui.importing

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.jandev.financialanalyst.data.ImportSummary
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.TxSource
import net.jandev.financialanalyst.domain.csv.ColumnMapping
import net.jandev.financialanalyst.domain.csv.ConversionResult
import net.jandev.financialanalyst.domain.csv.CsvAnalysis
import net.jandev.financialanalyst.domain.csv.CsvImporter
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.common.toFieldText
import net.jandev.financialanalyst.ui.transactions.TransactionRow
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Liest eine Textdatei; ältere Bankexporte sind oft Windows-1252 statt UTF-8. */
private fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Die Datei konnte nicht geöffnet werden.")
    if (bytes.size > 20 * 1024 * 1024) error("Die Datei ist zu groß.")
    return try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("windows-1252"))
    }
}

@Composable
fun ImportScreen(onBack: () -> Unit, onAddAccount: () -> Unit, onFinished: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val pendingUri by app.pendingImport.collectAsStateWithLifecycle()

    var analysis by remember { mutableStateOf<CsvAnalysis?>(null) }
    var mapping by remember { mutableStateOf<ColumnMapping?>(null) }
    var editMapping by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf<Long?>(null) }
    var balance by remember { mutableStateOf("") }
    var conversion by remember { mutableStateOf<ConversionResult?>(null) }
    var preview by remember { mutableStateOf<ImportSummary?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun load(uri: Uri) {
        scope.launch {
            error = null
            analysis = null
            try {
                val result = withContext(Dispatchers.IO) { CsvImporter.analyze(readText(context, uri)) }
                if (result.header.isEmpty() || result.rows.isEmpty()) {
                    error = "In der Datei wurden keine Tabellenzeilen gefunden. Bitte eine CSV-Datei mit Umsätzen wählen."
                } else {
                    analysis = result
                    mapping = result.mapping
                    editMapping = result.mapping == null
                    balance = result.statedBalanceCents?.toFieldText().orEmpty()
                }
            } catch (e: Exception) {
                error = e.message ?: "Die Datei konnte nicht gelesen werden."
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }
    LaunchedEffect(pendingUri) {
        pendingUri?.let {
            app.pendingImport.value = null
            load(it)
        }
    }
    LaunchedEffect(accounts) {
        if (accountId == null && accounts.size == 1) accountId = accounts.first().account.id
    }
    LaunchedEffect(analysis, mapping, accountId) {
        val a = analysis
        val m = mapping
        conversion = if (a != null && m != null && m.isUsable) {
            withContext(Dispatchers.Default) { CsvImporter.convert(a, m) }
        } else {
            null
        }
        val id = accountId
        val c = conversion
        preview = if (id != null && c != null) app.repository.previewImport(id, c.transactions, TxSource.IMPORT) else null
    }

    Screen(title = "Umsätze importieren", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val current = analysis
            if (current == null) {
                Text(
                    "Lade die Umsätze im Online-Banking oder in der App deiner Bank als CSV-Datei herunter und wähle sie hier aus. " +
                        "DKB, N26, American Express und PayPal werden automatisch erkannt, bei anderen Dateien ordnest du die Spalten kurz zu.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Du kannst dieselbe Datei gefahrlos mehrfach importieren: bereits vorhandene Umsätze werden übersprungen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Datei auswählen")
                }
                return@Column
            }

            SectionCard(title = "Datei") {
                LabeledRow("Erkanntes Format") { Text(current.format.label) }
                LabeledRow("Zeilen") { Text(current.rows.size.toString()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Andere Datei") }
                    if (current.mapping != null) {
                        TextButton(onClick = { editMapping = !editMapping }) {
                            Text(if (editMapping) "Spalten ausblenden" else "Spalten anpassen")
                        }
                    }
                }
            }

            if (editMapping) {
                MappingEditor(header = current.header, mapping = mapping, onChange = { mapping = it })
            }

            if (accounts.isEmpty()) {
                SectionCard(title = "Zielkonto") {
                    Text("Es gibt noch kein Konto, in das importiert werden kann.")
                    OutlinedButton(onClick = onAddAccount) { Text("Konto anlegen") }
                }
            } else {
                DropdownField(
                    label = "Zielkonto",
                    options = accounts.map { it.account },
                    selected = accounts.firstOrNull { it.account.id == accountId }?.account,
                    onSelect = { accountId = it.id },
                    optionLabel = { it.name },
                    placeholder = "Konto wählen",
                )
            }

            val converted = conversion
            if (converted == null) {
                Text(
                    "Bitte ordne mindestens die Spalten für Datum und Betrag zu.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            SectionCard(title = "Vorschau") {
                val summary = preview
                if (summary != null) {
                    LabeledRow("Neue Umsätze") { Text(summary.inserted.toString()) }
                    if (summary.confirmed > 0) {
                        LabeledRow("Bestätigen vorhandene Einträge") { Text(summary.confirmed.toString()) }
                    }
                    LabeledRow("Bereits vorhanden") { Text(summary.duplicates.toString()) }
                } else {
                    LabeledRow("Lesbare Umsätze") { Text(converted.transactions.size.toString()) }
                }
                if (converted.unreadable > 0) {
                    LabeledRow("Nicht lesbare Zeilen") {
                        Text(converted.unreadable.toString(), color = MaterialTheme.colorScheme.error)
                    }
                }
                converted.transactions.take(4).forEach { tx ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    TransactionRow(
                        tx = TransactionEntity(
                            accountId = 0,
                            bookingDate = tx.date,
                            amountCents = tx.amountCents,
                            counterparty = tx.counterparty,
                            purpose = tx.purpose,
                        ),
                        subtitle = listOf(tx.date.shortLabel(), tx.purpose).filter { it.isNotBlank() }.joinToString(" · "),
                        horizontalPadding = 0.dp,
                    )
                }
            }

            MoneyField(
                value = balance,
                onValueChange = { balance = it },
                label = "Kontostand nach dem Import (optional)",
                allowNegative = true,
                supportingText = "Leer lassen, um den Kontostand aus den Umsätzen fortzuschreiben",
            )

            val balanceCents = if (balance.isBlank()) null else Money.parse(balance)
            val count = preview?.let { it.inserted + it.confirmed } ?: converted.transactions.size
            Button(
                enabled = !busy && accountId != null && count > 0 && (balance.isBlank() || balanceCents != null),
                onClick = {
                    val id = accountId ?: return@Button
                    busy = true
                    scope.launch {
                        app.repository.importTransactions(id, converted.transactions, TxSource.IMPORT, balanceAfterCents = balanceCents)
                        app.afterTransactionsChanged()
                        busy = false
                        onFinished()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        accountId == null -> "Zielkonto wählen"
                        count == 0 -> "Nichts Neues zu importieren"
                        count == 1 -> "1 Umsatz importieren"
                        else -> "$count Umsätze importieren"
                    },
                )
            }
        }
    }
}

@Composable
private fun MappingEditor(header: List<String>, mapping: ColumnMapping?, onChange: (ColumnMapping) -> Unit) {
    val current = mapping ?: ColumnMapping(date = -1)
    val columns = header.indices.toList()
    fun label(index: Int?): String = index?.let { header.getOrNull(it)?.ifBlank { "Spalte ${it + 1}" } } ?: "—"
    val optional: List<Int?> = listOf<Int?>(null) + columns

    SectionCard(title = "Spalten zuordnen") {
        DropdownField(
            label = "Datum",
            options = columns,
            selected = current.date.takeIf { it >= 0 },
            onSelect = { onChange(current.copy(date = it)) },
            optionLabel = { label(it) },
            placeholder = "Spalte wählen",
        )
        DropdownField(
            label = "Betrag",
            options = optional,
            selected = current.amount,
            onSelect = { onChange(current.copy(amount = it, credit = if (it != null) null else current.credit, debit = if (it != null) null else current.debit)) },
            optionLabel = { label(it) },
            placeholder = if (current.credit != null || current.debit != null) "Getrennte Spalten für Ein- und Ausgang" else "Spalte wählen",
        )
        DropdownField(
            label = "Empfänger / Absender",
            options = optional,
            selected = current.counterparty.firstOrNull(),
            onSelect = { onChange(current.copy(counterparty = listOfNotNull(it))) },
            optionLabel = { label(it) },
            placeholder = "—",
        )
        DropdownField(
            label = "Verwendungszweck",
            options = optional,
            selected = current.purpose.firstOrNull(),
            onSelect = { onChange(current.copy(purpose = listOfNotNull(it))) },
            optionLabel = { label(it) },
            placeholder = "—",
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Vorzeichen umkehren")
                Text(
                    "Für Kreditkarten-Dateien, in denen Ausgaben positiv stehen",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = current.invertSign, onCheckedChange = { onChange(current.copy(invertSign = it)) })
        }
    }
}
