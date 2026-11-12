package net.jandev.financialanalyst.ui.recurring

import net.jandev.financialanalyst.ui.rules.toStored
import net.jandev.financialanalyst.ui.rules.toDraft
import net.jandev.financialanalyst.ui.rules.ConditionEditor
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.domain.RuleOp
import net.jandev.financialanalyst.domain.RuleMatcher
import net.jandev.financialanalyst.domain.RuleField
import net.jandev.financialanalyst.domain.RuleCondition
import net.jandev.financialanalyst.domain.MatchRules
import net.jandev.financialanalyst.data.db.RecurringIndex
import net.jandev.financialanalyst.ui.transactions.settlementKeysInUse

import net.jandev.financialanalyst.ui.transactions.settlementOptions

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import net.jandev.financialanalyst.domain.ChangeKind
import net.jandev.financialanalyst.domain.ContractSchedule
import net.jandev.financialanalyst.domain.Installment
import net.jandev.financialanalyst.domain.ScheduleChange
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
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
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
}

/** Wonach die Liste der Posten gruppiert ist. */
private enum class Grouping(val label: String) {
    INTERVAL("Rhythmus"),
    CATEGORY("Kategorie"),
    AREA("Bereich"),
}

/** Reiter der Fixkosten-Seite. */
private enum class FixedTab(val label: String) {
    OVERVIEW("Überblick"),
    FORECAST("Vorschau"),
    ITEMS("Posten"),
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
private fun RecurringEntity.yearlyCents() = yearlyTotalCents

@Composable
fun RecurringScreen(onEdit: (Long) -> Unit) {
    val app = LocalApp.current
    val items by app.repository.recurring.collectAsStateWithLifecycle(initialValue = null)
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    var sort by rememberSaveable { mutableStateOf(RecurringSort.DUE) }
    var grouping by rememberSaveable { mutableStateOf(Grouping.INTERVAL) }
    var tab by rememberSaveable { mutableStateOf(FixedTab.OVERVIEW) }
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
        }
        val active = visible.filter { it.active }
        val fixed = remember(active, categories) { fixedItems(active, categories) }
        val fixedExpenses = fixed.filter { it.item.amountCents < 0 }
        val fixedIncomes = fixed.filter { it.item.amountCents > 0 }
        val paused = ordered(visible.filter { !it.active })

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "tabs") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    FixedTab.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = tab == option,
                            onClick = { tab = option },
                            shape = SegmentedButtonDefaults.itemShape(index, FixedTab.entries.size),
                        ) { Text(option.label) }
                    }
                }
            }
            when (tab) {
                FixedTab.OVERVIEW -> {
                    item(key = "sum") { OverviewSummaryCard(fixedExpenses, fixedIncomes) }
                    item(key = "share") { DistributionCard(fixedExpenses, onEdit) }
                    item(key = "cancel") { CancelCheckCard(fixedExpenses, today, onEdit) }
                }
                FixedTab.FORECAST -> {
                    item(key = "forecast") { ForecastTab(fixedExpenses, fixedIncomes, transactions, splits, today, onEdit) }
                }
                FixedTab.ITEMS -> {
                    item(key = "sort") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Gruppen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Grouping.entries.forEach { option ->
                                    FilterChip(selected = grouping == option, onClick = { grouping = option }, label = { Text(option.label) })
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Sortieren", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                RecurringSort.entries.forEach { option ->
                                    FilterChip(selected = sort == option, onClick = { sort = option }, label = { Text(option.label) })
                                }
                            }
                        }
                    }
                    for ((kind, list) in listOf("Ausgaben" to fixedExpenses, "Einnahmen" to fixedIncomes)) {
                        // Abschnitte: nach Rhythmus wie bisher, sonst nach Kategorie oder Bereich, die teuersten zuerst.
                        val sections: List<Pair<String, List<RecurringEntity>>> = when (grouping) {
                            Grouping.INTERVAL -> sectionOrder.mapNotNull { (interval, adjective) ->
                                list.filter { it.item.interval == interval }.takeIf { it.isNotEmpty() }
                                    ?.let { group -> "$adjective $kind" to group.map { it.item } }
                            }
                            Grouping.CATEGORY -> list.groupBy { it.category }.entries
                                .sortedByDescending { entry -> entry.value.sumOf { it.yearlyCents } }
                                .map { (category, group) -> "${category?.name ?: "Ohne Kategorie"} · $kind" to group.map { it.item } }
                            Grouping.AREA -> list.groupBy { it.area }.entries
                                .sortedByDescending { entry -> entry.value.sumOf { it.yearlyCents } }
                                .map { (area, group) -> "${area.label} · $kind" to group.map { it.item } }
                        }
                        for ((title, members) in sections) {
                            val section = ordered(members)
                            val interval = section.first().interval
                            item(key = "$kind-$title") {
                                RecurringGroup(
                                    title = title,
                                    sums = listOfNotNull(
                                        "${Money.format(section.sumOf { abs(it.amountCents) })} ${interval.perLabel()}"
                                            .takeIf { grouping == Grouping.INTERVAL && interval != RecurrenceInterval.MONTHLY },
                                        "${Money.format(section.sumOf { it.yearlyCents() } / 12)} pro Monat",
                                        "${Money.format(section.sumOf { it.yearlyCents() })} pro Jahr".takeIf { grouping != Grouping.INTERVAL },
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
    val open = if (item.isContract && item.active) ContractSchedule.openDues(item.plan(), today).filter { it.date < today } else emptyList()
    val due = when {
        open.size == 1 -> "offen seit ${open[0].date.shortLabel()}"
        open.size > 1 -> "${open.size} Beiträge offen seit ${open[0].date.shortLabel()} (${Money.format(abs(open.sumOf { it.amountCents }))})"
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
                if (item.interval != RecurrenceInterval.MONTHLY || item.yearlyExtras.isNotEmpty()) {
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
    // An welchen Buchungen der Posten erkannt wird; leer = an der Bezeichnung.
    var linkKey by rememberSaveable { mutableStateOf("") }
    var isContract by rememberSaveable { mutableStateOf(false) }
    var firstDue by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var changesText by rememberSaveable { mutableStateOf("") }
    var editingDue by remember { mutableStateOf<Installment?>(null) }
    // Sonderzahlung im Dialog; ein Eintrag mit Betrag 0 ist eine neue.
    var editingExtra by remember { mutableStateOf<ScheduleChange?>(null) }
    var note by rememberSaveable { mutableStateOf("") }
    var parentKey by rememberSaveable { mutableStateOf("") }
    // Eigene Regeln, welche Buchungen dazugehören: Formularwerte, kodiert wie gespeichert.
    var ownRules by rememberSaveable { mutableStateOf(false) }
    var rulesText by rememberSaveable { mutableStateOf("") }
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())

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
            linkKey = item.matchKey
            isContract = item.isContract
            firstDue = (item.firstDue ?: item.nextDue).toEpochDay()
            changesText = item.scheduleChanges
            note = item.note
            parentKey = item.parentKey
            ownRules = item.hasOwnRules
            rulesText = MatchRules.encode(MatchRules.decode(item.matchRules).map { rule -> rule.map { it.toDraft() } })
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
        linkKey = basisKey
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
    val ruleDrafts = remember(rulesText) { MatchRules.decode(rulesText) }
    val storedRules = remember(ruleDrafts) { ruleDrafts.map { rule -> rule.map { it.toStored() } } }
    val rulesValid = !ownRules || (storedRules.isNotEmpty() && storedRules.all { rule -> rule.isNotEmpty() && rule.all(RuleMatcher::isValid) })
    val valid = name.isNotBlank() && amountCents != null && amountCents > 0 && rulesValid
    val signedAmount = (amountCents ?: 0).let { if (isExpense) -it else it }
    // Schlüssel, unter dem die Buchungen gerade zugeordnet würden.
    val effectiveKey = linkKey.ifBlank { RecurringDetector.matchKey(name.trim(), signedAmount) }
    val payees = remember(transactions, isExpense, linkKey) {
        val all = transactions
            .filter { it.transferPeerId == null && (it.amountCents < 0) == isExpense }
            .groupBy { RecurringDetector.matchKey(it.toTx()) }
            .map { (key, txs) -> Payee(key, txs.first().counterparty.ifBlank { txs.first().purpose.take(40) }.ifBlank { "Ohne Buchungstext" }, txs.size, txs.maxOf { it.bookingDate }) }
            .sortedByDescending { it.latest }
        // Die zuletzt gebuchten Empfänger, und immer der gerade zugeordnete.
        (all.take(60) + all.filter { it.key == linkKey }).distinct()
    }
    val changes = remember(changesText) { ScheduleChange.decode(changesText) }
    // Buchungen, die nach den eigenen Regeln dazugehören (Vorschau).
    val ruleHits = remember(ownRules, storedRules, rulesValid, transactions) {
        if (!ownRules || !rulesValid) emptyList()
        else transactions.filter { it.id > 0 && MatchRules.matches(storedRules, it.counterparty, it.purpose, it.amountCents, it.accountId) }
            .sortedByDescending { it.bookingDate }
    }
    val schedule = remember(isContract, firstDue, interval, signedAmount, changes, transactions, effectiveKey, ownRules, ruleHits) {
        if (!isContract || signedAmount == 0L) emptyList()
        else {
            val bookings = (if (ownRules) ruleHits else transactions.filter { RecurringDetector.matchKey(it.toTx()) == effectiveKey })
                .map { it.bookingDate to it.amountCents }
            ContractSchedule.allocate(
                ContractSchedule.dues(LocalDate.ofEpochDay(firstDue), interval, signedAmount, changes, LocalDate.now().plusMonths(13)),
                bookings,
            )
        }
    }

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
                Text(
                    "Wird automatisch als Position in die gewählte Abrechnung eingetragen, sobald diese nach dem Termin " +
                        "gebucht ist. Auf dem Konto zählt nur die Abrechnung selbst.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val allSplits by app.repository.splits.collectAsStateWithLifecycle(initialValue = emptyList())
                val settlements = remember(transactions, parentKey, allSplits, recurringItems) {
                    settlementOptions(transactions, parentKey, settlementKeysInUse(transactions, allSplits.map { it.parentId }.toSet(), recurringItems))
                }
                DropdownField(
                    label = "Abgerechnet über",
                    options = settlements,
                    selected = settlements.firstOrNull { it.key == parentKey },
                    onSelect = { parentKey = it.key },
                    optionLabel = { it.label },
                )
                if (parentKey != item.parentKey) {
                    Text(
                        "Künftige Termine landen in dieser Abrechnung; bisher eingetragene bleiben, wo sie sind.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            if (loaded?.isPosition != true) {
                MatchRulesSection(
                    enabled = ownRules,
                    onEnabledChange = { on ->
                        ownRules = on
                        // Vorschlag: was die Automatik bisher zuordnet – Empfänger und Betragsbereich.
                        if (on && ruleDrafts.isEmpty()) rulesText = MatchRules.encode(listOf(suggestedRule(effectiveKey, signedAmount).map { it.toDraft() }))
                    },
                    drafts = ruleDrafts,
                    onDraftsChange = { rulesText = MatchRules.encode(it) },
                    accounts = accounts.map { it.account },
                    hits = ruleHits,
                    valid = rulesValid,
                )
            }
            if (loaded?.isPosition != true && !ownRules) {
                val byName = Payee("", "An der Bezeichnung erkennen", 0, LocalDate.MIN)
                val current = payees.firstOrNull { it.key == linkKey }
                    ?: if (linkKey.isNotBlank()) Payee(linkKey, "Wie bisher", 0, LocalDate.MIN) else byName
                DropdownField(
                    label = "Zugehörige Buchungen",
                    options = listOf(byName) + payees + listOfNotNull(current.takeIf { it !in payees && it != byName }),
                    selected = current,
                    onSelect = { linkKey = it.key },
                    optionLabel = { payee ->
                        if (payee.count == 0) payee.name else "${payee.name} (${payee.count}×, zuletzt ${payee.latest.shortLabel()})"
                    },
                )
            }
            MoneyField(value = amount, onValueChange = { amount = it }, label = if (isContract) "Regulärer Beitrag" else "Betrag")
            DropdownField(
                label = "Rhythmus",
                options = RecurrenceInterval.entries,
                selected = interval,
                onSelect = { interval = it },
                optionLabel = { it.label.replaceFirstChar(Char::uppercase) },
            )
            if (loaded?.isPosition != true) {
                SwitchRow(
                    "Zahlungsplan laut Vertrag",
                    "Feste Termine ab dem ersten Beitrag. Ausgebliebene Beiträge bleiben offen, bis sie abgebucht sind",
                    isContract,
                ) { isContract = it }
            }
            if (isContract) {
                DateField(date = LocalDate.ofEpochDay(firstDue), onDateChange = { firstDue = it.toEpochDay() }, label = "Erster Termin des Plans")
                Text(
                    "Der erste Beitrag laut Vertrag – oder der erste regelmäßige, wenn die Anfangszeit (Tarifwechsel, Gutschriften) " +
                        "nicht nachgebildet werden soll. Die Umsätze davor bleiben unberührt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ContractScheduleCard(schedule, changes, onEdit = { editingDue = it })
            } else {
                DateField(date = LocalDate.ofEpochDay(nextDue), onDateChange = { nextDue = it.toEpochDay() }, label = "Nächste Fälligkeit")
            }
            if (loaded?.isPosition != true) {
                YearlyExtrasCard(
                    extras = ScheduleChange.yearly(changes),
                    regularCents = amountCents ?: 0,
                    interval = interval,
                    onEdit = { editingExtra = it },
                )
            }
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
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Notiz (optional)") },
                supportingText = { Text("Etwa Vertragsnummer, Konditionen oder Ansprechpartner; bleibt nur auf diesem Gerät und in der Sicherung") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = valid,
                onClick = {
                    val cents = amountCents ?: return@Button
                    val signed = if (isExpense) -cents else cents
                    val base = loaded ?: RecurringEntity(name = "", amountCents = 0, nextDue = LocalDate.now())
                    // Ein Schlüssel, der nur aus der alten Bezeichnung entstanden ist, folgt einer Umbenennung.
                    val nameDerived = loaded?.let { it.matchKey == RecurringDetector.matchKey(it.name, it.amountCents) } == true
                    val key = when {
                        nameDerived && linkKey == loaded?.matchKey && name.trim() != loaded?.name -> ""
                        else -> linkKey
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
                        firstDue = if (isContract) LocalDate.ofEpochDay(firstDue) else null,
                        // Sonderzahlungen gelten auch ohne Vertragsplan; ihr Vorzeichen folgt dem Posten.
                        scheduleChanges = ScheduleChange.encode(
                            (if (isContract) changes else ScheduleChange.yearly(changes)).map { change ->
                                if (change.kind == ChangeKind.YEARLY) change.copy(amountCents = if (isExpense) -abs(change.amountCents) else abs(change.amountCents)) else change
                            },
                        ),
                        note = note.trim(),
                        parentKey = if (base.isPosition) parentKey else base.parentKey,
                        accountId = if (base.isPosition && parentKey != base.parentKey) {
                            transactions.lastOrNull { RecurringDetector.matchKey(it.toTx()) == parentKey }?.accountId ?: base.accountId
                        } else base.accountId,
                        // Ein Vertrag folgt seinem Plan; die Erkennung schreibt ihn nicht mehr fort.
                        detected = base.detected && !isContract && !ownRules,
                        matchRules = if (ownRules && base.parentKey.isBlank()) MatchRules.encode(storedRules) else "",
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

    editingDue?.let { due ->
        InstallmentDialog(
            due = due,
            change = changes.firstOrNull { it.date == due.date && it.kind != ChangeKind.YEARLY },
            isExpense = isExpense,
            onDismiss = { editingDue = null },
            onSave = { change ->
                changesText = ScheduleChange.encode(changes.filter { it.date != due.date || it.kind == ChangeKind.YEARLY } + listOfNotNull(change))
                editingDue = null
            },
        )
    }

    editingExtra?.let { extra ->
        val isNew = extra.amountCents == 0L
        YearlyExtraDialog(
            extra = extra,
            isNew = isNew,
            onDismiss = { editingExtra = null },
            onSave = { changed ->
                val others = if (isNew) changes else changes.filter { it != extra }
                changesText = ScheduleChange.encode(others + listOfNotNull(changed?.copy(amountCents = if (isExpense) -changed.amountCents else changed.amountCents)))
                editingExtra = null
            },
        )
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

private data class Payee(val key: String, val name: String, val count: Int, val latest: LocalDate)

/** Was die Automatik einem Posten zuordnet, als Regel: Empfänger und Betragsbereich. */
private fun suggestedRule(key: String, signedAmount: Long): List<RuleCondition> {
    val base = key.substringBeforeLast('|')
    val sign = when {
        key.endsWith("|-") -> -1
        key.endsWith("|+") -> 1
        else -> if (signedAmount < 0) -1 else 1
    }
    val byKey = if (base.isBlank()) emptyList() else RuleMatcher.fromLearnKey(base, sign)
    if (signedAmount == 0L || byKey.any { it.field == RuleField.AMOUNT }) return byKey
    val range = RecurringEntity(name = "", amountCents = signedAmount, nextDue = LocalDate.now()).coveredRange()
    return byKey + RuleCondition(RuleField.AMOUNT, RuleOp.AT_LEAST, range.first.toString()) +
        RuleCondition(RuleField.AMOUNT, RuleOp.AT_MOST, range.last.toString())
}

/**
 * Eigene Regeln, welche Buchungen zum Posten gehören: innerhalb einer Regel müssen alle Bedingungen zutreffen,
 * von mehreren Regeln genügt eine.
 */
@Composable
private fun MatchRulesSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    drafts: List<List<RuleCondition>>,
    onDraftsChange: (List<List<RuleCondition>>) -> Unit,
    accounts: List<AccountEntity>,
    hits: List<TransactionEntity>,
    valid: Boolean,
) {
    SwitchRow(
        "Eigene Regeln für die Zuordnung",
        "Legt genau fest, welche Buchungen dazugehören, etwa Empfänger und Betragsbereich. Sonst automatisch: " +
            "gleicher Empfänger und ähnlicher Betrag",
        enabled,
        onEnabledChange,
    )
    if (!enabled) return
    val newCondition = RuleCondition(RuleField.COUNTERPARTY, RuleOp.CONTAINS, "")
    drafts.forEachIndexed { ruleIndex, rule ->
        if (ruleIndex > 0) Text("oder", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            if (drafts.size == 1) "Gehört dazu, wenn alle Bedingungen zutreffen" else "Regel ${ruleIndex + 1}: alle Bedingungen treffen zu",
            style = MaterialTheme.typography.titleSmall,
        )
        rule.forEachIndexed { index, condition ->
            ConditionEditor(
                condition = condition,
                accounts = accounts,
                removable = rule.size > 1 || drafts.size > 1,
                onChange = { changed ->
                    onDraftsChange(drafts.mapIndexed { r, conditions -> if (r != ruleIndex) conditions else conditions.toMutableList().also { it[index] = changed } })
                },
                onRemove = {
                    onDraftsChange(
                        drafts.mapIndexed { r, conditions -> if (r != ruleIndex) conditions else conditions.filterIndexed { i, _ -> i != index } }
                            .filter { it.isNotEmpty() },
                    )
                },
            )
        }
        TextButton(onClick = { onDraftsChange(drafts.mapIndexed { r, conditions -> if (r == ruleIndex) conditions + newCondition else conditions }) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Bedingung (und)", modifier = Modifier.padding(start = 8.dp))
        }
    }
    TextButton(onClick = { onDraftsChange(drafts + listOf(listOf(newCondition))) }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text("Weitere Regel (oder)", modifier = Modifier.padding(start = 8.dp))
    }
    SectionCard(title = "Zugeordnete Buchungen") {
        Text(
            when {
                !valid -> "Fülle alle Bedingungen aus, um die Treffer zu sehen."
                hits.isEmpty() -> "Trifft bisher auf keine Buchung zu."
                hits.size == 1 -> "Trifft auf 1 Buchung zu."
                else -> "Trifft auf ${hits.size} Buchungen zu."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hits.take(8).forEach { tx ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(tx.counterparty.ifBlank { tx.purpose }.ifBlank { "Ohne Buchungstext" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tx.bookingDate.shortLabel(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AmountText(tx.amountCents, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (hits.size > 8) {
            Text("… und ${hits.size - 8} ältere", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Termine eines Vertrags: bisherige mit der zugeordneten Buchung, offene hervorgehoben, dazu die nächsten. */
@Composable
private fun ContractScheduleCard(schedule: List<Installment>, changes: List<ScheduleChange>, onEdit: (Installment) -> Unit) {
    val today = LocalDate.now()
    val open = schedule.filter { it.isOpen && it.date <= today }
    SectionCard(title = "Termine") {
        if (open.isNotEmpty()) {
            Text(
                "${if (open.size == 1) "1 Beitrag" else "${open.size} Beiträge"} offen, zusammen ${Money.format(abs(open.sumOf { it.amountCents }))}. " +
                    "Sie zählen im Budget als fällig, bis sie abgebucht sind.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalFinanceColors.current.warning,
            )
        }
        val past = schedule.filter { it.date <= today }.takeLast(8)
        val future = schedule.filter { it.date > today }.take(4)
        (past + future).forEachIndexed { index, due ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val change = changes.firstOrNull { it.date == due.date && it.kind != ChangeKind.YEARLY }
            val extras = if (change?.kind == ChangeKind.ONCE) emptyList() else ScheduleChange.extrasIn(changes, due.date)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onEdit(due) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(due.date.shortLabel(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(
                            when {
                                due.settledManually -> "ohne eigene Buchung bezahlt"
                                due.paidOn != null -> "abgebucht am ${due.paidOn!!.shortLabel()}" +
                                    (due.paidCents?.takeIf { it != due.amountCents }?.let { " (${Money.format(abs(it))})" } ?: "")
                                due.date <= today -> "offen"
                                else -> "geplant"
                            },
                            when (change?.kind) {
                                ChangeKind.ONCE -> "abweichender Betrag"
                                ChangeKind.FROM_HERE -> "neuer Beitrag ab hier"
                                else -> null
                            },
                            extras.takeIf { it.isNotEmpty() }?.let { list -> "mit " + list.joinToString { it.note.ifBlank { "Sonderzahlung" } } },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (due.isOpen && due.date <= today) LocalFinanceColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(due.amountCents, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            "Tippe einen Termin an für einen anderen Betrag (Rabatt, Beitragsänderung), eine Pause oder eine Zahlung ohne Buchung.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Abweichung eines Termins festlegen; „Regulär" entfernt sie. */
@Composable
private fun InstallmentDialog(
    due: Installment,
    change: ScheduleChange?,
    isExpense: Boolean,
    onDismiss: () -> Unit,
    onSave: (ScheduleChange?) -> Unit,
) {
    var kind by rememberSaveable { mutableStateOf(change?.kind) }
    var amount by rememberSaveable {
        mutableStateOf(abs(change?.amountCents?.takeIf { it != 0L } ?: due.amountCents).toFieldText())
    }
    val cents = Money.parse(amount)?.let(::abs)
    val needsAmount = kind == ChangeKind.ONCE || kind == ChangeKind.FROM_HERE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Termin ${due.date.shortLabel()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                (listOf<ChangeKind?>(null) + ChangeKind.entries.filter { it != ChangeKind.YEARLY }).forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { kind = option },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == option, onClick = { kind = option })
                        Text(option?.label ?: "Regulär", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (needsAmount) MoneyField(value = amount, onValueChange = { amount = it }, label = "Betrag")
            }
        },
        confirmButton = {
            TextButton(
                enabled = !needsAmount || (cents != null && cents > 0),
                onClick = {
                    val chosen = kind
                    onSave(
                        chosen?.let {
                            val value = if (needsAmount) cents ?: 0 else 0
                            ScheduleChange(due.date, it, if (isExpense) -value else value)
                        },
                    )
                },
            ) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private fun Month.germanName(): String = getDisplayName(TextStyle.FULL_STANDALONE, Locale.GERMAN)

/** Jährliche Sonderzahlungen eines Postens: Urlaubs-, Weihnachtsgeld, Jahresbonus oder eine jährliche Gebühr. */
@Composable
private fun YearlyExtrasCard(
    extras: List<ScheduleChange>,
    regularCents: Long,
    interval: RecurrenceInterval,
    onEdit: (ScheduleChange) -> Unit,
) {
    SectionCard(title = "Jährliche Sonderzahlungen") {
        Text(
            "Kommen jedes Jahr im selben Monat zum regulären Betrag dazu, etwa Urlaubs- und Weihnachtsgeld oder ein " +
                "Bonus. Sie zählen in Budget und Vorschau mit der ersten Zahlung des Monats.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        extras.sortedBy { it.date.monthValue }.forEach { extra ->
            Row(
                modifier = Modifier.fillMaxWidth().clickable { onEdit(extra) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(extra.note.ifBlank { "Sonderzahlung" }, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Jedes Jahr im ${extra.date.month.germanName()} · ab ${extra.date.year}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(extra.amountCents, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (extras.isNotEmpty() && regularCents > 0) {
            val extraYearly = abs(extras.sumOf { it.amountCents })
            val yearly = interval.yearlyCents(regularCents) + extraYearly
            Text(
                "Im Jahr ${Money.format(yearly)}, davon ${Money.format(extraYearly)} Sonderzahlungen; " +
                    "Ø ${Money.format(yearly / 12)} pro Monat.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onEdit(ScheduleChange(LocalDate.now().withDayOfMonth(1), ChangeKind.YEARLY, 0)) }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Sonderzahlung hinzufügen", modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Sonderzahlung anlegen, ändern oder entfernen; der Betrag ohne Vorzeichen. */
@Composable
private fun YearlyExtraDialog(
    extra: ScheduleChange,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (ScheduleChange?) -> Unit,
) {
    var note by rememberSaveable { mutableStateOf(extra.note) }
    var month by rememberSaveable { mutableStateOf(extra.date.month) }
    var amount by rememberSaveable { mutableStateOf(if (isNew) "" else abs(extra.amountCents).toFieldText()) }
    val cents = Money.parse(amount)?.let(::abs)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Neue Sonderzahlung" else "Sonderzahlung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Bezeichnung") },
                    placeholder = { Text("etwa Urlaubsgeld") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownField(
                    label = "Jedes Jahr im",
                    options = Month.entries,
                    selected = month,
                    onSelect = { month = it },
                    optionLabel = { it.germanName() },
                )
                MoneyField(value = amount, onValueChange = { amount = it }, label = "Zusätzlich zum regulären Betrag")
                if (!isNew) {
                    TextButton(onClick = { onSave(null) }) { Text("Sonderzahlung entfernen") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = cents != null && cents > 0,
                onClick = {
                    // Gilt ab diesem Monat im Jahr der Anlage, damit auch die Termine des laufenden Jahres stimmen.
                    val start = (if (isNew) LocalDate.now() else extra.date).withDayOfMonth(1).withMonth(month.value)
                    onSave(ScheduleChange(start, ChangeKind.YEARLY, cents ?: 0, note.trim()))
                },
            ) { Text("Übernehmen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
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
