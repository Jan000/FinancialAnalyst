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
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import net.jandev.financialanalyst.data.db.GroupEntity
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import net.jandev.financialanalyst.ui.common.dayMonthLabel
import net.jandev.financialanalyst.domain.Budget
import net.jandev.financialanalyst.domain.RecurringPlan
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun TransactionsScreen(onEdit: (Long) -> Unit, onAddAccount: () -> Unit, onOpenRecurring: (Long) -> Unit = {}) {
    val app = LocalApp.current
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = null)
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var pickingId by rememberSaveable { mutableStateOf<Long?>(null) }

    val accountNames = remember(accounts) { accounts.associate { it.account.id to it.account.name } }
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val groups by app.repository.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val palette = LocalFinanceColors.current.chart
    val categoryColors = remember(categories, palette) { categories.associate { it.id to palette[it.colorIndex.mod(palette.size)] } }
    val groupMarks = remember(groups, palette) { groups.associate { it.id to (it.name to palette[it.colorIndex.mod(palette.size)]) } }

    val recurring by app.repository.recurring.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = remember { LocalDate.now() }
    // Was die Dauerposten in den nächsten Wochen erwarten lassen: als Umsätze mit negativer ID, die es noch nicht gibt.
    val planned = remember(recurring, today) {
        val origin = HashMap<Long, Long>()
        var nextId = -1L
        val rows = recurring.filter { it.active && !it.dismissed }.flatMap { item ->
            Budget.plannedFlows(listOf(RecurringPlan(item.displayName, item.amountCents, item.interval, item.nextDue)), today, today.plusDays(PLANNED_DAYS))
                .map { flow ->
                    TransactionEntity(
                        id = nextId--,
                        accountId = item.accountId ?: 0,
                        bookingDate = flow.date,
                        amountCents = flow.amountCents,
                        counterparty = item.displayName,
                        categoryId = item.categoryId,
                    ).also { origin[it.id] = item.id }
                }
        }
        rows to origin
    }

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
        val filtered = remember(all, query, categoryNames, groupMarks) {
            val q = query.trim().lowercase()
            if (q.isEmpty()) {
                all
            } else {
                all.filter {
                    it.counterparty.lowercase().contains(q) ||
                        it.purpose.lowercase().contains(q) ||
                        groupMarks[it.groupId]?.first?.lowercase()?.contains(q) == true ||
                        categoryNames[it.categoryId]?.lowercase()?.contains(q) == true ||
                        Money.format(abs(it.amountCents)).contains(q)
                }
            }
        }
        val grouped = remember(filtered, planned, query) {
            val q = query.trim().lowercase()
            val expected = planned.first.filter {
                q.isEmpty() || it.counterparty.lowercase().contains(q) ||
                    categoryNames[it.categoryId]?.lowercase()?.contains(q) == true ||
                    Money.format(abs(it.amountCents)).contains(q)
            }
            // Die zwei Seiten einer Umbuchung rücken zusammen, auch wenn sie an verschiedenen Tagen gebucht sind.
            val byId = filtered.associateBy { it.id }
            val partner = HashMap<Long, Long>()
            for (tx in filtered) {
                val peer = tx.transferPeerId ?: continue
                if (peer !in byId || tx.id in partner || peer in partner) continue
                partner[tx.id] = peer
                partner[peer] = tx.id
            }
            val placed = HashSet<Long>()
            val entries = ArrayList<List<TransactionEntity>>()
            for (tx in (expected + filtered).sortedByDescending { it.bookingDate }) {
                if (!placed.add(tx.id)) continue
                val other = partner[tx.id]?.let(byId::get)
                if (other == null) {
                    entries.add(listOf(tx))
                } else {
                    placed.add(other.id)
                    // Der Abgang steht oben, der Eingang darunter.
                    entries.add(listOf(tx, other).sortedBy { it.amountCents })
                }
            }
            // Ein Paar steht am Tag seiner jüngeren Buchung.
            entries.groupBy { entry -> entry.maxOf { it.bookingDate } }.toList().sortedByDescending { it.first }
        }

        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Suchen") },
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
            if (grouped.isEmpty()) {
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
                    grouped.forEach { (date, entries) ->
                        item(key = "h${date.toEpochDay()}") {
                            // Bis heute zählt, was gebucht ist; an künftigen Tagen, was erwartet wird.
                            val counted = entries.flatMap { entry ->
                                // Bankabbuchung und Kauf über PayPal sind dieselbe Ausgabe: nur der Kauf zählt in die Tagessumme.
                                if (entry.size == 2 && (entry[0].amountCents < 0) == (entry[1].amountCents < 0)) {
                                    listOf(entry.firstOrNull { it.transferPeerId == null } ?: entry[1])
                                } else {
                                    entry
                                }
                            }
                            DayHeader(date, counted.filter { it.id > 0 || date > today }.sumOf { it.amountCents }, dimmed = date > today)
                        }
                        items(entries, key = { it.first().id }) { entry ->
                            val row: @Composable (TransactionEntity, Dp, RowLink) -> Unit = { tx, padding, link ->
                                val isPlanned = tx.id < 0
                                TransactionRow(
                                    tx = tx,
                                    subtitle = listOfNotNull(
                                        "Geplant".takeIf { isPlanned },
                                        // Im Paar kann eine Seite an einem anderen Tag gebucht sein als die Überschrift sagt.
                                        tx.bookingDate.dayMonthLabel().takeIf { tx.bookingDate != date },
                                        categoryNames[tx.categoryId] ?: "Ohne Kategorie",
                                        accountNames[tx.accountId],
                                    ).joinToString(" · "),
                                    onClick = { if (isPlanned) planned.second[tx.id]?.let(onOpenRecurring) else onEdit(tx.id) },
                                    onLongClick = if (isPlanned) null else ({ pickingId = tx.id }),
                                    horizontalPadding = padding,
                                    dimmed = isPlanned || tx.bookingDate > today,
                                    categoryColor = categoryColors[tx.categoryId] ?: MaterialTheme.colorScheme.outlineVariant,
                                    group = groupMarks[tx.groupId],
                                    link = link,
                                )
                            }
                            if (entry.size == 1) {
                                row(entry[0], 16.dp, RowLink.NONE)
                            } else {
                                Column(
                                    Modifier
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer),
                                ) {
                                    row(entry[0], 8.dp, RowLink.DOWN)
                                    row(entry[1], 8.dp, RowLink.UP)
                                }
                            }
                        }
                    }
                }
            }
        }
        val picking = all.firstOrNull { it.id == pickingId }
        if (picking != null) {
            CategoryPickerDialog(tx = picking, categories = categories, onDismiss = { pickingId = null }, onEditDetails = { onEdit(picking.id) })
        }
    }
}

