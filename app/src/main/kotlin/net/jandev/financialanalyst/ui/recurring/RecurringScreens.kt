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
import androidx.compose.material3.ExtendedFloatingActionButton
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
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.RecurrenceInterval
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.DateField
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.EmptyState
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

@Composable
fun RecurringScreen(onEdit: (Long) -> Unit) {
    val app = LocalApp.current
    val items by app.repository.recurring.collectAsStateWithLifecycle(initialValue = null)
    val today = LocalDate.now()

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
        val active = visible.filter { it.active }
        val expenses = active.filter { it.amountCents < 0 }.sortedBy { it.nextDue }
        val incomes = active.filter { it.amountCents > 0 }.sortedBy { it.nextDue }
        val paused = visible.filter { !it.active }
        val monthlyOut = expenses.sumOf { it.interval.monthlyCents(-it.amountCents) }
        val monthlyIn = incomes.sumOf { it.interval.monthlyCents(it.amountCents) }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Fixkosten pro Monat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AmountText(monthlyOut, style = MaterialTheme.typography.headlineSmall, withSign = false, colored = false)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Feste Einnahmen pro Monat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            AmountText(monthlyIn, style = MaterialTheme.typography.headlineSmall, withSign = false, colored = false)
                        }
                    }
                    Text(
                        "Pro Jahr gehen ${Money.format(monthlyOut * 12)} fest ab. Seltenere Zahlungen sind auf den Monat umgerechnet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (expenses.isNotEmpty()) {
                item { RecurringGroup("Ausgaben", expenses, today, onEdit) }
            }
            if (incomes.isNotEmpty()) {
                item { RecurringGroup("Einnahmen", incomes, today, onEdit) }
            }
            if (paused.isNotEmpty()) {
                item { RecurringGroup("Pausiert oder beendet", paused, today, onEdit) }
            }
        }
    }
}

@Composable
private fun RecurringGroup(title: String, items: List<RecurringEntity>, today: LocalDate, onEdit: (Long) -> Unit) {
    SectionCard(title = title) {
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            RecurringRow(item, today, onClick = { onEdit(item.id) })
        }
    }
}

@Composable
fun RecurringRow(item: RecurringEntity, today: LocalDate, onClick: (() -> Unit)? = null) {
    val days = item.nextDue.toEpochDay() - today.toEpochDay()
    val due = when {
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
                Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${item.interval.label} · $due",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AmountText(item.amountCents)
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
fun RecurringEditScreen(itemId: Long, onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())

    var loaded by remember { mutableStateOf<RecurringEntity?>(null) }
    var ready by rememberSaveable { mutableStateOf(itemId == 0L) }
    var name by rememberSaveable { mutableStateOf("") }
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
            if (loaded?.detected == true) {
                Text(
                    "Automatisch aus deinen Umsätzen erkannt. Betrag und Termin werden mit jeder neuen Buchung fortgeschrieben.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = isExpense, onClick = { isExpense = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Ausgabe") }
                SegmentedButton(selected = !isExpense, onClick = { isExpense = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Einnahme") }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Bezeichnung") },
                supportingText = { Text("Am besten so, wie der Empfänger im Kontoauszug heißt") },
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
                    val item = base.copy(
                        name = name.trim(),
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
            text = "„$name“ zählt dann nicht mehr als feste Zahlung. Die Umsätze selbst bleiben erhalten.",
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
