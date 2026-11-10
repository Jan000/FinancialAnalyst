package net.jandev.financialanalyst.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.data.BudgetCalculator
import net.jandev.financialanalyst.data.Settings
import net.jandev.financialanalyst.domain.BudgetResult
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.NetWorth
import net.jandev.financialanalyst.ui.common.TrendLine
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.planning.BudgetRow
import net.jandev.financialanalyst.ui.planning.rememberBudgetStatus
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.dayMonthLabel
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import androidx.compose.foundation.layout.Row
import java.time.LocalDate
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.transactions.TransactionRow

@Composable
fun OverviewScreen(
    onOpenAccounts: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenTransactions: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBudgets: () -> Unit,
) {
    val app = LocalApp.current
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = null)
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val recurring by app.repository.recurring.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val today = LocalDate.now()

    Screen(title = "Übersicht") { padding ->
        val list = accounts ?: return@Screen
        if (list.isEmpty()) {
            EmptyState(
                title = "Willkommen beim Finanzanalysten",
                hint = "Lege dein erstes Konto an, um Einnahmen und Ausgaben auszuwerten.",
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = onAddAccount) { Text("Konto anlegen") }
            }
            return@Screen
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val budget = remember(today, settings, list, transactions, recurring) {
                BudgetCalculator.compute(today, settings, list, transactions, recurring)
            }
            if (budget != null) {
                BudgetCard(budget, today, onOpenRecurring, onOpenSettings)
            }
            val categoryBudgets = rememberBudgetStatus(today).filter { it.isOver || it.willExceed }
            if (categoryBudgets.isNotEmpty()) {
                SectionCard(title = "Budgets im Blick behalten") {
                    categoryBudgets.take(3).forEach { status ->
                        BudgetRow(categoryNames[status.categoryId] ?: "Kategorie", status, onClick = onOpenBudgets)
                    }
                }
            }
            val total = list.sumOf { it.balanceCents }
            SectionCard(title = "Gesamtvermögen") {
                AmountText(
                    total,
                    style = MaterialTheme.typography.displaySmall,
                    withSign = false,
                    colored = total < 0,
                    negativeIsAlarming = true,
                )
                // Der Verlauf beginnt mit dem ersten erfassten Monat: davor kennt die App den Stand nicht.
                val first = transactions.minOfOrNull { it.bookingDate }
                val months = if (first == null) 0 else {
                    (java.time.temporal.ChronoUnit.MONTHS.between(first.withDayOfMonth(1), today.withDayOfMonth(1)).toInt() + 1).coerceAtMost(12)
                }
                val history = remember(total, transactions, today, months) {
                    NetWorth.history(total, transactions.map { it.bookingDate to it.amountCents }, today, months.coerceAtLeast(1))
                }
                // Erst zeigen, wenn es über mindestens drei Monate etwas zu sehen gibt.
                if (months >= 3) {
                    val change = total - history.first().second
                    Text(
                        "${Money.format(change, withSign = true)} seit ${history.first().first.shortLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TrendLine(
                        points = history.map { it.second },
                        startLabel = history.first().first.shortLabel(),
                        endLabel = "heute",
                    )
                }
            }
            SectionCard(title = "Konten") {
                list.forEach { item ->
                    LabeledRow(label = item.account.name) {
                        AmountText(
                            item.balanceCents,
                            withSign = false,
                            colored = item.balanceCents < 0,
                            negativeIsAlarming = true,
                        )
                    }
                }
                TextButton(onClick = onOpenAccounts, modifier = Modifier.align(Alignment.End)) {
                    Text("Konten verwalten")
                }
            }
            if (transactions.isNotEmpty()) {
                SectionCard(title = "Letzte Umsätze") {
                    Column(Modifier.padding(horizontal = 0.dp)) {
                        transactions.take(5).forEachIndexed { index, tx ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            TransactionRow(
                                tx = tx,
                                subtitle = categoryNames[tx.categoryId] ?: "Ohne Kategorie",
                                onClick = { onEditTransaction(tx.id) },
                                horizontalPadding = 0.dp,
                            )
                        }
                    }
                    TextButton(onClick = onOpenTransactions, modifier = Modifier.align(Alignment.End)) {
                        Text("Alle Umsätze")
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetCard(budget: BudgetResult, today: LocalDate, onOpenRecurring: () -> Unit, onOpenSettings: () -> Unit) {
    val finance = LocalFinanceColors.current
    SectionCard(title = "Heute noch verfügbar") {
        AmountText(
            budget.todayRemainingCents,
            style = MaterialTheme.typography.displaySmall,
            withSign = false,
            colored = budget.todayRemainingCents < 0,
            negativeIsAlarming = true,
        )
        Text(
            buildString {
                append("Tagesbudget ${Money.format(budget.perDayCents)}")
                if (budget.spentTodayCents > 0) append(" · heute schon ${Money.format(budget.spentTodayCents)} ausgegeben")
                if (budget.spentTodayCents < 0) append(" · heute ${Money.format(-budget.spentTodayCents)} dazubekommen")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val limit = budget.limitedUntil
        if (limit != null) {
            // Vor dem Periodenende kommt noch Geld: ehrlich ist dann nur der Betrag, der bis dahin reichen muss.
            val days = limit.toEpochDay() - today.toEpochDay()
            LabeledRow("Frei bis zum Geldeingang am ${limit.dayMonthLabel()} ($days Tage)") {
                AmountText(budget.perDayCents * days, withSign = false, colored = budget.perDayCents < 0, negativeIsAlarming = true)
            }
        } else {
            LabeledRow("Frei bis ${budget.periodEnd.dayMonthLabel()} (${budget.daysLeft} Tage)") {
                AmountText(budget.restOfPeriodCents, withSign = false, colored = budget.restOfPeriodCents < 0, negativeIsAlarming = true)
            }
            if (budget.upcomingIncomeCents > 0) {
                LabeledRow("Eingerechnet: erwartete Einnahmen") {
                    AmountText(budget.upcomingIncomeCents)
                }
            }
        }
        if (budget.upcomingExpenseCents > 0) {
            LabeledRow("Schon abgezogen: Fixkosten bis ${budget.periodEnd.dayMonthLabel()}") {
                AmountText(-budget.upcomingExpenseCents, colored = false)
            }
        }
        if (budget.lowestBalanceCents < 0) {
            Text(
                "Achtung: Am ${budget.lowestBalanceDate.dayMonthLabel()} reicht der Kontostand voraussichtlich nicht für die Fixkosten " +
                    "(es fehlen ${Money.format(-budget.lowestBalanceCents)}).",
                style = MaterialTheme.typography.bodyMedium,
                color = finance.expense,
            )
        }
        val next = budget.flows.filter { it.amountCents < 0 }.take(4)
        if (next.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Als Nächstes fällig", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            next.forEach { flow ->
                LabeledRow("${if (flow.date == today) "Heute" else flow.date.dayMonthLabel()} · ${flow.name}") {
                    AmountText(flow.amountCents, colored = false)
                }
            }
        }
        Row(modifier = Modifier.align(Alignment.End)) {
            TextButton(onClick = onOpenSettings) { Text("Budget einstellen") }
            TextButton(onClick = onOpenRecurring) { Text("Fixkosten") }
        }
    }
}
