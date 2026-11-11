package net.jandev.financialanalyst.ui.recurring

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.db.RecurringEntity
import net.jandev.financialanalyst.data.db.GroupEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.data.db.SplitEntity
import net.jandev.financialanalyst.domain.Budget
import net.jandev.financialanalyst.domain.RecurringPlan
import androidx.compose.ui.draw.alpha
import java.time.format.DateTimeFormatter
import java.util.Locale
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.RecurrenceInterval
import net.jandev.financialanalyst.domain.RecurringDetector
import net.jandev.financialanalyst.data.toTx
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.DateField
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.dayMonthLabel
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.common.toFieldText
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate
import kotlin.math.abs

/** Reihenfolge der Posten innerhalb eines Abschnitts. */
private enum class RecurringSort(val label: String) {
    DUE("Fälligkeit"),
    AMOUNT("Kosten"),
    CATEGORY("Kategorie"),
}

/** Abschnitte in dieser Reihenfolge; die häufigen Rhythmen zuerst. */
private val sectionOrder = listOf(
    RecurrenceInterval.MONTHLY to "Monatliche",
    RecurrenceInterval.BIMONTHLY to "Zweimonatliche",
    RecurrenceInterval.QUARTERLY to "Vierteljährliche",
    RecurrenceInterval.FOUR_MONTHLY to "Viermonatliche",
    RecurrenceInterval.FIVE_MONTHLY to "Fünfmonatliche",
    RecurrenceInterval.HALF_YEARLY to "Halbjährliche",
    RecurrenceInterval.YEARLY to "Jährliche",
    RecurrenceInterval.WEEKLY to "Wöchentliche",
    RecurrenceInterval.BIWEEKLY to "14-tägige",
)

private fun RecurrenceInterval.perLabel() = when (this) {
    RecurrenceInterval.WEEKLY -> "pro Woche"
    RecurrenceInterval.BIWEEKLY -> "alle 14 Tage"
    RecurrenceInterval.MONTHLY -> "pro Monat"
    RecurrenceInterval.BIMONTHLY -> "alle 2 Monate"
    RecurrenceInterval.QUARTERLY -> "pro Quartal"
    RecurrenceInterval.FOUR_MONTHLY -> "alle 4 Monate"
    RecurrenceInterval.FIVE_MONTHLY -> "alle 5 Monate"
    RecurrenceInterval.HALF_YEARLY -> "pro Halbjahr"
    RecurrenceInterval.YEARLY -> "pro Jahr"
}

/** Durchschnitt pro Monat, aus dem Jahresbetrag gerechnet, damit beim Aufsummieren keine Cent verloren gehen. */
private fun RecurringEntity.yearlyCents() = interval.yearlyCents(abs(amountCents))

