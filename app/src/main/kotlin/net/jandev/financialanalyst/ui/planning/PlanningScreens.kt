package net.jandev.financialanalyst.ui.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.Settings
import net.jandev.financialanalyst.data.db.BudgetEntity
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.SavingsGoalEntity
import net.jandev.financialanalyst.data.toTx
import net.jandev.financialanalyst.domain.Analytics
import net.jandev.financialanalyst.domain.Budget
import net.jandev.financialanalyst.domain.BudgetStatus
import net.jandev.financialanalyst.domain.CategoryBudgets
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.SavingsGoals
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.DateField
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.ShareBar
import net.jandev.financialanalyst.ui.common.dayMonthLabel
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.common.toFieldText
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate

/** Budgets je Kategorie für den laufenden Budgetzeitraum. */
@Composable
fun rememberBudgetStatus(today: LocalDate): List<BudgetStatus> {
    val app = LocalApp.current
    val budgets by app.repository.budgets.collectAsStateWithLifecycle(initialValue = emptyList())
    val transactions by app.repository.analysisTransactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    return remember(budgets, transactions, categories, settings, today) {
        if (budgets.isEmpty()) {
            emptyList()
        } else {
            val kinds = categories.associate { it.id to it.kind }
            val summary = Analytics.summarize(transactions.map { it.toTx() }, Budget.period(today, settings.budgetStartDay)) { kinds[it] }
            CategoryBudgets.status(budgets.associate { it.categoryId to it.monthlyLimitCents }, summary, today)
        }
    }
}

