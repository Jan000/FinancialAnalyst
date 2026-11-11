package net.jandev.financialanalyst.domain

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class DetectedRecurring(
    /** Händlerschlüssel plus Vorzeichen, z. B. "spotify|-"; daran wird der Posten wiedererkannt. */
    val matchKey: String,
    val name: String,
    /** Betrag der letzten Buchung. */
    val amountCents: Long,
    val interval: RecurrenceInterval,
    val lastDate: LocalDate,
    val nextDue: LocalDate,
    /** Betrag davor, falls er sich geändert hat. */
    val previousAmountCents: Long?,
    val occurrences: Int,
    val categoryId: Long?,
    val accountId: Long,
)

/** Erkennt regelmäßige Zahlungen (Miete, Abos, Gehalt) aus der Umsatzhistorie. */
object RecurringDetector {

    private data class IntervalClass(val interval: RecurrenceInterval, val minDays: Long, val maxDays: Long, val minOccurrences: Int)

    private val classes = listOf(
        IntervalClass(RecurrenceInterval.WEEKLY, 6, 8, 4),
        IntervalClass(RecurrenceInterval.BIWEEKLY, 13, 15, 4),
        IntervalClass(RecurrenceInterval.MONTHLY, 26, 34, 3),
        IntervalClass(RecurrenceInterval.QUARTERLY, 84, 98, 3),
        IntervalClass(RecurrenceInterval.HALF_YEARLY, 170, 196, 2),
        IntervalClass(RecurrenceInterval.YEARLY, 350, 380, 2),
    )

    fun matchKey(counterparty: String, amountCents: Long): String =
        "${Categorizer.merchantKey(counterparty)}|${if (amountCents < 0) "-" else "+"}"

    /** Schlüssel eines Umsatzes; ohne Händler zählt der Verwendungszweck, ohne jeden Text Konto und Betrag. */
    fun matchKey(tx: Tx): String =
        "${Categorizer.learnKey(tx.counterparty, tx.purpose, tx.accountId, tx.amountCents)}|${if (tx.amountCents < 0) "-" else "+"}"

    fun detect(transactions: List<Tx>, today: LocalDate): List<DetectedRecurring> {
        val result = ArrayList<DetectedRecurring>()
        val groups = transactions
            .filter { !it.isTransfer && it.amountCents != 0L }
            .groupBy { matchKey(it) }

        for ((key, group) in groups) {
            if (group.size < 2) continue
            val sorted = group.sortedBy { it.date }
            val whole = analyze(key, sorted, today)
            if (whole != null) {
                result.add(whole)
                continue
            }
            // Derselbe Händler mit Abo und Einzelkäufen (z. B. Amazon): nach Betrag getrennt erneut prüfen.
            val byAmount = clusterByAmount(sorted).filter { it.size >= 2 }
            byAmount.mapNotNull { analyze(key, it, today) }.maxByOrNull { it.occurrences }?.let(result::add)
        }
        return result.sortedBy { it.nextDue }
    }

    /**
     * Mindestens drei Buchungen in aufeinanderfolgenden Kalendermonaten, je Monat genau eine; höchstens ein Monat
     * darf fehlen. So wird eine Monatszahlung auch dann erkannt, wenn der Buchungstag stark schwankt.
     */
    private fun byCalendarMonth(dates: List<LocalDate>): Boolean {
        if (dates.size < 3) return false
        val months = dates.map { YearMonth.from(it) }
        if (months.distinct().size != months.size) return false
        val steps = months.zipWithNext { a, b -> ChronoUnit.MONTHS.between(a, b) }
        return steps.all { it in 1..2 } && steps.count { it == 2L } <= 1
    }

    private fun clusterByAmount(sorted: List<Tx>): List<List<Tx>> {
        val clusters = ArrayList<MutableList<Tx>>()
        for (tx in sorted) {
            val cluster = clusters.firstOrNull { c ->
                val reference = abs(c.last().amountCents)
                abs(abs(tx.amountCents) - reference) <= reference * 0.05
            }
            if (cluster != null) cluster.add(tx) else clusters.add(mutableListOf(tx))
        }
        return clusters
    }

    private fun analyze(key: String, sorted: List<Tx>, today: LocalDate): DetectedRecurring? {
        // Mehrere Buchungen am selben Tag (Teilzahlungen) zählen als ein Termin.
        val dates = sorted.map { it.date }.distinct()
        if (dates.size < 2) return null
        val gaps = dates.zipWithNext { a, b -> b.toEpochDay() - a.toEpochDay() }
        val median = gaps.sorted()[gaps.size / 2]
        val cls = classes.firstOrNull { median in it.minDays..it.maxDays }
        // Ein ausgelassener Termin (doppelter Abstand) ist erlaubt, sonst müssen die Abstände passen.
        val regular = cls != null && dates.size >= cls.minOccurrences &&
            gaps.count { it in cls.minDays..cls.maxDays || it in cls.minDays * 2..cls.maxDays * 2 } >= gaps.size * 0.75
        val interval: RecurrenceInterval
        val nextDue: LocalDate
        val graceDays: Long
        when {
            regular -> {
                interval = cls!!.interval
                nextDue = interval.next(sorted.last().date)
                graceDays = maxOf(10L, cls.maxDays / 2)
            }
            byCalendarMonth(dates) -> {
                // Miete & Co.: mal am Monatsanfang, mal mitten im Monat, aber jeden Monat genau einmal.
                interval = RecurrenceInterval.MONTHLY
                val day = dates.map { it.dayOfMonth }.sorted()[dates.size / 2]
                val month = YearMonth.from(dates.last()).plusMonths(1)
                nextDue = month.atDay(minOf(day, month.lengthOfMonth()))
                graceDays = 17
            }
            else -> return null
        }

        val amounts = sorted.map { abs(it.amountCents) }
        val medianAmount = amounts.sorted()[amounts.size / 2]
        val similar = amounts.count { abs(it - medianAmount) <= medianAmount * 0.25 }
        if (similar < amounts.size * 0.75) return null

        val last = sorted.last()
        // Seit mehr als einem halben Intervall überfällig: der Posten läuft vermutlich nicht mehr.
        if (nextDue.plusDays(graceDays) < today) return null

        val before = sorted.dropLast(1).lastOrNull { it.date != last.date }
        val previous = before?.amountCents?.takeIf { it != last.amountCents }
        return DetectedRecurring(
            matchKey = key,
            name = last.counterparty.trim().ifEmpty { last.purpose.trim() }.ifEmpty { "Zahlung ohne Buchungstext" },
            amountCents = last.amountCents,
            interval = interval,
            lastDate = last.date,
            nextDue = nextDue,
            previousAmountCents = previous,
            occurrences = dates.size,
            categoryId = sorted.mapNotNull { it.categoryId }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key,
            accountId = last.accountId,
        )
    }
}