@Composable
fun RecurringScreen(onEdit: (Long) -> Unit) {
    val app = LocalApp.current
    val items by app.repository.recurring.collectAsStateWithLifecycle(initialValue = null)
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    var sort by rememberSaveable { mutableStateOf(RecurringSort.DUE) }
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val splits by app.repository.splits.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = LocalDate.now()
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }

    Screen(
        title = "Fixkosten & Abos",
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(0) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Dauerposten") },
            )
        },
    ) { padding ->
        val all = items ?: return@Screen
        val visible = all.filter { !it.dismissed }
        if (visible.isEmpty()) {
            EmptyState(
                title = "Noch keine Dauerposten",
                hint = "Regelmäßige Zahlungen wie Miete, Abos oder Gehalt werden automatisch erkannt, sobald sie mindestens dreimal in den Umsätzen stehen. Du kannst sie auch von Hand anlegen.",
                modifier = Modifier.padding(padding),
            )
            return@Screen
        }
        fun ordered(list: List<RecurringEntity>) = when (sort) {
            RecurringSort.DUE -> list.sortedBy { it.nextDue }
            RecurringSort.AMOUNT -> list.sortedByDescending { it.yearlyCents() }
            RecurringSort.CATEGORY -> list.sortedWith(
                compareBy<RecurringEntity>({ categoryNames[it.categoryId] == null }, { categoryNames[it.categoryId]?.lowercase().orEmpty() })
                    .thenByDescending { it.yearlyCents() },
            )
        }
        val active = visible.filter { it.active }
        val expenses = active.filter { it.amountCents < 0 }
        val incomes = active.filter { it.amountCents > 0 }
        val paused = ordered(visible.filter { !it.active })
        val yearlyOut = expenses.sumOf { it.yearlyCents() }
        val yearlyIn = incomes.sumOf { it.yearlyCents() }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "sum") {
                SectionCard(title = "Fixkosten gesamt") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TotalFigure("pro Monat", yearlyOut / 12, Modifier.weight(1f))
                        TotalFigure("pro Quartal", yearlyOut / 4, Modifier.weight(1f))
                        TotalFigure("pro Jahr", yearlyOut, Modifier.weight(1f))
                    }
                    if (incomes.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        LabeledRow("Feste Einnahmen pro Monat") { AmountText(yearlyIn / 12, withSign = false, colored = false) }
                        LabeledRow("Bleibt nach Fixkosten pro Monat") { AmountText((yearlyIn - yearlyOut) / 12, negativeIsAlarming = true) }
                    }
                    Text(
                        "Seltenere Zahlungen sind als Durchschnitt auf Monat, Quartal und Jahr umgerechnet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item(key = "month") {
                ThisMonthCard(expenses, transactions, splits, today, onEdit)
            }
            item(key = "sort") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sortieren", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    RecurringSort.entries.forEach { option ->
                        FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) })
                    }
                }
            }
            for ((kind, list) in listOf("Ausgaben" to expenses, "Einnahmen" to incomes)) {
                for ((interval, adjective) in sectionOrder) {
                    val section = ordered(list.filter { it.interval == interval })
                    if (section.isEmpty()) continue
                    item(key = "$kind-$interval") {
                        RecurringGroup(
                            title = "$adjective $kind",
                            sums = listOfNotNull(
                                "${Money.format(section.sumOf { abs(it.amountCents) })} ${interval.perLabel()}"
                                    .takeIf { interval != RecurrenceInterval.MONTHLY },
                                "${Money.format(section.sumOf { it.yearlyCents() } / 12)} pro Monat",
                            ).joinToString(" · "),
                            items = section,
                            today = today,
                            categoryNames = categoryNames,
                            onEdit = onEdit,
                        )
                    }
                }
            }
            if (paused.isNotEmpty()) {
                item(key = "paused") {
                    RecurringGroup(
                        title = "Pausiert oder beendet",
                        sums = null,
                        items = paused,
                        today = today,
                        categoryNames = categoryNames,
                        onEdit = onEdit,
                        hint = "Hier landet ein automatisch erkannter Posten, wenn seine erwartete Buchung mehr als sechs Wochen ausbleibt. " +
                            "Kommt wieder eine, wird er von selbst aktiv. Stimmt die Einschätzung nicht, schalte ihn im Posten wieder auf „Aktiv“.",
                    )
                }
            }
        }
    }
}

/** Eine Zahlung im laufenden Monat: schon gebucht oder noch erwartet. */
private data class MonthEntry(val item: RecurringEntity, val date: LocalDate, val cents: Long, val booked: Boolean)

