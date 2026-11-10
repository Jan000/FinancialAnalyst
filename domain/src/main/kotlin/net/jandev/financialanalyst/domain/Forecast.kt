package net.jandev.financialanalyst.domain

import java.time.LocalDate
import kotlin.math.roundToLong

data class MonthEndForecast(
    val monthEnd: LocalDate,
    /** Voraussichtlicher Kontostand am Monatsende. */
    val endBalanceCents: Long,
    /** Noch fällige Fixkosten bis dahin (negativ). */
    val fixedExpenseCents: Long,
    /** Noch erwartete feste Einnahmen bis dahin. */
    val expectedIncomeCents: Long,
    /** Hochgerechnete übrige Bewegungen bis dahin (mit Vorzeichen). */
    val otherCents: Long,
    val otherPerDayCents: Long,
    /** Im laufenden Monat bis heute aufgelaufene Dispozinsen. */
    val interestSoFarCents: Long,
    /** Dispozinsen für den ganzen Monat, wenn es so weiterläuft. */
    val interestMonthCents: Long,
    val lowestBalanceCents: Long,
    val lowestDate: LocalDate,
)

/** Rechnet den Kontostand eines Kontos bis zum Monatsende hoch, samt der auflaufenden Dispozinsen. */
object MonthEnd {
    const val TREND_DAYS = 90L
    private const val MIN_TREND_DAYS = 14L

    /**
     * Durchschnitt der Bewegungen pro Tag, die keine Dauerposten sind (Einkäufe, Umbuchungen, Einzelzahlungen),
     * über die letzten [TREND_DAYS] Tage. Bei zu kurzer Historie 0, statt aus wenigen Tagen hochzurechnen.
     */
    fun otherPerDay(today: LocalDate, movements: List<Pair<LocalDate, Long>>, firstKnown: LocalDate?): Long {
        if (firstKnown == null) return 0
        // Tage von der ersten bekannten Buchung bis heute, beide eingeschlossen.
        val span = minOf(TREND_DAYS, today.toEpochDay() - firstKnown.toEpochDay() + 1)
        if (span < MIN_TREND_DAYS) return 0
        val from = today.minusDays(span)
        return (movements.filter { it.first > from && it.first <= today }.sumOf { it.second }.toDouble() / span).roundToLong()
    }

    /**
     * @param openingCents Stand vor dem ersten Umsatz
     * @param transactions alle Umsätze des Kontos
     * @param flows noch ausstehende Dauerposten von heute bis Monatsende
     * @param otherPerDayCents siehe [otherPerDay]
     */
    fun forecast(
        today: LocalDate,
        openingCents: Long,
        transactions: List<Pair<LocalDate, Long>>,
        flows: List<Pair<LocalDate, Long>>,
        otherPerDayCents: Long,
        ratePercent: Double,
    ): MonthEndForecast {
        val monthStart = today.withDayOfMonth(1)
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val past = Overdraft.dailyBalances(openingCents, transactions, monthStart, today)
        var interest = past.sumOf { Overdraft.dailyInterest(it.second, ratePercent) }
        val soFar = interest.roundToLong()

        val due = flows.filter { it.first <= monthEnd }
        // Was für heute erwartet wird, aber noch nicht gebucht ist, zählt sofort.
        var balance = past.last().second + due.filter { it.first <= today }.sumOf { it.second }
        var lowest = balance
        var lowestDate = today
        var day = today.plusDays(1)
        var other = 0L
        while (day <= monthEnd) {
            balance += due.filter { it.first == day }.sumOf { it.second } + otherPerDayCents
            other += otherPerDayCents
            interest += Overdraft.dailyInterest(balance, ratePercent)
            if (balance < lowest) {
                lowest = balance
                lowestDate = day
            }
            day = day.plusDays(1)
        }
        return MonthEndForecast(
            monthEnd = monthEnd,
            endBalanceCents = balance,
            fixedExpenseCents = due.filter { it.second < 0 }.sumOf { it.second },
            expectedIncomeCents = due.filter { it.second > 0 }.sumOf { it.second },
            otherCents = other,
            otherPerDayCents = otherPerDayCents,
            interestSoFarCents = soFar,
            interestMonthCents = interest.roundToLong(),
            lowestBalanceCents = lowest,
            lowestDate = lowestDate,
        )
    }
}
