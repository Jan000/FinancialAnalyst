package net.jandev.financialanalyst.ui.overview

import net.jandev.financialanalyst.ui.transactions.TransactionEntry
import net.jandev.financialanalyst.ui.transactions.pairEntries
import net.jandev.financialanalyst.ui.transactions.rememberRowContext

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import net.jandev.financialanalyst.data.AccountForecast
import net.jandev.financialanalyst.data.BudgetCalculator
import net.jandev.financialanalyst.data.ForecastCalculator
import net.jandev.financialanalyst.data.Settings
import net.jandev.financialanalyst.domain.BudgetResult
import net.jandev.financialanalyst.domain.CategoryKind
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
            val forecasts = remember(today, list, transactions, recurring, categories) {
                val transferIds = categories.filter { it.kind == CategoryKind.TRANSFER }.map { it.id }.toSet()
                ForecastCalculator.compute(today, list, transactions, recurring, transferIds)
                    .filter { it.account.balanceCents != 0L || it.forecast.endBalanceCents != 0L }
            }
            if (forecasts.isNotEmpty()) {
                MonthEndCard(forecasts)
            }
            SectionCard(title = "Konten") {
                var showEmpty by rememberSaveable { mutableStateOf(false) }
                val empty = list.count { it.balanceCents == 0L }
                (if (showEmpty) list else list.filter { it.balanceCents != 0L }).forEach { item ->
                    LabeledRow(label = item.account.name) {
                        AmountText(
                            item.balanceCents,
                            withSign = false,
                            colored = item.balanceCents < 0,
                            negativeIsAlarming = true,
                        )
                    }
                }
                Row(modifier = Modifier.align(Alignment.End)) {
                    if (empty > 0) {
                        TextButton(onClick = { showEmpty = !showEmpty }) {
                            Text(if (showEmpty) "0-€-Konten ausblenden" else "$empty mit 0 € anzeigen")
                        }
                    }
                    TextButton(onClick = onOpenAccounts) { Text("Konten verwalten") }
                }
            }
            if (transactions.isNotEmpty()) {
                SectionCard(title = "Letzte Umsätze") {
                    // Dieselben Zeilen wie in der Umsatzliste: Kauf über PayPal als der Kauf, Umbuchung als eine Zeile.
                    val rowContext = rememberRowContext()
                    val recent = remember(transactions) { pairEntries(transactions.take(20)).take(5) }
                    Column(Modifier.padding(horizontal = 0.dp)) {
                        recent.forEachIndexed { index, entry ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            TransactionEntry(
                                entry = entry,
                                day = null,
                                ctx = rowContext,
                                onClick = { onEditTransaction(it.id) },
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

/** Je Konto der hochgerechnete Stand am Monatsende, mit Herleitung und den auflaufenden Dispozinsen. */
@Composable
private fun MonthEndCard(forecasts: List<AccountForecast>) {
    val finance = LocalFinanceColors.current
    val end = forecasts.first().forecast.monthEnd
    SectionCard(title = "Voraussichtlich am ${end.dayMonthLabel()}") {
        forecasts.forEachIndexed { index, item ->
            val f = item.forecast
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LabeledRow(item.account.account.name) {
                AmountText(f.endBalanceCents, withSign = false, colored = f.endBalanceCents < 0, negativeIsAlarming = true)
            }
            Text(
                buildList {
                    add("heute ${Money.format(item.account.balanceCents)}")
                    if (f.fixedExpenseCents != 0L) add("Fixkosten ${Money.format(f.fixedExpenseCents, withSign = true)}")
                    if (f.expectedIncomeCents != 0L) add("Einnahmen ${Money.format(f.expectedIncomeCents, withSign = true)}")
                    if (f.otherCents != 0L) add("Übriges ${Money.format(f.otherCents, withSign = true)}")
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                f.interestMonthCents > 0 -> Text(
                    "Dispozinsen in diesem Monat ca. ${Money.format(f.interestMonthCents)} (bis heute ${Money.format(f.interestSoFarCents)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = finance.expense,
                )
                f.lowestBalanceCents < 0 && item.account.account.overdraftRatePercent == 0.0 -> Text(
                    "Im Minus – trage beim Konto den Dispozins ein, um die Zinsen zu sehen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (f.lowestBalanceCents < 0 && f.lowestBalanceCents < f.endBalanceCents && f.lowestBalanceCents < item.account.balanceCents) {
                Text(
                    "Tiefster Stand ${Money.format(f.lowestBalanceCents)} am ${f.lowestDate.dayMonthLabel()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Fixkosten und Einnahmen stammen aus den Dauerposten und regelmäßigen Umbuchungen des Kontos. „Übriges“ ist der Tagesdurchschnitt aller " +
                "anderen Bewegungen der letzten 90 Tage, hochgerechnet auf die restlichen Tage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