/** Verbindungslinie zwischen den zwei Zeilen einer Umbuchung: vom Farbpunkt nach unten bzw. von oben zum Farbpunkt. */
enum class RowLink { NONE, DOWN, UP }

/** So weit voraus zeigt die Umsatzliste erwartete Buchungen der Dauerposten. */
private const val PLANNED_DAYS = 31L
private const val FUTURE_ALPHA = 0.45f

@Composable
private fun DayHeader(date: LocalDate, sumCents: Long, dimmed: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) FUTURE_ALPHA else 1f)
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
    onLongClick: (() -> Unit)? = null,
    /** Farbpunkt der Kategorie; die Kategorie steht zusätzlich immer als Text da. */
    categoryColor: Color? = null,
    /** Name und Farbe der Gruppe, zu der der Umsatz gehört. */
    group: Pair<String, Color>? = null,
    /** Ausgegraut: Buchung liegt in der Zukunft oder ist erst geplant. */
    dimmed: Boolean = false,
    link: RowLink = RowLink.NONE,
) {
    val linkColor = MaterialTheme.colorScheme.outline
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (dimmed) FUTURE_ALPHA else 1f)
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Kategorie ändern")
                } else {
                    Modifier
                },
            )
            .drawBehind {
                if (link == RowLink.NONE) return@drawBehind
                // Mitte des Farbpunkts; die Linie lässt um ihn herum etwas Luft.
                val x = (horizontalPadding + 5.dp).toPx()
                val gap = 9.dp.toPx()
                val from = if (link == RowLink.DOWN) size.height / 2 + gap else 0f
                val to = if (link == RowLink.DOWN) size.height else size.height / 2 - gap
                drawLine(linkColor, Offset(x, from), Offset(x, to), strokeWidth = 2.dp.toPx())
            }
            .padding(horizontal = horizontalPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (categoryColor != null) {
            Box(Modifier.padding(end = 12.dp).size(10.dp).clip(CircleShape).background(categoryColor))
        }
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
            if (group != null) {
                Row(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(group.second))
                    Text(
                        group.first,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
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
    var groupId by rememberSaveable { mutableStateOf<Long?>(null) }
    val groups by app.repository.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    var epochDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var counterparty by rememberSaveable { mutableStateOf("") }
    var purpose by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var applying by remember { mutableStateOf<TransactionEntity?>(null) }

    LaunchedEffect(transactionId) {
        if (transactionId == 0L) return@LaunchedEffect
        val tx = app.repository.transaction(transactionId) ?: return@LaunchedEffect
        loaded = tx
        if (!ready) {
            isExpense = tx.amountCents <= 0
            amount = abs(tx.amountCents).toFieldText()
            accountId = tx.accountId
            categoryId = tx.categoryId
            groupId = tx.groupId
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
            if (groups.isNotEmpty()) {
                val none = GroupEntity(id = 0, name = "Keine Gruppe")
                DropdownField(
                    label = "Gruppe",
                    options = listOf(none) + groups,
                    selected = groups.firstOrNull { it.id == groupId },
                    onSelect = { groupId = it.id.takeIf { id -> id != 0L } },
                    optionLabel = { it.name },
                    placeholder = "Keine Gruppe",
                )
            }
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
                        groupId = groupId,
                        categoryLocked = categoryId != null && (base.categoryLocked || categoryId != base.categoryId),
                    )
                    scope.launch {
                        val id = app.repository.saveTransaction(tx)
                        if (tx.categoryId != null && tx.categoryId != base.categoryId) {
                            // Gleiche Zahlungen bekommen die Kategorie erst nach einer Vorschau.
                            applying = tx.copy(id = id)
                        } else {
                            app.afterTransactionsChanged()
                            onDone()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }
        }
    }

    applying?.let { saved ->
        SimilarPaymentsDialog(tx = saved, categoryId = saved.categoryId ?: return@let, categories = categories, onDone = onDone)
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