@Composable
fun BudgetsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val today = LocalDate.now()
    val status = rememberBudgetStatus(today)
    val period = Budget.period(today, settings.budgetStartDay)
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }

    val expenseCategories = categories.filter { it.kind == CategoryKind.EXPENSE }
    val byCategory = status.associateBy { it.categoryId }
    val withBudget = status.mapNotNull { s -> expenseCategories.firstOrNull { it.id == s.categoryId }?.let { it to s } }
    val without = expenseCategories.filter { it.id !in byCategory }

    Screen(title = "Budgets", onBack = onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Lege fest, wie viel du pro Budgetzeitraum höchstens je Kategorie ausgeben möchtest. " +
                        "Aktueller Zeitraum: ${period.start.dayMonthLabel()} bis ${period.endInclusive.dayMonthLabel()}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (withBudget.isNotEmpty()) {
                item {
                    SectionCard(title = "Deine Budgets") {
                        withBudget.forEach { (category, s) -> BudgetRow(category.name, s, onClick = { editing = category }) }
                    }
                }
            }
            item {
                SectionCard(title = if (withBudget.isEmpty()) "Kategorie wählen" else "Weitere Kategorien") {
                    without.forEach { category ->
                        Text(
                            category.name,
                            modifier = Modifier.fillMaxWidth().clickable { editing = category }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    editing?.let { category ->
        var limit by remember(category) { mutableStateOf(byCategory[category.id]?.limitCents?.toFieldText().orEmpty()) }
        val cents = Money.parse(limit)?.takeIf { it > 0 }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(category.name) },
            text = { MoneyField(value = limit, onValueChange = { limit = it }, label = "Budget pro Zeitraum") },
            confirmButton = {
                TextButton(
                    enabled = cents != null,
                    onClick = {
                        val value = cents ?: return@TextButton
                        scope.launch { app.repository.setBudget(BudgetEntity(category.id, value)) }
                        editing = null
                    },
                ) { Text("Speichern") }
            },
            dismissButton = {
                Row {
                    if (category.id in byCategory) {
                        TextButton(onClick = {
                            scope.launch { app.repository.removeBudget(category.id) }
                            editing = null
                        }) { Text("Entfernen") }
                    }
                    TextButton(onClick = { editing = null }) { Text("Abbrechen") }
                }
            },
        )
    }
}

@Composable
fun BudgetRow(name: String, status: BudgetStatus, onClick: (() -> Unit)? = null) {
    val finance = LocalFinanceColors.current
    val color = when {
        status.isOver -> finance.expense
        status.willExceed -> finance.warning
        else -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, modifier = Modifier.weight(1f))
            Text(
                "${Money.format(status.spentCents)} von ${Money.format(status.limitCents)}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ShareBar(status.fraction, color)
        Text(
            when {
                status.isOver -> "Um ${Money.format(-status.remainingCents)} überschritten"
                status.willExceed -> "Noch ${Money.format(status.remainingCents)} – bei diesem Tempo reicht es nicht bis zum Ende"
                else -> "Noch ${Money.format(status.remainingCents)} übrig"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status.isOver || status.willExceed) color else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun GoalsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val goals by app.repository.goals.collectAsStateWithLifecycle(initialValue = null)
    val today = LocalDate.now()
    var editing by remember { mutableStateOf<SavingsGoalEntity?>(null) }

    Screen(
        title = "Sparziele",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = SavingsGoalEntity(name = "", targetCents = 0) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Sparziel") },
            )
        },
    ) { padding ->
        val list = goals ?: return@Screen
        if (list.isEmpty()) {
            EmptyState(
                title = "Noch keine Sparziele",
                hint = "Lege fest, worauf du sparst. Die App rechnet aus, wie viel du dafür monatlich zurücklegen musst.",
                modifier = Modifier.padding(padding),
            )
            return@Screen
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(list, key = { it.id }) { goal ->
                val plan = SavingsGoals.plan(goal.targetCents, goal.savedCents, goal.targetDate, today)
                SectionCard(modifier = Modifier.clickable { editing = goal }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(goal.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        AmountText(goal.savedCents, withSign = false, colored = false)
                    }
                    ShareBar(plan.fraction, MaterialTheme.colorScheme.primary)
                    Text(
                        when {
                            plan.remainingCents == 0L -> "Geschafft – Ziel von ${Money.format(goal.targetCents)} erreicht"
                            plan.monthlyCents != null ->
                                "Noch ${Money.format(plan.remainingCents)} bis ${goal.targetDate?.shortLabel()}: ${Money.format(plan.monthlyCents!!)} pro Monat"
                            goal.targetDate != null -> "Noch ${Money.format(plan.remainingCents)} – das Zieldatum ist erreicht"
                            else -> "Noch ${Money.format(plan.remainingCents)} bis ${Money.format(goal.targetCents)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    editing?.let { goal ->
        var name by remember(goal) { mutableStateOf(goal.name) }
        var target by remember(goal) { mutableStateOf(goal.targetCents.takeIf { it > 0 }?.toFieldText().orEmpty()) }
        var saved by remember(goal) { mutableStateOf(goal.savedCents.takeIf { it > 0 }?.toFieldText().orEmpty()) }
        var hasDate by remember(goal) { mutableStateOf(goal.targetDate != null) }
        var date by remember(goal) { mutableStateOf(goal.targetDate ?: today.plusYears(1)) }
        val targetCents = Money.parse(target)?.takeIf { it > 0 }
        val savedCents = if (saved.isBlank()) 0L else Money.parse(saved)?.takeIf { it >= 0 }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(if (goal.id == 0L) "Neues Sparziel" else "Sparziel bearbeiten") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Wofür?") }, singleLine = true)
                    MoneyField(value = target, onValueChange = { target = it }, label = "Zielbetrag")
                    MoneyField(value = saved, onValueChange = { saved = it }, label = "Schon gespart")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Mit Zieldatum", modifier = Modifier.weight(1f))
                        Switch(checked = hasDate, onCheckedChange = { hasDate = it })
                    }
                    if (hasDate) DateField(date = date, onDateChange = { date = it }, label = "Bis wann?")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && targetCents != null && savedCents != null,
                    onClick = {
                        val updated = goal.copy(
                            name = name.trim(),
                            targetCents = targetCents ?: return@TextButton,
                            savedCents = savedCents ?: 0,
                            targetDate = if (hasDate) date else null,
                        )
                        scope.launch { app.repository.saveGoal(updated) }
                        editing = null
                    },
                ) { Text("Speichern") }
            },
            dismissButton = {
                Row {
                    if (goal.id != 0L) {
                        TextButton(onClick = {
                            scope.launch { app.repository.deleteGoal(goal) }
                            editing = null
                        }) { Text("Löschen") }
                    }
                    TextButton(onClick = { editing = null }) { Text("Abbrechen") }
                }
            },
        )
    }
}
