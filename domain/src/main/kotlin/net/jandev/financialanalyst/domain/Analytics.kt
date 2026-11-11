package net.jandev.financialanalyst.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

enum class Period(val label: String) {
    DAY("Tag"),
    WEEK("Woche"),
    MONTH("Monat"),
    QUARTER("Quartal"),
    YEAR("Jahr"),
}

data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date >= start && date <= endInclusive
    val days: Long get() = endInclusive.toEpochDay() - start.toEpochDay() + 1
}

object Periods {
    private val dayLabel = DateTimeFormatter.ofPattern("EEE, d. MMM yyyy", Locale.GERMAN)
    private val dayShort = DateTimeFormatter.ofPattern("d.M.", Locale.GERMAN)
    private val monthLabel = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)
    private val monthShort = DateTimeFormatter.ofPattern("MMM", Locale.GERMAN)

    fun rangeOf(period: Period, date: LocalDate): DateRange = when (period) {
        Period.DAY -> DateRange(date, date)
        Period.WEEK -> {
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            DateRange(monday, monday.plusDays(6))
        }
        Period.MONTH -> DateRange(date.withDayOfMonth(1), date.with(TemporalAdjusters.lastDayOfMonth()))
        Period.QUARTER -> {
            val first = date.withMonth((date.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1)
            DateRange(first, first.plusMonths(2).with(TemporalAdjusters.lastDayOfMonth()))
        }
        Period.YEAR -> DateRange(date.withDayOfYear(1), date.with(TemporalAdjusters.lastDayOfYear()))
    }

    /** Ein Datum im Zeitraum, der [steps] Zeiträume vor (negativ) oder nach [date] liegt. */
    fun shift(period: Period, date: LocalDate, steps: Long): LocalDate = when (period) {
        Period.DAY -> date.plusDays(steps)
        Period.WEEK -> date.plusWeeks(steps)
        Period.MONTH -> date.plusMonths(steps)
        Period.QUARTER -> date.plusMonths(3 * steps)
        Period.YEAR -> date.plusYears(steps)
    }

    fun label(period: Period, date: LocalDate): String = when (period) {
        Period.DAY -> date.format(dayLabel)
        Period.WEEK -> rangeOf(period, date).let { "${it.start.format(dayShort)} – ${it.endInclusive.format(dayShort)}${it.endInclusive.year}" }
        Period.MONTH -> date.format(monthLabel)
        Period.QUARTER -> "${(date.monthValue - 1) / 3 + 1}. Quartal ${date.year}"
        Period.YEAR -> date.year.toString()
    }

    /** Kurzform für Diagrammachsen. */
    fun shortLabel(period: Period, date: LocalDate): String = when (period) {
        Period.DAY -> date.format(dayShort)
        Period.WEEK -> rangeOf(period, date).start.format(dayShort)
        Period.MONTH -> date.format(monthShort).trimEnd('.')
        Period.QUARTER -> "Q${(date.monthValue - 1) / 3 + 1}"
        Period.YEAR -> date.year.toString()
    }
}

data class CategoryTotal(val categoryId: Long?, val cents: Long, val count: Int)

/** [key] ist der Händlerschlüssel, unter dem die Umsätze zusammengefasst sind ([Analytics.merchantKey]). */
data class MerchantTotal(val name: String, val cents: Long, val count: Int, val key: String = "")

data class PeriodSummary(
    val range: DateRange,
    /** Summe der Einnahmen, positiv. */
    val incomeCents: Long,
    /** Summe der Ausgaben, positiv. */
    val expenseCents: Long,
    /** Ausgaben je Kategorie, absteigend; Erstattungen sind bereits abgezogen. */
    val expensesByCategory: List<CategoryTotal>,
    val incomeByCategory: List<CategoryTotal>,
    /** Größte Ausgabenempfänger, absteigend. */
    val topMerchants: List<MerchantTotal>,
    val transactionCount: Int,
) {
    val netCents: Long get() = incomeCents - expenseCents

    /** Anteil der Einnahmen, der übrig bleibt; `null` ohne Einnahmen. */
    val savingsRate: Double? get() = if (incomeCents > 0) netCents.toDouble() / incomeCents else null
}

