package net.jandev.financialanalyst.ui.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.data.toTx
import net.jandev.financialanalyst.domain.Analytics
import net.jandev.financialanalyst.domain.CategoryTotal
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.Period
import net.jandev.financialanalyst.domain.Periods
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.BarPair
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.IncomeExpenseChart
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.ShareBar
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate
import androidx.compose.foundation.clickable
import kotlin.math.abs
import kotlin.math.roundToInt

private fun historyLength(period: Period) = when (period) {
    Period.DAY -> 14
    Period.WEEK -> 12
    Period.MONTH -> 12
    Period.QUARTER -> 8
    Period.YEAR -> 5
}

@Composable
fun AnalysisScreen(
    onOpenCategory: (categoryId: Long, from: LocalDate, to: LocalDate, expense: Boolean) -> Unit,
    onOpenMerchant: (key: String, from: LocalDate, to: LocalDate) -> Unit = { _, _, _ -> },
) {
    val app = LocalApp.current
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = null)
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    var period by rememberSaveable { mutableStateOf(Period.MONTH) }
    var anchorDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val anchor = LocalDate.ofEpochDay(anchorDay)
    val today = LocalDate.now()

    Screen(title = "Analyse") { padding ->
        val entities = transactions ?: return@Screen
        if (entities.isEmpty()) {
            EmptyState(
                title = "Noch nichts auszuwerten",
                hint = "Sobald Umsätze erfasst oder importiert sind, siehst du hier Einnahmen und Ausgaben nach Zeitraum und Kategorie.",
                modifier = Modifier.padding(padding),
            )
            return@Screen
        }
        val txs = remember(entities) { entities.map { it.toTx() } }
        val kinds = remember(categories) { categories.associate { it.id to it.kind } }
        val names = remember(categories) { categories.associate { it.id to it.name } }
        val kindOf = remember(kinds) { { id: Long? -> kinds[id] } }

        // Der Verlauf endet beim aktuellen Zeitraum, außer man blättert weiter zurück.
        val historyEnd = remember(period, anchor) {
            val count = historyLength(period)
            var end = today
            while (Periods.rangeOf(period, Periods.shift(period, end, -(count - 1).toLong())).start > anchor) {
                end = Periods.shift(period, end, -count.toLong())
            }
            end
        }
        val history = remember(txs, period, historyEnd, kindOf) {
            Analytics.history(txs, period, historyEnd, historyLength(period), kindOf)
        }
        val summary = remember(txs, period, anchor, kindOf) {
            Analytics.summarize(txs, Periods.rangeOf(period, anchor), kindOf)
        }
        val previous = remember(txs, period, anchor, kindOf) {
            Analytics.summarize(txs, Periods.rangeOf(period, Periods.shift(period, anchor, -1)), kindOf)
        }
        val selectedIndex = history.indexOfFirst { anchor in Periods.rangeOf(period, it.anchor) }
        val isCurrent = today in summary.range

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Period.entries.forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = period == item,
                        onClick = { period = item },
                        shape = SegmentedButtonDefaults.itemShape(index, Period.entries.size),
                        icon = {},
                    ) { Text(item.label, maxLines = 1) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { anchorDay = Periods.shift(period, anchor, -1).toEpochDay() }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Vorheriger Zeitraum")
                }
                Text(
                    Periods.label(period, anchor),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    enabled = !isCurrent && summary.range.endInclusive < today,
                    onClick = { anchorDay = Periods.shift(period, anchor, 1).toEpochDay() },
                ) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Nächster Zeitraum")
                }
            }

            SectionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Figure("Einnahmen", summary.incomeCents, Modifier.weight(1f))
                    Figure("Ausgaben", summary.expenseCents, Modifier.weight(1f))
                }
                LabeledRow("Saldo") {
                    AmountText(summary.netCents, negativeIsAlarming = true)
                }
                summary.savingsRate?.let { rate ->
                    LabeledRow("Sparquote") { Text("${(rate * 100).roundToInt()} %") }
                }
                if (previous.transactionCount > 0) {
                    val delta = summary.expenseCents - previous.expenseCents
                    LabeledRow(if (isCurrent) "Ausgaben ggü. Vorzeitraum (bisher)" else "Ausgaben ggü. Vorzeitraum") {
                        Text(
                            when {
                                delta == 0L -> "unverändert"
                                delta > 0 -> "${Money.format(delta)} mehr"
                                else -> "${Money.format(abs(delta))} weniger"
                            },
                            color = when {
                                delta > 0 -> LocalFinanceColors.current.expense
                                delta < 0 -> LocalFinanceColors.current.income
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                if (period != Period.DAY && summary.expenseCents > 0) {
                    val elapsed = if (isCurrent) today.toEpochDay() - summary.range.start.toEpochDay() + 1 else summary.range.days
                    LabeledRow("Ausgaben pro Tag (Ø)") {
                        AmountText(summary.expenseCents / elapsed, withSign = false, colored = false)
                    }
                }
            }

            SectionCard(title = "Verlauf") {
                IncomeExpenseChart(
                    bars = history.map { BarPair(it.label, it.incomeCents, it.expenseCents) },
                    selectedIndex = selectedIndex,
                    onSelect = { anchorDay = history[it].anchor.toEpochDay() },
                )
            }

            if (summary.expensesByCategory.isNotEmpty()) {
                SectionCard(title = "Ausgaben nach Kategorie") {
                    CategoryList(summary.expensesByCategory, summary.expenseCents, names, LocalFinanceColors.current.chartExpense) {
                        onOpenCategory(it ?: 0, summary.range.start, summary.range.endInclusive, true)
                    }
                }
            }
            if (summary.incomeByCategory.isNotEmpty()) {
                SectionCard(title = "Einnahmen nach Kategorie") {
                    CategoryList(summary.incomeByCategory, summary.incomeCents, names, LocalFinanceColors.current.chartIncome) {
                        onOpenCategory(it ?: 0, summary.range.start, summary.range.endInclusive, false)
                    }
                }
            }
            if (summary.topMerchants.isNotEmpty()) {
                SectionCard(title = "Größte Empfänger") {
                    summary.topMerchants.take(8).forEach { merchant ->
                        LabeledRow(
                            label = if (merchant.count > 1) "${merchant.name} (${merchant.count}×)" else merchant.name,
                            onClick = { onOpenMerchant(merchant.key, summary.range.start, summary.range.endInclusive) },
                        ) {
                            AmountText(merchant.cents, withSign = false, colored = false)
                            OpenHint()
                        }
                    }
                }
            }
            if (summary.transactionCount == 0) {
                Text(
                    "In diesem Zeitraum gibt es keine Umsätze.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Kleiner Pfeil am Zeilenende: die Zeile führt zu den einzelnen Umsätzen. */
@Composable
private fun OpenHint() {
    Icon(
        Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp).size(18.dp),
    )
}

@Composable
private fun Figure(label: String, cents: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(cents, style = MaterialTheme.typography.headlineSmall, withSign = false, colored = false)
    }
}

@Composable
private fun CategoryList(
    totals: List<CategoryTotal>,
    sumCents: Long,
    names: Map<Long, String>,
    color: androidx.compose.ui.graphics.Color,
    onOpen: (Long?) -> Unit,
) {
    val max = totals.maxOf { it.cents }.coerceAtLeast(1)
    totals.filter { it.cents > 0 }.forEach { total ->
        Column(
            modifier = Modifier.clickable(onClickLabel = "Umsätze anzeigen") { onOpen(total.categoryId) }.padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    names[total.categoryId] ?: "Ohne Kategorie",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                if (sumCents > 0) {
                    Text(
                        "${(total.cents * 100.0 / sumCents).roundToInt()} %",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                AmountText(total.cents, withSign = false, colored = false, style = MaterialTheme.typography.bodyMedium)
                OpenHint()
            }
            ShareBar(total.cents.toFloat() / max, color)
        }
    }
}
