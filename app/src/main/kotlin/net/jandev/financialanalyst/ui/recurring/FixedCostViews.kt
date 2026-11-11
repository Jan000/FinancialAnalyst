package net.jandev.financialanalyst.ui.recurring

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.RecurringEntity
import net.jandev.financialanalyst.data.db.RecurringIndex
import net.jandev.financialanalyst.data.db.SplitEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.Budget
import net.jandev.financialanalyst.domain.ContractSchedule
import net.jandev.financialanalyst.domain.CostArea
import net.jandev.financialanalyst.domain.FixedCostCalendar
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.RecurrenceInterval
import net.jandev.financialanalyst.domain.ServiceFamily
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.ChartSlice
import net.jandev.financialanalyst.ui.common.DonutChart
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.ShareBar
import net.jandev.financialanalyst.ui.common.StackedBar
import net.jandev.financialanalyst.ui.common.StackedBarChart
import net.jandev.financialanalyst.ui.common.TreemapChart
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Wie viele Monate die Vorschau zeigt: der laufende und die 24 folgenden. */
private const val FORECAST_MONTHS = 25

/** Ein Posten mit allem, was die Übersicht über ihn wissen muss. */
internal data class FixedItem(
    val item: RecurringEntity,
    val area: CostArea,
    val category: CategoryEntity?,
) {
    val yearlyCents: Long get() = item.interval.yearlyCents(abs(item.amountCents))
    val monthlyCents: Long get() = yearlyCents / 12
}

internal fun fixedItems(items: List<RecurringEntity>, categories: List<CategoryEntity>): List<FixedItem> {
    val byId = categories.associateBy { it.id }
    return items.map { item ->
        val category = item.categoryId?.let(byId::get)
        FixedItem(item, CostArea.of(category?.builtinKey, category?.name), category)
    }
}

/** Farbe eines Bereichs: feste Reihenfolge der Palette, „Sonstiges“ neutral. */
@Composable
internal fun CostArea.color(): Color =
    if (this == CostArea.OTHER) MaterialTheme.colorScheme.outline
    else LocalFinanceColors.current.chart[ordinal % LocalFinanceColors.current.chart.size]

@Composable
private fun CategoryEntity?.color(): Color =
    if (this == null) MaterialTheme.colorScheme.outline
    else LocalFinanceColors.current.chart[colorIndex.mod(LocalFinanceColors.current.chart.size)]

/** Eine Zahlung eines Postens: schon gebucht oder noch erwartet. [month] ist der Monat, in dem sie zählt. */
internal data class MonthEntry(val item: RecurringEntity, val date: LocalDate, val cents: Long, val booked: Boolean, val month: YearMonth)

/**
 * Zahlungen der Posten vom Anfang des laufenden Monats bis [until]: im laufenden Monat die schon gebuchten, danach
 * die geplanten. Offene Vertragsbeiträge zählen im laufenden Monat, tragen aber ihren eigentlichen Termin.
 */
internal fun fixedEntries(
    items: List<RecurringEntity>,
    transactions: List<TransactionEntity>,
    splits: List<SplitEntity>,
    today: LocalDate,
    until: LocalDate,
): List<MonthEntry> {
    if (items.isEmpty()) return emptyList()
    val start = today.withDayOfMonth(1)
    val current = YearMonth.from(today)
    val index = RecurringIndex(items)
    val booked = transactions
        .filter { it.bookingDate in start..today && it.transferPeerId == null }
        .mapNotNull { tx ->
            index.find(tx)?.takeIf { (it.amountCents < 0) == (tx.amountCents < 0) }
                ?.let { MonthEntry(it, tx.bookingDate, tx.amountCents, booked = true, month = current) }
        } +
        // Spar-Abos: die schon in eine Abrechnung eingetragenen Lieferungen.
        items.filter { it.isPosition }.flatMap { item ->
            splits.filter { it.recurringId == item.id && it.date in start..until }
                .map { MonthEntry(item, it.date, it.amountCents, booked = true, month = YearMonth.from(it.date)) }
        }
    val due = items.flatMap { item ->
        if (item.isContract) {
            ContractSchedule.openDues(item.plan(), until)
                .map { MonthEntry(item, it.date, it.amountCents, booked = false, month = YearMonth.from(maxOf(it.date, today))) }
        } else {
            Budget.plannedFlows(listOf(item.plan()), today, until)
                .map { MonthEntry(item, it.date, it.amountCents, booked = false, month = YearMonth.from(it.date)) }
        }
    }
    return (booked + due).sortedBy { it.date }
}

