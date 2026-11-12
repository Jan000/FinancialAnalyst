package net.jandev.financialanalyst.ui.capital

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.data.CapitalCalculator
import net.jandev.financialanalyst.data.CapitalResult
import net.jandev.financialanalyst.domain.CapitalMonth
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.NetWorth
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.common.toPercentText
import net.jandev.financialanalyst.ui.loans.loanEnds
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Zeitraum der Prognose in Monaten nach dem laufenden. */
private enum class Horizon(val label: String, val months: Int, val title: String) {
    HALF("6 Mon.", 6, "in 6 Monaten"),
    ONE("1 Jahr", 12, "in 1 Jahr"),
    TWO("2 Jahre", 24, "in 2 Jahren"),
    FIVE("5 Jahre", 60, "in 5 Jahren"),
}

private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)
private fun YearMonth.label(): String = format(monthYear)

/** Die Daten der Kapitalprognose, so wie Übersicht und Prognose-Seite sie brauchen. */
@Composable
internal fun rememberCapital(
    months: Int,
    windowDays: Long = 90,
    includeOtherIncome: Boolean = false,
    savingsPerMonthCents: Long = 0,
): CapitalResult? {
    val app = LocalApp.current
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = null)
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val recurring by app.repository.recurring.collectAsStateWithLifecycle(initialValue = emptyList())
    val splits by app.repository.splits.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val loans by app.repository.loans.collectAsStateWithLifecycle(initialValue = emptyList())
    val extras by app.repository.loanExtraPayments.collectAsStateWithLifecycle(initialValue = emptyList())
    val changes by app.repository.loanRateChanges.collectAsStateWithLifecycle(initialValue = emptyList())
    val list = accounts ?: return null
    val today = LocalDate.now()
    val ends = remember(loans, extras, changes, transactions, today) { loanEnds(loans, extras, changes, transactions, today) }
    return remember(list, transactions, recurring, splits, categories, ends, months, windowDays, includeOtherIncome, savingsPerMonthCents, today) {
        CapitalCalculator.compute(
            today = today,
            accounts = list,
            transactions = transactions,
            recurring = recurring,
            splits = splits,
            transferCategoryIds = categories.filter { it.kind == CategoryKind.TRANSFER }.map { it.id }.toSet(),
            loans = ends,
            months = months,
            windowDays = windowDays,
            includeOtherIncome = includeOtherIncome,
            savingsPerMonthCents = savingsPerMonthCents,
        )
    }
}

