package net.jandev.financialanalyst.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.TxStatus
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.DateField
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.longLabel
import net.jandev.financialanalyst.ui.common.toFieldText
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun TransactionsScreen(onEdit: (Long) -> Unit, onAddAccount: () -> Unit) {
    val app = LocalApp.current
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = null)
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by rememberSaveable { mutableStateOf("") }

    val accountNames = remember(accounts) { accounts.associate { it.account.id to it.account.name } }
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }

    Screen(
        title = "Umsätze",
        floatingActionButton = {
            if (accounts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { onEdit(0) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Umsatz") },
                )
            }
        },
    ) { padding ->
        val all = transactions ?: return@Screen
        if (accounts.isEmpty()) {
            EmptyState(
                title = "Noch keine Konten",
                hint = "Umsätze gehören immer zu einem Konto. Lege zuerst eines an.",
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = onAddAccount) { Text("Konto anlegen") }
            }
            return@Screen
        }
        val filtered = remember(all, query, categoryNames) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) {
                all
            } else {
                all.filter {
                    it.counterparty.lowercase().contains(q) ||
                        it.purpose.lowercase().contains(q) ||
                        categoryNames[it.categoryId]?.lowercase()?.contains(q) == true ||
                        Money.format(abs(it.amountCents)).contains(q)
                }
            }
        }
        val grouped = remember(filtered) { filtered.groupBy { it.bookingDate }.toList() }

        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Umsätze durchsuchen") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Suche leeren")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (filtered.isEmpty()) {
                EmptyState(
                    title = if (all.isEmpty()) "Noch keine Umsätze" else "Keine Treffer",
                    hint = if (all.isEmpty()) {
                        "Erfasse einen Umsatz über die Schaltfläche unten rechts."
                    } else {
                        "Für „$query“ wurde nichts gefunden."
                    },
                )
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    grouped.forEach { (date, items) ->
                        item(key = "h${date.toEpochDay()}") {
                            DayHeader(date, items.sumOf { it.amountCents })
                        }
                        items(items, key = { it.id }) { tx ->
                            TransactionRow(
                                tx = tx,
                                subtitle = listOfNotNull(
                                    categoryNames[tx.categoryId] ?: "Ohne Kategorie",
                                    accountNames[tx.accountId],
                                ).joinToString(" · "),
                                onClick = { onEdit(tx.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(date: LocalDate, sumCents: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            date.longLabel(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        AmountText(sumCents, style = MaterialTheme.typography.labelLarge, colored = false)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun TransactionRow(
    tx: TransactionEntity,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                tx.counterparty.ifBlank { tx.purpose.ifBlank { "Ohne Bezeichnung" } },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (tx.status == TxStatus.PENDING) "Vorläufig · $subtitle" else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AmountText(tx.amountCents)
    }
}

@Composable
fun TransactionEditScreen(transactionId: Long, onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    var loaded by remember { mutableStateOf<TransactionEntity?>(null) }
    var ready by rememberSaveable { mutableStateOf(transactionId == 0L) }
    var isExpense by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    var accountId by rememberSaveable { mutableStateOf<Long?>(null) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var epochDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var counterparty by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(transactionId) {
        if (transactionId == 0L) return@LaunchedEffect
        val tx = app.repository.transaction(transactionId) ?: return@LaunchedEffect
        loaded = tx
        if (!ready) {
            isExpense = tx.amountCents <= 0
            amount = abs(tx.amountCents).toFieldText()
            accountId = tx.accountId
            categoryId = tx.categoryId
            epochDay = tx.bookingDate.toEpochDay()
            counterparty = tx.counterparty
            purpose = tx.purpose
            ready = true
        }
    }
    LaunchedEffect(accounts) {
        if (accountId == null && accounts.isNotEmpty()) accountId = accounts.first().account.id
    }

    val amountCents = Money.parse(amount)?.let(::abs)
    val valid = amountCents != null && amountCents > 0 && accountId != null
    val selectableCategories = remember(categories, isExpense) {
        categories.filter {
            it.kind == CategoryKind.TRANSFER || it.kind == (if (isExpense) CategoryKind.EXPENSE else CategoryKind.INCOME)
        }
    }

    Screen(
        title = if (transactionId == 0L) "Neuer Umsatz" else "Umsatz bearbeiten",
        onBack = onDone,
        actions = {
            if (transactionId != 0L) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Umsatz löschen")
                }
            }
        },
    ) { padding ->
        if (!ready) return@Screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = isExpense,
                    onClick = {
                        if (!isExpense) categoryId = null
                        isExpense = true
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text("Ausgabe") }
                SegmentedButton(
                    selected = !isExpense,
                    onClick = {
                        if (isExpense) categoryId = null
                        isExpense = false
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text("Einnahme") }
            }
            MoneyField(value = amount, onValueChange = { amount = it }, label = "Betrag")
            DropdownField(
                label = "Konto",
                options = accounts.map { it.account },
                selected = accounts.firstOrNull { it.account.id == accountId }?.account,
                onSelect = { accountId = it.id },
                optionLabel = { it.name },
            )
            DateField(
                date = LocalDate.ofEpochDay(epochDay),
                onDateChange = { epochDay = it.toEpochDay() },
                label = "Datum",
            )
            OutlinedTextField(
                value = counterparty,
                onValueChange = { counterparty = it },
                label = { Text(if (isExpense) "Empfänger" else "Absender") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = purpose,
                onValueChange = { purpose = it },
                label = { Text("Verwendungszweck (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField<CategoryEntity>(
                label = "Kategorie",
                options = selectableCategories,
                selected = categories.firstOrNull { it.id == categoryId },
                onSelect = { categoryId = it.id },
                optionLabel = { it.name },
                placeholder = "Ohne Kategorie",
            )
            Button(
                enabled = valid,
                onClick = {
                    val cents = amountCents ?: return@Button
                    val base = loaded ?: TransactionEntity(accountId = 0, bookingDate = LocalDate.now(), amountCents = 0)
                    val tx = base.copy(
                        accountId = accountId ?: return@Button,
                        bookingDate = LocalDate.ofEpochDay(epochDay),
                        amountCents = if (isExpense) -cents else cents,
                        counterparty = counterparty.trim(),
                        purpose = purpose.trim(),
                        categoryId = categoryId,
                        categoryLocked = categoryId != null && (base.categoryLocked || categoryId != base.categoryId),
                    )
                    scope.launch {
                        app.repository.saveTransaction(tx)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Umsatz löschen?",
            text = "Der Umsatz wird endgültig gelöscht. Der Kontostand ändert sich entsprechend.",
            confirmLabel = "Löschen",
            onConfirm = {
                confirmDelete = false
                val tx = loaded ?: return@ConfirmDialog
                scope.launch {
                    app.repository.deleteTransaction(tx)
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