/** Was im laufenden Monat an Fixkosten anfällt – gerade auch seltene Zahlungen, die diesmal dran sind. */
@Composable
private fun ThisMonthCard(
    expenses: List<RecurringEntity>,
    transactions: List<TransactionEntity>,
    splits: List<SplitEntity>,
    today: LocalDate,
    onEdit: (Long) -> Unit,
) {
    val entries = remember(expenses, transactions, splits, today) {
        val start = today.withDayOfMonth(1)
        val end = today.withDayOfMonth(today.lengthOfMonth())
        val byKey = expenses.filter { it.matchKey.isNotBlank() }.associateBy { it.matchKey }
        val booked = transactions
            .filter { it.bookingDate in start..today && it.transferPeerId == null && it.amountCents < 0 }
            .mapNotNull { tx -> byKey[RecurringDetector.matchKey(tx.toTx())]?.let { MonthEntry(it, tx.bookingDate, tx.amountCents, booked = true) } } +
            // Spar-Abos: die schon in eine Abrechnung eingetragenen Lieferungen dieses Monats.
            expenses.filter { it.isPosition }.flatMap { item ->
                splits.filter { it.recurringId == item.id && it.date in start..end }.map { MonthEntry(item, it.date, it.amountCents, booked = true) }
            }
        val due = expenses.flatMap { item ->
            Budget.plannedFlows(listOf(RecurringPlan(item.displayName, item.amountCents, item.interval, item.nextDue)), today, end)
                .map { MonthEntry(item, it.date, it.amountCents, booked = false) }
        }
        (booked + due).sortedBy { it.date }
    }
    val monthName = today.format(DateTimeFormatter.ofPattern("MMMM", Locale.GERMAN))
    SectionCard(title = "Fixkosten im $monthName") {
        if (entries.isEmpty()) {
            Text("In diesem Monat ist nichts fällig.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        val bookedSum = entries.filter { it.booked }.sumOf { -it.cents }
        val dueSum = entries.filter { !it.booked }.sumOf { -it.cents }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TotalFigure("schon gebucht", bookedSum, Modifier.weight(1f))
            TotalFigure("noch fällig", dueSum, Modifier.weight(1f))
            TotalFigure("ganzer Monat", bookedSum + dueSum, Modifier.weight(1f))
        }
        val rare = entries.filter { it.item.interval != RecurrenceInterval.MONTHLY && it.item.interval.months > 1 }
        if (rare.isNotEmpty()) {
            Text(
                "Diesen Monat zusätzlich dran: " + rare.joinToString { "${it.item.displayName} (${it.item.interval.label})" },
                style = MaterialTheme.typography.bodySmall,
                color = LocalFinanceColors.current.warning,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        entries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onEdit(entry.item.id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(entry.item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            when {
                                entry.item.isPosition && entry.booked -> "geliefert am ${entry.date.dayMonthLabel()}"
                                entry.item.isPosition -> "Lieferung am ${entry.date.dayMonthLabel()}"
                                entry.booked -> "gebucht am ${entry.date.dayMonthLabel()}"
                                else -> "fällig am ${entry.date.dayMonthLabel()}"
                            },
                            "über Kartenabrechnung".takeIf { entry.item.isPosition },
                            entry.item.interval.label.takeIf { entry.item.interval != RecurrenceInterval.MONTHLY },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.item.interval.months > 1) LocalFinanceColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(entry.cents, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(if (entry.booked) 0.6f else 1f))
            }
        }
    }
}

@Composable
private fun TotalFigure(label: String, cents: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(cents, style = MaterialTheme.typography.titleMedium, withSign = false, colored = false)
    }
}

@Composable
private fun RecurringGroup(
    title: String,
    sums: String?,
    items: List<RecurringEntity>,
    today: LocalDate,
    categoryNames: Map<Long, String>,
    onEdit: (Long) -> Unit,
    hint: String? = null,
) {
    SectionCard {
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sums != null) Text(sums, style = MaterialTheme.typography.titleMedium)
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RecurringRow(item, today, categoryName = categoryNames[item.categoryId] ?: "Ohne Kategorie", onClick = { onEdit(item.id) })
        }
    }
}