@Composable
fun CapitalScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    var horizon by rememberSaveable { mutableStateOf(Horizon.TWO) }
    var windowDays by rememberSaveable { mutableLongStateOf(90L) }
    var includeOtherIncome by rememberSaveable { mutableStateOf(false) }
    var savingsEuro by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable(horizon) { mutableIntStateOf(-1) }
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val result = rememberCapital(horizon.months, windowDays, includeOtherIncome, savingsEuro * 100L)
    val today = LocalDate.now()

    Screen(title = "Kapitalprognose", onBack = onBack) { padding ->
        val capital = result ?: return@Screen
        val projection = capital.projection
        // Bis zu zwölf bisherige Monate, soweit Umsätze da sind.
        val first = transactions.minOfOrNull { it.bookingDate }
        val pastMonths = if (first == null) 1 else (ChronoUnit.MONTHS.between(first.withDayOfMonth(1), today.withDayOfMonth(1)).toInt() + 1).coerceIn(1, 12)
        val history = remember(projection.startCents, transactions, today, pastMonths) {
            NetWorth.history(projection.startCents, transactions.map { it.bookingDate to it.amountCents }, today, pastMonths)
                .map { CapitalPoint(it.first, it.second) }
        }
        val future = projection.months.map { CapitalPoint(it.month.atEndOfMonth(), it.endCents) }
        val scenario = capital.scenario?.months?.map { CapitalPoint(it.month.atEndOfMonth(), it.endCents) }
        val points = history + future
        val chosen = if (selected in points.indices) selected else points.lastIndex

        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Horizon.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = horizon == option,
                        onClick = { horizon = option },
                        shape = SegmentedButtonDefaults.itemShape(index, Horizon.entries.size),
                    ) { Text(option.label, maxLines = 1) }
                }
            }
            HeadlineCard(capital, horizon)
            SectionCard(title = "Verlauf") {
                val point = points[chosen]
                val isFuture = chosen > history.lastIndex
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                chosen == history.lastIndex -> "Heute"
                                isFuture -> "Ende ${YearMonth.from(point.date).label()} · Prognose"
                                else -> "Ende ${YearMonth.from(point.date).label()}"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        scenario?.getOrNull(chosen - history.size)?.let { s ->
                            Text(
                                "mit Sparvorgabe ${Money.format(s.cents)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    AmountText(point.cents, style = MaterialTheme.typography.titleMedium, withSign = false, colored = point.cents < 0, negativeIsAlarming = true)
                }
                CapitalChart(
                    history = history,
                    projection = future,
                    scenario = scenario,
                    trendPerMonthCents = projection.trendPerMonthCents,
                    selectedIndex = chosen,
                    onSelect = { selected = it },
                    description = "Kapital heute ${Money.format(projection.startCents)}, ${horizon.title} voraussichtlich " +
                        "${Money.format(projection.endCents)}; Trend ${Money.format(projection.trendPerMonthCents, withSign = true)} pro Monat",
                )
                Legend(scenario != null)
                Text(
                    "Antippen oder darüberwischen zeigt den Stand am Ende jedes Monats.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AssumptionsCard(
                capital = capital,
                windowDays = windowDays,
                onWindowChange = { windowDays = it },
                includeOtherIncome = includeOtherIncome,
                onIncludeOtherIncomeChange = { includeOtherIncome = it },
            )
            ScenarioCard(capital, savingsEuro, horizon) { savingsEuro = it }
            TimelineCard(projection.months, horizon)
        }
    }
}

