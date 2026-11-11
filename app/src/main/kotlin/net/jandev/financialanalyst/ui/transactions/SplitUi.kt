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
                    color = if ((rest < 0) != (parent.amountCents < 0) && rest != 0L) LocalFinanceColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                if (rest != 0L) AmountText(rest, style = MaterialTheme.typography.bodyMedium, colored = false)
            }
            if ((rest < 0) != (parent.amountCents < 0) && rest != 0L) {
                Text(
                    "Die Positionen sind zusammen größer als der Umsatz.",
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
                    // Vorschlag: der noch offene Rest.
                    amountCents = rest.takeIf { (it < 0) == (parent.amountCents < 0) && it != 0L } ?: 0,
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
    val isExpense = parent.amountCents < 0
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
    val cents = Money.parse(amount)?.let(::abs)
    val selectable = remember(categories) {
        categories.filter { it.kind == (if (isExpense) CategoryKind.EXPENSE else CategoryKind.INCOME) }
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (split.id == 0L) "Neue Position" else "Position bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    options = selectable,
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
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { repeat = !repeat },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = repeat, onCheckedChange = { repeat = it })
                    Column {
                        Text("Wiederholt sich (z. B. Spar-Abo)", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Künftige Lieferungen werden automatisch in die jeweils nächste Abrechnung eingetragen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (repeat) {
                    DropdownField(
                        label = "Liefert",
                        options = POSITION_INTERVALS,
                        selected = chosenInterval,
                        onSelect = { interval = it },
                        optionLabel = { if (it == RecurrenceInterval.MONTHLY) "Jeden Monat" else it.label.replaceFirstChar(Char::uppercase) },
                    )
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
                                    // Diese Lieferung steht schon hier; als Nächstes kommt die folgende.
                                    nextDue = chosenInterval.next(date),
                                    accountId = parent.accountId,
                                    categoryId = categoryId,
                                    groupId = groupId,
                                    parentKey = RecurringDetector.matchKey(parent.toTx()),
                                ),
                            )
                            repeat && template != null -> {
                                if (template.interval != chosenInterval || !template.active) {
                                    app.repository.saveRecurring(template.copy(interval = chosenInterval, active = true))
                                }
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

/** Rhythmen, in denen Spar-Abos liefern. */
private val POSITION_INTERVALS = listOf(
    RecurrenceInterval.MONTHLY,
    RecurrenceInterval.BIMONTHLY,
    RecurrenceInterval.QUARTERLY,
    RecurrenceInterval.FOUR_MONTHLY,
    RecurrenceInterval.FIVE_MONTHLY,
    RecurrenceInterval.HALF_YEARLY,
)

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