@Composable
fun RecurringRow(item: RecurringEntity, today: LocalDate, categoryName: String? = null, onClick: (() -> Unit)? = null) {
    val days = item.nextDue.toEpochDay() - today.toEpochDay()
    val due = when {
        !item.active && item.detected -> "Buchung vom ${item.nextDue.shortLabel()} blieb aus"
        !item.active -> "pausiert"
        days < 0 -> "überfällig seit ${item.nextDue.dayMonthLabel()}"
        days == 0L -> "heute"
        days == 1L -> "morgen"
        days <= 14 -> "in $days Tagen"
        else -> "am ${item.nextDue.shortLabel()}"
    }
    val previous = item.previousAmountCents
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(item.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        categoryName,
                        if (categoryName == null) item.interval.label else null,
                        "in Sammelabrechnung".takeIf { item.isPosition },
                        due,
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                AmountText(item.amountCents)
                if (item.interval != RecurrenceInterval.MONTHLY) {
                    // Auch seltenere Zahlungen bekommen ihren Monatspreis.
                    Text(
                        "Ø ${Money.format(item.yearlyCents() / 12)} / Monat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (previous != null && item.amountCents < 0 && abs(item.amountCents) > abs(previous)) {
            Text(
                "Teurer geworden: vorher ${Money.format(abs(previous))}, jetzt ${Money.format(abs(item.amountCents))}",
                style = MaterialTheme.typography.bodySmall,
                color = LocalFinanceColors.current.warning,
            )
        }
        val end = item.contractEnd
        val notice = item.noticeDays
        if (end != null && notice != null && item.active) {
            val deadline = end.minusDays(notice.toLong())
            val left = deadline.toEpochDay() - today.toEpochDay()
            if (left in 0..45) {
                Text(
                    "Kündigung bis ${deadline.shortLabel()} möglich (noch $left Tage)",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalFinanceColors.current.warning,
                )
            }
        }
    }
}

@Composable
fun RecurringEditScreen(itemId: Long, onDone: () -> Unit, fromTransactionId: Long = 0) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    // Aus Buchungen angelegt: deren Schlüssel ordnet künftige Zahlungen zu, solange die Bezeichnung nicht geändert wird.
    var basisKey by rememberSaveable { mutableStateOf("") }
    var basisName by rememberSaveable { mutableStateOf("") }
    var basis by rememberSaveable { mutableStateOf(longArrayOf()) }

    var loaded by remember { mutableStateOf<RecurringEntity?>(null) }
    var ready by rememberSaveable { mutableStateOf(itemId == 0L && fromTransactionId == 0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var alias by rememberSaveable { mutableStateOf("") }
    var groupId by rememberSaveable { mutableStateOf<Long?>(null) }
    val groups by app.repository.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    var isExpense by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    var interval by rememberSaveable { mutableStateOf(RecurrenceInterval.MONTHLY) }
    var nextDue by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var active by rememberSaveable { mutableStateOf(true) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var hasContractEnd by rememberSaveable { mutableStateOf(false) }
    var contractEnd by rememberSaveable { mutableStateOf(LocalDate.now().plusYears(1).toEpochDay()) }
    var noticeDays by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(itemId) {
        if (itemId == 0L) return@LaunchedEffect
        val item = app.repository.recurringItem(itemId) ?: return@LaunchedEffect
        loaded = item
        if (!ready) {
            name = item.name
            alias = item.alias
            groupId = item.groupId
            isExpense = item.amountCents <= 0
            amount = abs(item.amountCents).toFieldText()
            interval = item.interval
            nextDue = item.nextDue.toEpochDay()
            active = item.active
            categoryId = item.categoryId
            hasContractEnd = item.contractEnd != null
            item.contractEnd?.let { contractEnd = it.toEpochDay() }
            noticeDays = item.noticeDays?.toString().orEmpty()
            ready = true
        }
    }

    val recurringItems by app.repository.recurring.collectAsStateWithLifecycle(initialValue = emptyList())
    val twin = recurringItems.firstOrNull { itemId == 0L && basisKey.isNotBlank() && it.matchKey == basisKey && !it.dismissed }
    val candidates = remember(transactions, basisKey) {
        if (basisKey.isBlank()) emptyList()
        else transactions.filter { it.transferPeerId == null && RecurringDetector.matchKey(it.toTx()) == basisKey }
    }

    /** Übernimmt Betrag, Rhythmus und nächsten Termin aus den gewählten Buchungen. */
    fun applyBasis(ids: LongArray) {
        basis = ids
        val chosen = candidates.filter { it.id in ids }.sortedBy { it.bookingDate }
        val latest = chosen.lastOrNull() ?: return
        isExpense = latest.amountCents < 0
        amount = abs(latest.amountCents).toFieldText()
        val dates = chosen.map { it.bookingDate }.distinct()
        val gap = dates.zipWithNext { a, b -> b.toEpochDay() - a.toEpochDay() }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        interval = when {
            gap == null -> interval
            gap <= 10 -> RecurrenceInterval.WEEKLY
            gap <= 20 -> RecurrenceInterval.BIWEEKLY
            gap <= 70 -> RecurrenceInterval.MONTHLY
            gap <= 140 -> RecurrenceInterval.QUARTERLY
            gap <= 270 -> RecurrenceInterval.HALF_YEARLY
            else -> RecurrenceInterval.YEARLY
        }
        var due = interval.next(latest.bookingDate)
        while (due < LocalDate.now()) due = interval.next(due)
        nextDue = due.toEpochDay()
        if (categoryId == null) categoryId = chosen.lastOrNull { it.categoryId != null }?.categoryId
    }

    LaunchedEffect(fromTransactionId) {
        if (fromTransactionId == 0L || itemId != 0L || ready) return@LaunchedEffect
        val tx = app.repository.transaction(fromTransactionId) ?: return@LaunchedEffect
        basisKey = RecurringDetector.matchKey(tx.toTx())
        basisName = tx.counterparty.trim().ifEmpty { tx.purpose.trim().take(60) }.ifEmpty { "Zahlung ohne Buchungstext" }
        name = basisName
        categoryId = tx.categoryId
        ready = true
    }
    // Sobald die Umsätze da sind: die Buchung selbst und gleiche mit ähnlichem Betrag vorauswählen.
    val baseTx = candidates.firstOrNull { it.id == fromTransactionId }
    LaunchedEffect(baseTx != null) {
        if (baseTx == null || basis.isNotEmpty()) return@LaunchedEffect
        val reference = abs(baseTx.amountCents)
        applyBasis(candidates.filter { abs(abs(it.amountCents) - reference) <= reference / 20 }.map { it.id }.toLongArray())
    }

    val amountCents = Money.parse(amount)?.let(::abs)
    val valid = name.isNotBlank() && amountCents != null && amountCents > 0

    Screen(
        title = if (itemId == 0L) "Neuer Dauerposten" else "Dauerposten bearbeiten",
        onBack = onDone,
        actions = {
            if (itemId != 0L) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Dauerposten entfernen")
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
            loaded?.takeIf { it.isPosition }?.let { item ->
                val settlement = transactions.lastOrNull { RecurringDetector.matchKey(it.toTx()) == item.parentKey }
                Text(
                    "Wird automatisch als Position in die Abrechnung „${settlement?.counterparty ?: "Sammelabrechnung"}“ eingetragen, " +
                        "sobald diese nach dem Termin gebucht ist. Auf dem Konto zählt nur die Abrechnung selbst.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (groups.isNotEmpty()) {
                    val none = GroupEntity(id = 0, name = "Keine Gruppe")
                    DropdownField(
                        label = "Gruppe der Positionen",
                        options = listOf(none) + groups,
                        selected = groups.firstOrNull { it.id == groupId },
                        onSelect = { groupId = it.id.takeIf { id -> id != 0L } },
                        optionLabel = { it.name },
                        placeholder = "Keine Gruppe",
                    )
                }
            }
            if (loaded?.detected == true) {
                Text(
                    "Automatisch aus deinen Umsätzen erkannt. Betrag und Termin werden mit jeder neuen Buchung fortgeschrieben.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (candidates.isNotEmpty()) {
                SectionCard(title = "Aus diesen Buchungen") {
                    Text(
                        "Betrag, Rhythmus und nächster Termin werden aus den gewählten Buchungen übernommen. " +
                            "Künftige Zahlungen derselben Art schreiben den Posten fort.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (twin != null) {
                        Text(
                            "Für diese Zahlung gibt es schon den Posten „${twin.displayName}“. Speichern ersetzt ihn mit den Angaben hier.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalFinanceColors.current.warning,
                        )
                    }
                    candidates.take(24).forEach { tx ->
                        val checked = tx.id in basis
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                applyBasis(if (checked) basis.filter { it != tx.id }.toLongArray() else basis + tx.id)
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(tx.bookingDate.shortLabel(), modifier = Modifier.weight(1f).padding(start = 8.dp))
                            AmountText(tx.amountCents, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = isExpense, onClick = { isExpense = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Ausgabe") }
                SegmentedButton(selected = !isExpense, onClick = { isExpense = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Einnahme") }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Bezeichnung im Kontoauszug") },
                supportingText = { Text("So, wie der Empfänger im Kontoauszug heißt – darüber werden die Zahlungen zugeordnet") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = alias,
                onValueChange = { alias = it },
                label = { Text("Anzeigename (optional)") },
                supportingText = { Text("Nur zur Anzeige, etwa „Handyvertrag“. Hat keinen Einfluss darauf, welche Zahlungen dazugehören") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MoneyField(value = amount, onValueChange = { amount = it }, label = "Betrag")
            DropdownField(
                label = "Rhythmus",
                options = RecurrenceInterval.entries,
                selected = interval,
                onSelect = { interval = it },
                optionLabel = { it.label.replaceFirstChar(Char::uppercase) },
            )
            DateField(date = LocalDate.ofEpochDay(nextDue), onDateChange = { nextDue = it.toEpochDay() }, label = "Nächste Fälligkeit")
            DropdownField(
                label = "Kategorie",
                options = categories,
                selected = categories.firstOrNull { it.id == categoryId },
                onSelect = { categoryId = it.id },
                optionLabel = { it.name },
                placeholder = "Ohne Kategorie",
            )
            SwitchRow("Aktiv", "Pausierte Posten zählen nicht ins Budget", active) { active = it }
            SwitchRow("Vertrag mit Laufzeit", "Erinnert rechtzeitig an die Kündigungsfrist", hasContractEnd) { hasContractEnd = it }
            if (hasContractEnd) {
                DateField(date = LocalDate.ofEpochDay(contractEnd), onDateChange = { contractEnd = it.toEpochDay() }, label = "Vertragsende")
                OutlinedTextField(
                    value = noticeDays,
                    onValueChange = { text -> noticeDays = text.filter(Char::isDigit).take(3) },
                    label = { Text("Kündigungsfrist in Tagen") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                enabled = valid,
                onClick = {
                    val cents = amountCents ?: return@Button
                    val signed = if (isExpense) -cents else cents
                    val base = loaded ?: RecurringEntity(name = "", amountCents = 0, nextDue = LocalDate.now())
                    val key = when {
                        base.detected -> base.matchKey
                        // Aus Buchungen angelegt und Bezeichnung unverändert: deren Schlüssel behalten.
                        basisKey.isNotBlank() && name.trim() == basisName -> basisKey
                        // Von Hand angelegt und umbenannt: Schlüssel neu aus der Bezeichnung bilden.
                        loaded != null && name.trim() != loaded?.name -> ""
                        else -> base.matchKey
                    }
                    val item = base.copy(
                        matchKey = key,
                        name = name.trim(),
                        alias = alias.trim(),
                        groupId = groupId,
                        amountCents = signed,
                        // Eine von Hand bestätigte Betragsänderung ist keine „Preiserhöhung" mehr.
                        previousAmountCents = null,
                        interval = interval,
                        nextDue = LocalDate.ofEpochDay(nextDue),
                        active = active,
                        categoryId = categoryId,
                        contractEnd = if (hasContractEnd) LocalDate.ofEpochDay(contractEnd) else null,
                        noticeDays = if (hasContractEnd) noticeDays.toIntOrNull() else null,
                    )
                    scope.launch {
                        app.repository.saveRecurring(item)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Dauerposten entfernen?",
            text = "„${alias.ifBlank { name }}“ zählt dann nicht mehr als feste Zahlung. Die Umsätze selbst bleiben erhalten.",
            confirmLabel = "Entfernen",
            onConfirm = {
                confirmDelete = false
                val item = loaded ?: return@ConfirmDialog
                scope.launch {
                    app.repository.deleteRecurring(item)
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