@Composable
private fun HeadlineCard(capital: CapitalResult, horizon: Horizon) {
    val p = capital.projection
    val finance = LocalFinanceColors.current
    val end = p.months.last().month.atEndOfMonth()
    SectionCard(title = "Kapital ${horizon.title}") {
        AmountText(p.endCents, style = MaterialTheme.typography.displaySmall, withSign = false, colored = p.endCents < 0, negativeIsAlarming = true)
        Text(
            "am ${end.shortLabel()} · ${Money.format(p.endCents - p.startCents, withSign = true)} gegenüber heute (${Money.format(p.startCents)})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val trend = p.trendPerMonthCents
        LabeledRow(if (trend >= 0) "Trend: steigend" else "Trend: fallend") {
            Text(
                "${if (trend >= 0) "▲" else "▼"} ${Money.format(trend, withSign = true)} / Monat",
                style = MaterialTheme.typography.bodyLarge,
                color = if (trend >= 0) finance.income else finance.expense,
            )
        }
        val yearly = p.trendPerMonthCents * 12
        Text(
            "Im Schnitt ${if (yearly >= 0) "wächst" else "schrumpft"} dein Kapital so um ${Money.format(kotlin.math.abs(yearly))} im Jahr.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        p.backToPlus?.let {
            Text("Wieder im Plus ab ${it.shortLabel()}", style = MaterialTheme.typography.bodyMedium, color = finance.income)
        }
        p.firstNegative?.let {
            Text("Rutscht am ${it.shortLabel()} ins Minus", style = MaterialTheme.typography.bodyMedium, color = finance.expense)
        }
        if (p.startCents < 0 && p.backToPlus == null && p.endCents < 0) {
            Text("Bleibt im ganzen Zeitraum überwiegend im Minus", style = MaterialTheme.typography.bodyMedium, color = finance.expense)
        }
        p.beyondLimit?.let {
            Text(
                "Ab ${it.shortLabel()} reicht der Dispo-Rahmen von ${Money.format(capital.overdraftLimitCents)} nicht mehr",
                style = MaterialTheme.typography.bodyMedium,
                color = finance.expense,
            )
        }
        if (p.lowestCents < p.startCents) {
            Text(
                "Tiefster Stand ${Money.format(p.lowestCents)} am ${p.lowestDate.shortLabel()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val interest = p.months.sumOf { it.interestCents }
        if (interest < 0) {
            Text(
                "Dispozinsen bis dahin ca. ${Money.format(-interest)}",
                style = MaterialTheme.typography.bodySmall,
                color = finance.expense,
            )
        }
    }
}

@Composable
private fun Legend(withScenario: Boolean) {
    val finance = LocalFinanceColors.current
    val items = buildList {
        add(Triple("Bisher", MaterialTheme.colorScheme.onSurfaceVariant, null))
        add(Triple("Prognose", MaterialTheme.colorScheme.primary, null))
        add(Triple("Trend", MaterialTheme.colorScheme.outline, floatArrayOf(1f, 4f)))
        if (withScenario) add(Triple("Gespart", finance.chart.getOrElse(2) { finance.chartIncome }, floatArrayOf(5f, 3f)))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (label, color, dash) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendLine(color, dash)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegendLine(color: Color, dash: FloatArray?) {
    Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
        val effect = dash?.let { PathEffect.dashPathEffect(floatArrayOf(it[0] * density, it[1] * density)) }
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.dp.toPx(), pathEffect = effect)
    }
}

@Composable
private fun AssumptionsCard(
    capital: CapitalResult,
    windowDays: Long,
    onWindowChange: (Long) -> Unit,
    includeOtherIncome: Boolean,
    onIncludeOtherIncomeChange: (Boolean) -> Unit,
) {
    val perMonth = { perDay: Long -> perDay * 365 / 12 }
    SectionCard(title = "So wird gerechnet") {
        LabeledRow("Feste Einnahmen Ø pro Monat") { AmountText(capital.fixedIncomeMonthlyCents) }
        Text(
            "Laut Dauerposten, mit Sonderzahlungen wie Urlaubs- und Weihnachtsgeld in ihrem Monat",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledRow("Fixkosten Ø pro Monat") { AmountText(-capital.fixedCostMonthlyCents) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LabeledRow("Übrige Ausgaben pro Tag") { AmountText(capital.otherExpensePerDayCents) }
        Text(
            "Alles, was kein Dauerposten und keine Umbuchung ist – Einkäufe, Tanken, Essen –, als Durchschnitt der letzten " +
                "${capital.windowDays} Tage; im Monat etwa ${Money.format(-perMonth(capital.otherExpensePerDayCents))}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Durchschnitt über", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val windows = listOf(30L, 90L, 180L, 365L)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            windows.forEachIndexed { index, days ->
                SegmentedButton(
                    selected = windowDays == days,
                    onClick = { onWindowChange(days) },
                    shape = SegmentedButtonDefaults.itemShape(index, windows.size),
                ) { Text(if (days == 365L) "1 Jahr" else "$days T.", maxLines = 1) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Übrige Einnahmen einrechnen", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Ø ${Money.format(capital.otherIncomePerDayCents, withSign = true)} pro Tag: Erstattungen, Verkäufe, Einmalzahlungen. " +
                        "Aus, weil sie selten wiederkommen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = includeOtherIncome, onCheckedChange = onIncludeOtherIncomeChange)
        }
        if (capital.overdraftRatePercent > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                "Steht das Kapital im Minus, kosten die ${capital.overdraftRatePercent.toPercentText()} % Dispozins mit; " +
                    "sie werden zum Monatsende abgezogen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        capital.endingLoans.forEach { (item, loan) ->
            Text(
                "${item.displayName} ist laut Tilgungsplan am ${loan.payoff.shortLabel()} abbezahlt; danach bleiben " +
                    "${Money.format(loan.monthlyPaymentCents)} im Monat mehr.",
                style = MaterialTheme.typography.bodySmall,
                color = LocalFinanceColors.current.income,
            )
        }
    }
}

@Composable
private fun ScenarioCard(capital: CapitalResult, savingsEuro: Int, horizon: Horizon, onChange: (Int) -> Unit) {
    SectionCard(title = "Was wäre, wenn …") {
        LabeledRow("… ich im Monat weniger ausgebe") {
            Text("${savingsEuro} €", style = MaterialTheme.typography.bodyLarge)
        }
        Slider(
            value = savingsEuro.toFloat(),
            onValueChange = { onChange((it / 25).toInt() * 25) },
            valueRange = 0f..600f,
            steps = 23,
        )
        val scenario = capital.scenario
        if (scenario != null) {
            Text(
                "Dann ${horizon.title} ${Money.format(scenario.endCents)} statt ${Money.format(capital.projection.endCents)} " +
                    "(${Money.format(scenario.endCents - capital.projection.endCents, withSign = true)}); Trend " +
                    "${Money.format(scenario.trendPerMonthCents, withSign = true)} pro Monat.",
                style = MaterialTheme.typography.bodyMedium,
            )
            scenario.backToPlus?.let {
                Text(
                    "Wieder im Plus ab ${it.shortLabel()}" + (capital.projection.backToPlus?.let { base -> " statt ${base.shortLabel()}" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalFinanceColors.current.income,
                )
            }
        } else {
            Text(
                "Schiebe den Regler, um zu sehen, was weniger Alltagsausgaben bewirken. Die Linie erscheint im Diagramm.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Monat für Monat, beim 5-Jahres-Zeitraum Jahr für Jahr; antippen zeigt die Zusammensetzung. */
@Composable
private fun TimelineCard(months: List<CapitalMonth>, horizon: Horizon) {
    val byYear = horizon == Horizon.FIVE
    val rows: List<Pair<String, List<CapitalMonth>>> = if (byYear) {
        months.groupBy { it.month.year }.map { (year, list) -> year.toString() to list }
    } else {
        months.map { it.month.label() to listOf(it) }
    }
    var open by rememberSaveable(horizon) { mutableStateOf<String?>(null) }
    SectionCard(title = if (byYear) "Jahr für Jahr" else "Monat für Monat", modifier = Modifier.animateContentSize()) {
        rows.forEachIndexed { index, (label, list) ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            val net = list.sumOf { it.netCents }
            val end = list.last().endCents
            Column(Modifier.fillMaxWidth().clickable { open = if (open == label) null else label }.padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(label.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${Money.format(net, withSign = true)} im ${if (byYear) "Jahr" else "Monat"}" +
                                if (list.first().month == YearMonth.now() && !byYear) " (ab heute)" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (net < 0) LocalFinanceColors.current.expense else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    AmountText(end, withSign = false, colored = end < 0, negativeIsAlarming = true)
                }
                if (open == label) {
                    Column(Modifier.padding(top = 4.dp, start = 12.dp)) {
                        LabeledRow("Feste Einnahmen") { AmountText(list.sumOf { it.incomeCents }, style = MaterialTheme.typography.bodyMedium) }
                        LabeledRow("Fixkosten") { AmountText(list.sumOf { it.fixedCents }, style = MaterialTheme.typography.bodyMedium) }
                        LabeledRow("Übrige Bewegungen") { AmountText(list.sumOf { it.otherCents }, style = MaterialTheme.typography.bodyMedium) }
                        val interest = list.sumOf { it.interestCents }
                        if (interest != 0L) LabeledRow("Dispozinsen") { AmountText(interest, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}
