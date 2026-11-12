package net.jandev.financialanalyst.domain

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

/** Ein Monat der Kapitalprognose; Beträge mit Vorzeichen. */
data class CapitalMonth(
    val month: YearMonth,
    /** Feste Einnahmen laut Dauerposten (Gehalt samt Sonderzahlungen). */
    val incomeCents: Long,
    /** Fixkosten laut Dauerposten (negativ). */
    val fixedCents: Long,
    /** Hochgerechnete übrige Bewegungen: Alltagsausgaben, abzüglich übriger Einnahmen, falls eingerechnet. */
    val otherCents: Long,
    /** Dispozinsen auf den negativen Stand (negativ). */
    val interestCents: Long,
    /** Stand am Monatsende (im laufenden Monat ab heute gerechnet). */
    val endCents: Long,
) {
    val netCents: Long get() = incomeCents + fixedCents + otherCents + interestCents
}

data class CapitalProjection(
    val today: LocalDate,
    val startCents: Long,
    val months: List<CapitalMonth>,
    val lowestCents: Long,
    val lowestDate: LocalDate,
    /** Steigung der Monatsendstände (Regressionsgerade) in Cent pro Monat. */
    val trendPerMonthCents: Long,
    /**
     * Ab diesem Tag bleibt ein heute negativer Stand bis zum Ende der Prognose im Plus; ein kurzer Ausflug über null
     * am Gehaltstag zählt nicht. Sonst `null`.
     */
    val backToPlus: LocalDate?,
    /** Erster Tag, an dem ein heute positiver Stand unter 0 fällt; sonst `null`. */
    val firstNegative: LocalDate?,
    /** Erster Tag, an dem der Stand tiefer liegt als der Dispo-Rahmen; `null` ohne Rahmen oder wenn er reicht. */
    val beyondLimit: LocalDate? = null,
) {
    val endCents: Long get() = months.lastOrNull()?.endCents ?: startCents
}

/**
 * Rechnet das Kapital (Summe aller Konten) Tag für Tag in die Zukunft: feste Termine der Dauerposten an ihrem Tag,
 * dazu jeden Tag der Durchschnitt der übrigen Bewegungen, und auf einen negativen Stand Dispozinsen, die zum
 * Monatsende belastet werden.
 */
object CapitalForecast {
    /**
     * @param flows Termine der Dauerposten ab heute; überfällige mit dem Datum von heute
     * @param otherPerDayCents Tagesdurchschnitt der übrigen Bewegungen (mit Vorzeichen, meist negativ)
     * @param months Anzahl voller Monate nach dem laufenden
     */
    fun project(
        today: LocalDate,
        startCents: Long,
        flows: List<PlannedFlow>,
        otherPerDayCents: Long,
        months: Int,
        overdraftRatePercent: Double,
        overdraftLimitCents: Long = 0,
    ): CapitalProjection {
        val end = YearMonth.from(today).plusMonths(months.toLong()).atEndOfMonth()
        val byDay = flows.filter { it.date <= end }.groupBy { maxOf(it.date, today) }
        val result = ArrayList<CapitalMonth>()
        var balance = startCents
        var lowest = startCents
        var lowestDate = today
        var lastNegative: LocalDate? = if (startCents < 0) today else null
        var firstNegative: LocalDate? = null
        var beyondLimit: LocalDate? = null
        var income = 0L
        var fixed = 0L
        var other = 0L
        var interest = 0.0
        var day = today
        while (day <= end) {
            val todays = byDay[day].orEmpty()
            income += todays.filter { it.amountCents > 0 }.sumOf { it.amountCents }
            fixed += todays.filter { it.amountCents < 0 }.sumOf { it.amountCents }
            var change = todays.sumOf { it.amountCents }
            // Heute ist schon ausgegeben, was ausgegeben ist: der Durchschnitt zählt ab morgen.
            if (day > today) {
                change += otherPerDayCents
                other += otherPerDayCents
            }
            val before = balance
            balance += change
            interest += Overdraft.dailyInterest(balance, overdraftRatePercent)
            if (day == day.withDayOfMonth(day.lengthOfMonth())) {
                val charged = interest.roundToLong()
                balance -= charged
                result.add(CapitalMonth(YearMonth.from(day), income, fixed, other, -charged, balance))
                income = 0; fixed = 0; other = 0; interest = 0.0
            }
            if (balance < lowest) {
                lowest = balance
                lowestDate = day
            }
            if (balance < 0) lastNegative = day
            if (before >= 0 && balance < 0 && firstNegative == null && startCents >= 0) firstNegative = day
            if (overdraftLimitCents > 0 && balance < -overdraftLimitCents && beyondLimit == null) beyondLimit = day
            day = day.plusDays(1)
        }
        return CapitalProjection(
            today = today,
            startCents = startCents,
            months = result,
            lowestCents = lowest,
            lowestDate = lowestDate,
            trendPerMonthCents = slope(listOf(startCents) + result.map { it.endCents }),
            backToPlus = if (startCents < 0 && lastNegative != null && lastNegative < end) lastNegative.plusDays(1) else null,
            firstNegative = firstNegative,
            beyondLimit = beyondLimit,
        )
    }

    /** Steigung der Ausgleichsgeraden durch gleichmäßig verteilte Werte, gerundet. */
    fun slope(values: List<Long>): Long {
        if (values.size < 2) return 0
        val n = values.size
        val meanX = (n - 1) / 2.0
        val meanY = values.average()
        var numerator = 0.0
        var denominator = 0.0
        values.forEachIndexed { x, y ->
            numerator += (x - meanX) * (y - meanY)
            denominator += (x - meanX) * (x - meanX)
        }
        return (numerator / denominator).roundToLong()
    }

    /**
     * Tagesdurchschnitt von Bewegungen über die letzten [days] Tage bis einschließlich [today]; bei kürzerer Historie
     * über die bekannten Tage, ab [firstKnown].
     */
    fun perDay(movements: List<Pair<LocalDate, Long>>, today: LocalDate, days: Long, firstKnown: LocalDate?): Long {
        if (firstKnown == null) return 0
        val span = minOf(days, today.toEpochDay() - firstKnown.toEpochDay() + 1).coerceAtLeast(1)
        val from = today.minusDays(span)
        return (movements.filter { it.first > from && it.first <= today }.sumOf { it.second }.toDouble() / span).roundToLong()
    }
}