private val monthShort = DateTimeFormatter.ofPattern("MMM", Locale.GERMAN)
private val monthLong = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)

private fun YearMonth.shortName(): String = format(monthShort).trimEnd('.').take(3)

/* ---------------------------------------------------------------- Überblick */

@Composable
internal fun OverviewSummaryCard(expenses: List<FixedItem>, incomes: List<FixedItem>) {
    val yearlyOut = expenses.sumOf { it.yearlyCents }
    val yearlyIn = incomes.sumOf { it.yearlyCents }
    SectionCard(title = "Fixkosten gesamt") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure("pro Monat", yearlyOut / 12, Modifier.weight(1f))
            Figure("pro Quartal", yearlyOut / 4, Modifier.weight(1f))
            Figure("pro Jahr", yearlyOut, Modifier.weight(1f))
        }
        if (yearlyIn > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LabeledRow("Feste Einnahmen pro Monat") { AmountText(yearlyIn / 12, withSign = false, colored = false) }
            LabeledRow("Bleibt nach Fixkosten pro Monat") { AmountText((yearlyIn - yearlyOut) / 12, negativeIsAlarming = true) }
            val ratio = yearlyOut.toFloat() / yearlyIn
            Text(
                "Fixkosten binden ${(ratio * 100).roundToInt()} % deiner festen Einnahmen.",
                style = MaterialTheme.typography.bodyMedium,
            )
            ShareBar(ratio, if (ratio > 0.9f) LocalFinanceColors.current.warning else MaterialTheme.colorScheme.primary)
        }
        Text(
            "Seltenere Zahlungen sind als Durchschnitt auf Monat, Quartal und Jahr umgerechnet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Figure(label: String, cents: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AmountText(cents, style = MaterialTheme.typography.titleMedium, withSign = false, colored = false)
    }
}

private enum class Dimension(val label: String) { AREA("Bereiche"), CATEGORY("Kategorien"), ITEM("Posten") }

private enum class ChartKind(val label: String) { DONUT("Kreis"), BARS("Balken"), TILES("Kacheln") }

/** Eine Gruppe der Verteilung samt ihrer Posten. */
private data class Share(val slice: ChartSlice, val items: List<FixedItem>)

/** Höchstens so viele Anteile bekommen eine eigene Farbe; der Rest wird zu „Weitere“. */
private const val MAX_SLICES = 8