data class HistoryPoint(val anchor: LocalDate, val label: String, val incomeCents: Long, val expenseCents: Long)

object Analytics {

    /**
     * Wertet einen Zeitraum aus. Umbuchungen zwischen eigenen Konten bleiben außen vor.
     * [kindOf] liefert die Art einer Kategorie; ohne Kategorie entscheidet das Vorzeichen.
     */
    /** Schlüssel, unter dem die Auswertung Ausgaben an denselben Empfänger zusammenfasst. */
    fun merchantKey(counterparty: String): String = Categorizer.merchantKey(counterparty).ifBlank { "ohne bezeichnung" }

    /** Zählt der Umsatz in der Auswertung als Ausgabe an einen Empfänger? Dieselbe Regel wie in [summarize]. */
    fun isMerchantExpense(tx: Tx, kind: CategoryKind?): Boolean =
        !tx.isTransfer && kind != CategoryKind.TRANSFER && kind != CategoryKind.INCOME && tx.amountCents < 0

    fun summarize(transactions: List<Tx>, range: DateRange, kindOf: (Long?) -> CategoryKind?): PeriodSummary {
        val expenses = HashMap<Long?, LongArray>()
        val incomes = HashMap<Long?, LongArray>()
        val merchants = HashMap<String, Pair<String, LongArray>>()
        var count = 0

        for (tx in transactions) {
            if (tx.date !in range) continue
            val kind = kindOf(tx.categoryId)
            if (tx.isTransfer || kind == CategoryKind.TRANSFER) continue
            count++
            val isExpense = when (kind) {
                CategoryKind.EXPENSE -> true
                CategoryKind.INCOME -> false
                else -> tx.amountCents < 0
            }
            val bucket = (if (isExpense) expenses else incomes).getOrPut(tx.categoryId) { LongArray(2) }
            bucket[0] += if (isExpense) -tx.amountCents else tx.amountCents
            bucket[1]++
            if (isExpense && tx.amountCents < 0) {
                val entry = merchants.getOrPut(merchantKey(tx.counterparty)) { tx.counterparty.ifBlank { "Ohne Bezeichnung" } to LongArray(2) }
                entry.second[0] += -tx.amountCents
                entry.second[1]++
            }
        }

        fun totals(map: Map<Long?, LongArray>) = map
            .map { CategoryTotal(it.key, it.value[0], it.value[1].toInt()) }
            .sortedByDescending { it.cents }

        val expenseTotals = totals(expenses)
        val incomeTotals = totals(incomes)
        return PeriodSummary(
            range = range,
            incomeCents = incomeTotals.sumOf { it.cents },
            expenseCents = expenseTotals.sumOf { it.cents },
            expensesByCategory = expenseTotals,
            incomeByCategory = incomeTotals,
            topMerchants = merchants
                .map { (key, value) -> MerchantTotal(value.first, value.second[0], value.second[1].toInt(), key) }
                .sortedByDescending { it.cents },
            transactionCount = count,
        )
    }

    /** Einnahmen und Ausgaben der letzten [count] Zeiträume bis einschließlich dem um [anchor]. */
    fun history(
        transactions: List<Tx>,
        period: Period,
        anchor: LocalDate,
        count: Int,
        kindOf: (Long?) -> CategoryKind?,
    ): List<HistoryPoint> = (count - 1 downTo 0).map { back ->
        val date = Periods.shift(period, anchor, -back.toLong())
        val summary = summarize(transactions, Periods.rangeOf(period, date), kindOf)
        HistoryPoint(date, Periods.shortLabel(period, date), summary.incomeCents, summary.expenseCents)
    }
}
