package net.jandev.financialanalyst.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import net.jandev.financialanalyst.ui.categories.CategoryDialog
import net.jandev.financialanalyst.ui.groups.GroupDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.data.db.RecurringEntity
import net.jandev.financialanalyst.data.toTx
import net.jandev.financialanalyst.domain.RecurrenceInterval
import net.jandev.financialanalyst.domain.RecurringDetector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.GroupEntity
import net.jandev.financialanalyst.data.db.SplitEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.DateField
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.dayMonthLabel
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.common.toFieldText
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate
import kotlin.math.abs

/**
 * Positionen eines Umsatzes, etwa die Einzelkäufe einer Kreditkartenabrechnung. Sie ändern keinen Kontostand und zählen
 * nicht doppelt: in Auswertung, Budgets und Gruppen ersetzen sie den Umsatz, der Rest bleibt bei ihm.
 */
@Composable
fun SplitSection(parent: TransactionEntity, splits: List<SplitEntity>, categories: List<CategoryEntity>, groups: List<GroupEntity>) {
    var editing by remember { mutableStateOf<SplitEntity?>(null) }
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val groupNames = remember(groups) { groups.associate { it.id to it.name } }
    val rest = parent.amountCents - splits.sumOf { it.amountCents }
    // Gutschriften (Prämienpunkte, Rabatte) haben das umgekehrte Vorzeichen und mindern die Summe der Käufe.
    val overflow = rest != 0L && (rest < 0) != (parent.amountCents < 0)

    SectionCard(title = "Enthaltene Positionen") {
        if (splits.isEmpty()) {
            Text(
                "Teile den Umsatz in Positionen auf, etwa die Einzelkäufe einer Kreditkartenabrechnung. Jede Position bekommt " +
                    "eigene Kategorie und Gruppe; gezählt wird trotzdem nur einmal.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        splits.forEachIndexed { index, split ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().clickable { editing = split }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(split.description.ifBlank { "Ohne Beschreibung" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(
                            split.date.dayMonthLabel(),
                            "wiederkehrend".takeIf { split.recurringId != null },
                            categoryNames[split.categoryId] ?: "Ohne Kategorie",
                            groupNames[split.groupId],
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AmountText(split.amountCents, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (splits.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (rest == 0L) "Vollständig aufgeteilt" else "Noch nicht aufgeteilt (bleibt bei „${categoryNames[parent.categoryId] ?: "Ohne Kategorie"}“)",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overflow) LocalFinanceColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                if (rest != 0L) AmountText(rest, style = MaterialTheme.typography.bodyMedium, colored = false)
            }
            if (overflow) {
                Text(
                    "Die Positionen sind zusammen größer als der Umsatz. Rabatte oder eingelöste Prämienpunkte als Gutschrift eintragen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalFinanceColors.current.warning,
                )
            }
        }
        TextButton(
            onClick = {
                editing = SplitEntity(
                    parentId = parent.id,
                    date = parent.bookingDate,
                    // Vorschlag: der noch offene Rest; übersteigen die Käufe die Abrechnung, die fehlende Gutschrift.
                    amountCents = rest,
                )
            },
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("Position hinzufügen", modifier = Modifier.padding(start = 8.dp))
        }
    }

    editing?.let { split ->
        SplitDialog(split, parent, categories, groups, onDone = { editing = null })
    }
}

@Composable
private fun SplitDialog(
    split: SplitEntity,
    parent: TransactionEntity,
    categories: List<CategoryEntity>,
    groups: List<GroupEntity>,
    onDone: () -> Unit,
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val parentIsExpense = parent.amountCents < 0
    // Gutschrift: umgekehrtes Vorzeichen zum Umsatz, etwa ein Rabatt in der Kartenabrechnung.
    var credit by rememberSaveable { mutableStateOf(split.amountCents != 0L && (split.amountCents < 0) != parentIsExpense) }
    val isExpense = parentIsExpense != credit
    var creatingCategory by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }
    var description by rememberSaveable { mutableStateOf(split.description) }
    var amount by rememberSaveable { mutableStateOf(if (split.amountCents == 0L) "" else abs(split.amountCents).toFieldText()) }
    var day by rememberSaveable { mutableStateOf(split.date.toEpochDay()) }
    var categoryId by rememberSaveable { mutableStateOf(split.categoryId) }
    var groupId by rememberSaveable { mutableStateOf(split.groupId) }
    val recurring by app.repository.recurring.collectAsStateWithLifecycle(initialValue = emptyList())
    val template = recurring.firstOrNull { it.id == split.recurringId }
    var repeat by rememberSaveable { mutableStateOf(split.recurringId != null) }
    var interval by rememberSaveable { mutableStateOf<RecurrenceInterval?>(null) }
    val chosenInterval = interval ?: template?.interval ?: RecurrenceInterval.MONTHLY
    // Über welche Abrechnung das Abo künftig läuft, etwa nach einem Kartenwechsel; sonst über diese.
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val ownKey = remember(parent) { RecurringDetector.matchKey(parent.toTx()) }
    var settlementKey by rememberSaveable { mutableStateOf<String?>(null) }
    val chosenSettlement = settlementKey ?: template?.parentKey ?: ownKey
    val allSplits by app.repository.splits.collectAsStateWithLifecycle(initialValue = emptyList())
    val settlements = remember(transactions, chosenSettlement, allSplits, recurring) {
        settlementOptions(transactions, chosenSettlement, settlementKeysInUse(transactions, allSplits.map { it.parentId }.toSet(), recurring))
    }
    var nextDay by rememberSaveable { mutableStateOf<Long?>(null) }
    val chosenNext = nextDay?.let(LocalDate::ofEpochDay)
        ?: template?.nextDue?.takeIf { interval == null }
        ?: chosenInterval.next(LocalDate.ofEpochDay(day))
    val cents = Money.parse(amount)?.let(::abs)
    val selectable = remember(categories, credit, parentIsExpense) {
        // Eine Gutschrift mindert eine Ausgabenkategorie (Rabatt) oder ist eine Einnahme (Prämie): beide Arten zur Wahl.
        if (credit) categories.filter { it.kind != CategoryKind.TRANSFER }
        else categories.filter { it.kind == (if (parentIsExpense) CategoryKind.EXPENSE else CategoryKind.INCOME) }
    }
    val palette = LocalFinanceColors.current.chart

    if (creatingCategory) {
        CategoryDialog(
            category = CategoryEntity(
                name = "",
                kind = if (isExpense) CategoryKind.EXPENSE else CategoryKind.INCOME,
                colorIndex = palette.indices.firstOrNull { index -> categories.none { it.colorIndex == index } } ?: categories.size,
            ),
            usedNames = categories.map { it.name.lowercase() }.toSet(),
            onSaved = { categoryId = it },
            onDone = { creatingCategory = false },
        )
        return
    }
    if (creatingGroup) {
        GroupDialog(
            initial = null,
            suggestedColor = groups.size,
            onDismiss = { creatingGroup = false },
            onSave = { name, color ->
                scope.launch {
                    groupId = app.repository.saveGroup(GroupEntity(name = name, colorIndex = color))
                    creatingGroup = false
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (split.id == 0L) "Neue Position" else "Position bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !credit,
                        onClick = { credit = false; categoryId = null },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(if (parentIsExpense) "Kauf" else "Eingang") }
                    SegmentedButton(
                        selected = credit,
                        onClick = { credit = true; categoryId = null },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(if (parentIsExpense) "Gutschrift" else "Abzug") }
                }
                if (credit) {
                    Text(
                        "Rabatt, eingelöste Prämienpunkte oder Erstattung: mindert den Betrag der Abrechnung.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Beschreibung") },
                    placeholder = { Text("z. B. Amazon: USB-Kabel") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                MoneyField(value = amount, onValueChange = { amount = it }, label = "Betrag")
                DateField(date = LocalDate.ofEpochDay(day), onDateChange = { day = it.toEpochDay() }, label = "Datum")
                DropdownField(
                    label = "Kategorie",
                    options = selectable + CategoryEntity(id = NEW_ID, name = NEW_CATEGORY, kind = CategoryKind.EXPENSE),
                    selected = categories.firstOrNull { it.id == categoryId },
                    onSelect = { if (it.id == NEW_ID) creatingCategory = true else categoryId = it.id },
                    optionLabel = { it.name },
                    placeholder = "Ohne Kategorie",
                )
                run {
                    val none = GroupEntity(id = 0, name = "Keine Gruppe")
                    DropdownField(
                        label = "Gruppe",
                        options = listOf(none) + groups + GroupEntity(id = NEW_ID, name = NEW_GROUP),
                        selected = groups.firstOrNull { it.id == groupId },
                        onSelect = {
                            when (it.id) {
                                NEW_ID -> creatingGroup = true
                                0L -> groupId = null
                                else -> groupId = it.id
                            }
                        },
                        optionLabel = { it.name },
                        placeholder = "Keine Gruppe",
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { repeat = !repeat },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = repeat, onCheckedChange = { repeat = it })
                    Column {
                        Text("Wiederholt sich (Spar-Abo, Jahresabo)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Künftige Termine werden automatisch in die jeweils nächste Abrechnung eingetragen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (repeat) {
                    DropdownField(
                        label = "Wiederholung",
                        options = POSITION_INTERVALS,
                        selected = chosenInterval,
                        onSelect = { interval = it; nextDay = null },
                        optionLabel = { if (it == RecurrenceInterval.MONTHLY) "Jeden Monat" else it.label.replaceFirstChar(Char::uppercase) },
                    )
                    DateField(date = chosenNext, onDateChange = { nextDay = it.toEpochDay() }, label = "Nächster Termin")
                    DropdownField(
                        label = "Abgerechnet über",
                        options = settlements,
                        selected = settlements.firstOrNull { it.key == chosenSettlement },
                        onSelect = { settlementKey = it.key },
                        optionLabel = { it.label },
                    )
                    if (chosenSettlement != ownKey) {
                        Text(
                            "Künftige Termine landen in dieser Abrechnung, etwa nach einem Wechsel der Zahlungsart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (split.id != 0L) {
                    TextButton(onClick = {
                        scope.launch {
                            app.repository.deleteSplit(split)
                            onDone()
                        }
                    }) { Text("Position löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = cents != null && cents > 0,
                onClick = {
                    val value = cents ?: return@TextButton
                    val signed = if (isExpense) -value else value
                    val date = LocalDate.ofEpochDay(day)
                    scope.launch {
                        val recurringId = when {
                            repeat && template == null -> app.repository.saveRecurring(
                                RecurringEntity(
                                    name = description.trim().ifBlank { "Position" },
                                    amountCents = signed,
                                    interval = chosenInterval,
                                    // Dieser Termin steht schon hier; als Nächstes kommt der folgende.
                                    nextDue = chosenNext,
                                    accountId = settlements.firstOrNull { it.key == chosenSettlement }?.accountId ?: parent.accountId,
                                    categoryId = categoryId,
                                    groupId = groupId,
                                    parentKey = chosenSettlement,
                                ),
                            )
                            repeat && template != null -> {
                                val updated = template.copy(
                                    interval = chosenInterval,
                                    active = true,
                                    nextDue = chosenNext,
                                    parentKey = chosenSettlement,
                                    accountId = settlements.firstOrNull { it.key == chosenSettlement }?.accountId ?: template.accountId,
                                )
                                if (updated != template) app.repository.saveRecurring(updated)
                                template.id
                            }
                            // Abgewählt: das Spar-Abo läuft nicht mehr; bisher eingetragene Lieferungen bleiben.
                            template != null -> {
                                app.repository.saveRecurring(template.copy(active = false))
                                null
                            }
                            else -> null
                        }
                        app.repository.saveSplit(
                            split.copy(
                                recurringId = recurringId,
                                description = description.trim(),
                                amountCents = signed,
                                date = date,
                                categoryId = categoryId,
                                groupId = groupId,
                            ),
                        )
                        onDone()
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}

/** Platzhalter in den Auswahllisten, der eine neue Kategorie bzw. Gruppe anlegt. */
private const val NEW_ID = -1L
private const val NEW_CATEGORY = "Neue Kategorie …"
private const val NEW_GROUP = "Neue Gruppe …"

/** Rhythmen, in denen Spar-Abos liefern. */
private val POSITION_INTERVALS = listOf(
    RecurrenceInterval.MONTHLY,
    RecurrenceInterval.BIMONTHLY,
    RecurrenceInterval.QUARTERLY,
    RecurrenceInterval.FOUR_MONTHLY,
    RecurrenceInterval.FIVE_MONTHLY,
    RecurrenceInterval.HALF_YEARLY,
    RecurrenceInterval.YEARLY,
)

/** Eine Sammelabrechnung, über die wiederkehrende Positionen laufen können. */
data class SettlementOption(val key: String, val label: String, val accountId: Long)

/**
 * Abrechnungen zur Wahl für wiederkehrende Positionen: zuerst [current] und die, die schon Positionen haben
 * ([withPositions]), dann Kartenabrechnungen, danach weitere mehrfach vorkommende Abbuchungen nach Datum.
 */
fun settlementOptions(
    transactions: List<TransactionEntity>,
    current: String,
    withPositions: Set<String> = emptySet(),
): List<SettlementOption> {
    val all = transactions
        .filter { it.amountCents < 0 && it.id > 0 }
        .groupBy { RecurringDetector.matchKey(it.toTx()) }
        .filter { (key, txs) -> txs.size >= 2 || key == current }
        .map { (key, txs) ->
            val latest = txs.maxBy { it.bookingDate }
            val name = latest.counterparty.ifBlank { latest.purpose.take(40) }.ifBlank { "Ohne Buchungstext" }
            val rank = when {
                key == current -> 0
                key in withPositions -> 1
                CARD_SETTLEMENT.containsMatchIn("$name ${latest.purpose}") -> 2
                else -> 3
            }
            Triple(SettlementOption(key, "$name (zuletzt ${latest.bookingDate.let { if (it.year == LocalDate.now().year) it.dayMonthLabel() else it.shortLabel() }})", latest.accountId), rank, latest.bookingDate)
        }
        .sortedWith(compareBy<Triple<SettlementOption, Int, LocalDate>> { it.second }.thenByDescending { it.third })
    return all.take(40).map { it.first }
}

/** Woran eine Kreditkarten- oder Sammelabrechnung zu erkennen ist. */
private val CARD_SETTLEMENT = Regex(
    "kreditkart|visa|mastercard|american express|amex|zinia|barclaycard|hanseatic|advanzia|klarna|abrechnung",
    RegexOption.IGNORE_CASE,
)

/** Schlüssel der Abrechnungen, die schon Positionen oder wiederkehrende Positionen haben. */
fun settlementKeysInUse(transactions: List<TransactionEntity>, splitParents: Set<Long>, templates: List<RecurringEntity>): Set<String> =
    transactions.filter { it.id in splitParents }.map { RecurringDetector.matchKey(it.toTx()) }.toSet() +
        templates.filter { it.isPosition }.map { it.parentKey }

/** Eine Position eingerückt unter ihrem Umsatz in der Umsatzliste. */
@Composable
fun SplitRow(split: SplitEntity, categoryName: String, categoryColor: Color, group: Pair<String, Color>?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 42.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 10.dp).size(7.dp).clip(CircleShape).background(categoryColor))
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                split.description.ifBlank { "Ohne Beschreibung" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(split.date.dayMonthLabel(), categoryName, group?.first).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (split.recurringId != null) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = "Wiederkehrend",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 6.dp).size(14.dp),
            )
        }
        Text(
            Money.format(split.amountCents, withSign = true),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