@Composable
internal fun DistributionCard(expenses: List<FixedItem>, onEdit: (Long) -> Unit) {
    var dimension by rememberSaveable { mutableStateOf(Dimension.AREA) }
    var kind by rememberSaveable { mutableStateOf(ChartKind.DONUT) }
    var selected by rememberSaveable(dimension) { mutableStateOf<String?>(null) }
    val other = MaterialTheme.colorScheme.outline
    val areaColors = CostArea.entries.associateWith { it.color() }
    val categoryColors = expenses.map { it.category }.distinct().associateWith { it.color() }
    val shares = remember(expenses, dimension, areaColors, categoryColors) {
        val groups: List<Share> = when (dimension) {
            Dimension.AREA -> expenses.groupBy { it.area }.map { (area, list) ->
                Share(ChartSlice(area.name, area.label, list.sumOf { it.yearlyCents } / 12, areaColors.getValue(area)), list)
            }
            Dimension.CATEGORY -> expenses.groupBy { it.category }.map { (category, list) ->
                Share(
                    ChartSlice("c${category?.id ?: 0}", category?.name ?: "Ohne Kategorie", list.sumOf { it.yearlyCents } / 12, categoryColors.getValue(category)),
                    list,
                )
            }
            // Posten tragen die Farbe ihres Bereichs.
            Dimension.ITEM -> expenses.map { Share(ChartSlice("i${it.item.id}", it.item.displayName, it.monthlyCents, areaColors.getValue(it.area)), listOf(it)) }
        }.sortedByDescending { it.slice.cents }
        if (groups.size <= MAX_SLICES) groups
        else {
            val rest = groups.drop(MAX_SLICES - 1)
            groups.take(MAX_SLICES - 1) + Share(
                ChartSlice("rest", "Weitere (${rest.size})", rest.flatMap { it.items }.sumOf { it.yearlyCents } / 12, other),
                rest.flatMap { it.items },
            )
        }
    }
    val total = expenses.sumOf { it.yearlyCents } / 12
    val chosen = shares.firstOrNull { it.slice.key == selected }

    SectionCard(title = "Wohin die Fixkosten gehen") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dimension.entries.forEach { option ->
                FilterChip(selected = dimension == option, onClick = { dimension = option }, label = { Text(option.label) })
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ChartKind.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = kind == option,
                    onClick = { kind = option },
                    shape = SegmentedButtonDefaults.itemShape(index, ChartKind.entries.size),
                ) { Text(option.label) }
            }
        }
        val slices = shares.map { it.slice }
        when (kind) {
            ChartKind.DONUT -> DonutChart(
                slices = slices,
                selectedKey = selected,
                onSelect = { selected = it },
                centerTitle = chosen?.slice?.label ?: "pro Monat",
                centerValue = Money.format(chosen?.slice?.cents ?: total),
            )
            ChartKind.TILES -> TreemapChart(slices = slices, selectedKey = selected, onSelect = { selected = it })
            ChartKind.BARS -> Unit
        }
        Text(
            if (kind == ChartKind.BARS) "Durchschnitt pro Monat · Anteil an allen Fixkosten" else "Antippen zeigt, was dahintersteckt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val largest = shares.maxOfOrNull { it.slice.cents }?.coerceAtLeast(1) ?: 1
        shares.forEach { share ->
            val isSelected = share.slice.key == selected
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { selected = if (isSelected) null else share.slice.key }
                    .background(if (isSelected) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .alpha(if (selected == null || isSelected) 1f else 0.6f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(share.slice.color))
                    Text(
                        share.slice.label,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp, end = 8.dp),
                    )
                    Text(
                        "${percent(share.slice.cents, total)} %",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(Money.format(share.slice.cents), style = MaterialTheme.typography.bodyMedium)
                }
                if (kind == ChartKind.BARS) ShareBar(share.slice.cents.toFloat() / largest, share.slice.color)
                if (isSelected) {
                    Text(
                        "${Money.format(share.items.sumOf { it.yearlyCents })} im Jahr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (dimension != Dimension.ITEM || share.slice.key == "rest") {
                        share.items.sortedByDescending { it.yearlyCents }.forEach { fixed ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onEdit(fixed.item.id) }.padding(start = 18.dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    fixed.item.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                                Text(Money.format(fixed.monthlyCents), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Text(
                            "Bearbeiten",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onEdit(share.items.first().item.id) }.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun percent(part: Long, total: Long): String =
    if (total <= 0) "0" else (part * 1000 / total).let { "${it / 10},${it % 10}" }

/** Warum ein Posten im Kündigungs-Check auffällt. */
/** [info]: nur ein Hinweis (etwa das Laufzeitende), macht den Posten allein nicht auffällig. */
private data class Flag(val text: String, val urgent: Boolean = false, val info: Boolean = false)

private data class Candidate(val fixed: FixedItem, val flags: List<Flag>)

/** Zahlungen, die man nicht kündigen kann – Steuern und Pflichtbeiträge. */
private val MANDATORY = Regex("steuer|rundfunk|beitragsservice|bundeskasse|finanzamt|\\bgez\\b", RegexOption.IGNORE_CASE)

private fun FixedItem.mandatory(): Boolean = MANDATORY.containsMatchIn("${item.name} ${item.alias}")

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CancelCheckCard(expenses: List<FixedItem>, today: LocalDate, onEdit: (Long) -> Unit) {
    val families = remember(expenses) {
        expenses.mapNotNull { fixed -> ServiceFamily.of(fixed.item.name, fixed.item.alias)?.let { it to fixed } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
    }
    val cancellable = remember(expenses) { expenses.filter { it.area.cancellable && !it.mandatory() } }
    val cancellableYearly = cancellable.sumOf { it.yearlyCents }
    val candidates = remember(cancellable, families, today) {
        cancellable.map { fixed ->
            val flags = ArrayList<Flag>()
            families.entries.firstOrNull { (_, list) -> fixed in list }?.let { (family, list) ->
                val others = list.filter { it != fixed }.joinToString { it.item.displayName }
                flags.add(Flag("Doppelt? ${family.label} auch bei $others"))
            }
            val previous = fixed.item.previousAmountCents
            if (previous != null && abs(fixed.item.amountCents) > abs(previous)) {
                flags.add(Flag("Teurer geworden: vorher ${Money.format(abs(previous))}", urgent = true))
            }
            fixed.item.contractEnd?.let { end ->
                val deadline = end.minusDays((fixed.item.noticeDays ?: 0).toLong())
                val left = ChronoUnit.DAYS.between(today, deadline)
                when {
                    left in 0..90 -> flags.add(Flag("Kündigen bis ${deadline.shortLabel()} (noch $left Tage), sonst verlängert er sich", urgent = true))
                    left > 90 -> flags.add(Flag("Kündbar zum ${end.shortLabel()}", info = true))
                }
            }
            if (cancellableYearly > 0 && fixed.yearlyCents * 100 / cancellableYearly >= 15) {
                flags.add(Flag("Großer Posten: ${fixed.yearlyCents * 100 / cancellableYearly} % der kündbaren Fixkosten"))
            }
            Candidate(fixed, flags)
        }.sortedWith(compareByDescending<Candidate> { c -> c.flags.any { it.urgent } }.thenByDescending { c -> c.flags.count { !it.info } }.thenByDescending { it.fixed.yearlyCents })
    }
    val flagged = candidates.filter { c -> c.flags.any { !it.info } }
    val switchable = expenses.filter { it.area.switchable && !it.mandatory() }.sortedByDescending { it.yearlyCents }
    val warning = LocalFinanceColors.current.warning

    SectionCard(title = "Kündigungs-Check") {
        Text(
            "Kündbar sind ${cancellable.size} Posten mit zusammen ${Money.format(cancellableYearly / 12)} im Monat – " +
                "${Money.format(cancellableYearly)} im Jahr.",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (flagged.isNotEmpty()) {
            Text("Auffällig", style = MaterialTheme.typography.titleSmall)
            flagged.forEach { candidate ->
                Column(
                    Modifier.fillMaxWidth().clickable { onEdit(candidate.fixed.item.id) }.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(candidate.fixed.area.color()))
                        Text(
                            candidate.fixed.item.displayName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${Money.format(candidate.fixed.yearlyCents)} / Jahr", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${Money.format(candidate.fixed.monthlyCents)} / Monat",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    candidate.flags.forEach { flag ->
                        Text(
                            (if (flag.urgent) "⚠ " else "• ") + flag.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (flag.urgent) warning else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 18.dp),
                        )
                    }
                }
            }
            Text(
                "Kündigst du alle auffälligen, sparst du bis zu ${Money.format(flagged.sumOf { it.fixed.yearlyCents })} im Jahr.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text("Alle kündbaren Posten nach Kosten", style = MaterialTheme.typography.titleSmall)
        val largest = cancellable.maxOfOrNull { it.yearlyCents }?.coerceAtLeast(1) ?: 1
        cancellable.sortedByDescending { it.yearlyCents }.forEach { fixed ->
            Column(Modifier.fillMaxWidth().clickable { onEdit(fixed.item.id) }.padding(vertical = 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        fixed.item.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Text("${Money.format(fixed.yearlyCents)} / Jahr", style = MaterialTheme.typography.bodyMedium)
                }
                ShareBar(fixed.yearlyCents.toFloat() / largest, fixed.area.color())
            }
        }
        if (switchable.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text("Eher Anbieter vergleichen als kündigen", style = MaterialTheme.typography.titleSmall)
            Text(
                "Energie und Versicherungen braucht man meist weiter, ein Wechsel kann trotzdem sparen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                switchable.forEach { fixed ->
                    Text(
                        "${fixed.item.displayName}: ${Money.format(fixed.yearlyCents)}/Jahr",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onEdit(fixed.item.id) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- Vorschau */

@Composable
internal fun ForecastTab(
    expenses: List<FixedItem>,
    incomes: List<FixedItem>,
    transactions: List<TransactionEntity>,
    splits: List<SplitEntity>,
    today: LocalDate,
    onEdit: (Long) -> Unit,
) {
    val first = YearMonth.from(today)
    val last = first.plusMonths((FORECAST_MONTHS - 1).toLong())
    val until = last.atEndOfMonth()
    val areaOf = remember(expenses) { expenses.associate { it.item.id to it.area } }
    val outEntries = remember(expenses, transactions, splits, today) { fixedEntries(expenses.map { it.item }, transactions, splits, today, until) }
    val inEntries = remember(incomes, transactions, today) { fixedEntries(incomes.map { it.item }, transactions, emptyList(), today, until) }
    val months = (0 until FORECAST_MONTHS).map { first.plusMonths(it.toLong()) }
    val totals = remember(outEntries) { FixedCostCalendar.monthlyTotals(outEntries.map { it.month.atDay(1) to it.cents }, first, FORECAST_MONTHS) }
    val incomeTotals = remember(inEntries) { FixedCostCalendar.monthlyTotals(inEntries.map { it.month.atDay(1) to it.cents }, first, FORECAST_MONTHS) }
    val areas = CostArea.entries.filter { area -> expenses.any { it.area == area } }
    val colors = areas.associateWith { it.color() }
    val bars = remember(outEntries, colors) {
        val byMonth = outEntries.groupBy { it.month }
        months.map { month ->
            val list = byMonth[month].orEmpty()
            StackedBar(
                label = if (month.monthValue == 1 || month == first) "${month.shortName()} ${month.year % 100}" else month.shortName(),
                segments = areas.map { area -> colors.getValue(area) to list.filter { areaOf[it.item.id] == area }.sumOf { abs(it.cents) } },
            )
        }
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val month = months[selected.coerceIn(months.indices)]
    // Durchschnitt über die vollen kommenden 24 Monate, ohne den angebrochenen laufenden.
    val average = totals.drop(1).average().toLong()
    val maxIndex = totals.indices.maxByOrNull { totals[it] } ?: 0
    val minIndex = totals.indices.drop(1).minByOrNull { totals[it] } ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    SectionCard(title = "Fixkosten der nächsten 2 Jahre") {
        Text(
            "Jeder Monat auf den Cent, mit allen Terminen laut Rhythmus und Vertrag. Antippen wählt einen Monat; die gestrichelte Linie ist der Durchschnitt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StackedBarChart(bars = bars, selectedIndex = selected, onSelect = { selected = it }, referenceCents = average)
        LegendRow(areas.map { it.label to colors.getValue(it) })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Figure("Ø pro Monat", average, Modifier.weight(1f))
            Column(Modifier.weight(1f).clickable { selected = maxIndex }) {
                Text("teuerster Monat", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${months[maxIndex].shortName()} ${months[maxIndex].year}", style = MaterialTheme.typography.titleMedium)
                Text(Money.format(totals[maxIndex]), style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f).clickable { selected = minIndex }) {
                Text("günstigster", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${months[minIndex].shortName()} ${months[minIndex].year}", style = MaterialTheme.typography.titleMedium)
                Text(Money.format(totals[minIndex]), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    MonthDetailCard(
        month = month,
        isCurrent = month == first,
        entries = outEntries.filter { it.month == month },
        incomeCents = incomeTotals[selected.coerceIn(months.indices)],
        averageCents = average,
        areaOf = areaOf,
        today = today,
        onPrevious = { if (selected > 0) selected-- },
        onNext = { if (selected < months.lastIndex) selected++ },
        onEdit = onEdit,
    )

    SectionCard(title = "Nach Jahren") {
        months.indices.groupBy { months[it].year }.forEach { (year, indices) ->
            val sum = indices.sumOf { totals[it] }
            val income = indices.sumOf { incomeTotals[it] }
            val range = if (indices.size == 12) "ganzes Jahr" else "${months[indices.first()].shortName()}–${months[indices.last()].shortName()}, ${indices.size} Monate"
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("$year", style = MaterialTheme.typography.bodyLarge)
                    Text(range, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(Money.format(sum), style = MaterialTheme.typography.bodyLarge)
                    if (income > 0) {
                        Text(
                            "bleibt ${Money.format(income - sum, withSign = true)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            "Feste Einnahmen sind nur die als Dauerposten erfassten, etwa das Gehalt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegendRow(entries: List<Pair<String, Color>>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@Composable
private fun MonthDetailCard(
    month: YearMonth,
    isCurrent: Boolean,
    entries: List<MonthEntry>,
    incomeCents: Long,
    averageCents: Long,
    areaOf: Map<Long, CostArea>,
    today: LocalDate,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val total = entries.sumOf { abs(it.cents) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable(onClick = onPrevious).padding(horizontal = 12.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(month.format(monthLong).replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.titleMedium)
                Text(Money.format(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                val diff = total - averageCents
                Text(
                    "${entries.size} Zahlungen · ${Money.format(abs(diff))} ${if (diff >= 0) "über" else "unter"} dem Durchschnitt",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable(onClick = onNext).padding(horizontal = 12.dp))
        }
        if (isCurrent) {
            val booked = entries.filter { it.booked }.sumOf { abs(it.cents) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Figure("schon gebucht", booked, Modifier.weight(1f))
                Figure("noch fällig", total - booked, Modifier.weight(1f))
            }
        }
        if (incomeCents > 0) {
            LabeledRow("Feste Einnahmen") { AmountText(incomeCents, withSign = false, colored = false) }
            LabeledRow("Bleibt nach Fixkosten") { AmountText(incomeCents - total, negativeIsAlarming = true) }
        }
        val rare = entries.filter { it.item.interval.months > 1 }.distinctBy { it.item.id }
        if (rare.isNotEmpty()) {
            Text(
                "Zusätzlich dran: " + rare.joinToString { "${it.item.displayName} (${it.item.interval.label})" },
                style = MaterialTheme.typography.bodySmall,
                color = LocalFinanceColors.current.warning,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (entries.isEmpty()) Text("In diesem Monat ist nichts fällig.", style = MaterialTheme.typography.bodyMedium)
        entries.forEach { entry ->
            Row(
                Modifier.fillMaxWidth().clickable { onEdit(entry.item.id) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background((areaOf[entry.item.id] ?: CostArea.OTHER).color()))
                Text(
                    entry.date.format(DateTimeFormatter.ofPattern("dd.MM.")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp).width(48.dp),
                )
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(entry.item.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val state = listOfNotNull(
                        when {
                            entry.booked -> "gebucht"
                            entry.date < today -> "offen seit ${entry.date.shortLabel()}"
                            else -> null
                        },
                        "über Kartenabrechnung".takeIf { entry.item.isPosition },
                        entry.item.interval.label.takeIf { entry.item.interval != RecurrenceInterval.MONTHLY },
                    )
                    if (state.isNotEmpty()) {
                        Text(
                            state.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (entry.date < today && !entry.booked) LocalFinanceColors.current.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AmountText(entry.cents, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.alpha(if (entry.booked) 0.6f else 1f))
            }
        }
    }
}
