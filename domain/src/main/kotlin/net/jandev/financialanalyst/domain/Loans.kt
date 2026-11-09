package net.jandev.financialanalyst.domain

import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToLong

data class ExtraPayment(val date: LocalDate, val amountCents: Long)

data class LoanTerms(
    val principalCents: Long,
    /** Sollzins p. a. in Prozent. */
    val ratePercent: Double,
    val monthlyPaymentCents: Long,
    val firstPayment: LocalDate,
    val extraPayments: List<ExtraPayment> = emptyList(),
)

data class LoanRow(
    val date: LocalDate,
    /** Tatsächlich gezahlte Rate (die letzte ist meist kleiner). */
    val paymentCents: Long,
    val interestCents: Long,
    val principalCents: Long,
    val extraCents: Long,
    val remainingCents: Long,
)

data class LoanSchedule(
    val rows: List<LoanRow>,
    val totalInterestCents: Long,
    val totalPaidCents: Long,
    /** `null`, wenn die Rate die Zinsen nicht deckt oder die Laufzeit 100 Jahre überschreitet. */
    val payoffDate: LocalDate?,
)

data class LoanStatus(
    val remainingCents: Long,
    val paidInterestCents: Long,
    val remainingInterestCents: Long,
    val paymentsMade: Int,
    val paymentsLeft: Int,
)

/** Annuitätendarlehen mit monatlicher Rate und nachschüssiger Verzinsung (30/360). */
object LoanCalculator {
    private const val MAX_MONTHS = 1200

    fun schedule(terms: LoanTerms): LoanSchedule {
        val rows = ArrayList<LoanRow>()
        var remaining = terms.principalCents
        var date = terms.firstPayment
        var previous = LocalDate.MIN
        val monthlyRate = terms.ratePercent / 100.0 / 12.0
        val extras = terms.extraPayments.sortedBy { it.date }
        var totalInterest = 0L
        var totalPaid = 0L

        while (remaining > 0 && rows.size < MAX_MONTHS) {
            val interest = (remaining * monthlyRate).roundToLong()
            val payment = minOf(terms.monthlyPaymentCents, remaining + interest)
            val principal = payment - interest
            if (principal <= 0 && extras.none { it.date > previous }) {
                // Die Rate deckt nicht einmal die Zinsen: der Kredit wird nie getilgt.
                return LoanSchedule(rows, totalInterest, totalPaid, null)
            }
            remaining -= principal
            // Sondertilgungen seit der letzten Rate wirken ab diesem Termin.
            val extra = minOf(
                remaining.coerceAtLeast(0),
                extras.filter { it.date > previous && it.date <= date }.sumOf { it.amountCents },
            )
            remaining -= extra
            totalInterest += interest
            totalPaid += payment + extra
            rows.add(LoanRow(date, payment, interest, principal, extra, remaining.coerceAtLeast(0)))
            previous = date
            date = terms.firstPayment.plusMonths(rows.size.toLong())
        }
        return LoanSchedule(rows, totalInterest, totalPaid, if (remaining <= 0) rows.lastOrNull()?.date else null)
    }

    fun status(terms: LoanTerms, schedule: LoanSchedule, today: LocalDate): LoanStatus {
        val done = schedule.rows.filter { it.date <= today }
        val paidInterest = done.sumOf { it.interestCents }
        return LoanStatus(
            remainingCents = done.lastOrNull()?.remainingCents ?: terms.principalCents,
            paidInterestCents = paidInterest,
            remainingInterestCents = schedule.totalInterestCents - paidInterest,
            paymentsMade = done.size,
            paymentsLeft = schedule.rows.size - done.size,
        )
    }

    /** Monatsrate, mit der ein Darlehen in [months] Monaten getilgt ist. */
    fun annuityCents(principalCents: Long, ratePercent: Double, months: Int): Long {
        if (months <= 0) return principalCents
        val r = ratePercent / 100.0 / 12.0
        if (r == 0.0) return Math.ceilDiv(principalCents, months.toLong())
        return kotlin.math.ceil(principalCents * r / (1 - (1 + r).pow(-months))).toLong()
    }
}

data class OverdraftReport(
    val from: LocalDate,
    val to: LocalDate,
    /** Aufgelaufene Dispozinsen im Zeitraum. */
    val interestCents: Long,
    val daysOverdrawn: Int,
    /** Tiefster Kontostand im Zeitraum. */
    val lowestBalanceCents: Long,
    /** Tage, an denen der eingeräumte Rahmen überschritten war. */
    val daysOverLimit: Int,
)

/** Dispozinsen: taggenau auf den negativen Tagesendsaldo, Jahr mit 365 Tagen. */
object Overdraft {

    /** Tagesendsalden von [from] bis [to]; [openingCents] ist der Stand vor dem allerersten Umsatz. */
    fun dailyBalances(
        openingCents: Long,
        transactions: List<Pair<LocalDate, Long>>,
        from: LocalDate,
        to: LocalDate,
    ): List<Pair<LocalDate, Long>> {
        val byDay = HashMap<LocalDate, Long>()
        var balance = openingCents
        for ((date, amount) in transactions) {
            if (date < from) balance += amount else if (date <= to) byDay.merge(date, amount, Long::plus)
        }
        val result = ArrayList<Pair<LocalDate, Long>>()
        var day = from
        while (day <= to) {
            balance += byDay[day] ?: 0
            result.add(day to balance)
            day = day.plusDays(1)
        }
        return result
    }

    fun dailyInterest(balanceCents: Long, ratePercent: Double): Double =
        if (balanceCents >= 0) 0.0 else -balanceCents * ratePercent / 100.0 / 365.0

    fun report(
        openingCents: Long,
        transactions: List<Pair<LocalDate, Long>>,
        from: LocalDate,
        to: LocalDate,
        ratePercent: Double,
        limitCents: Long,
    ): OverdraftReport {
        val balances = dailyBalances(openingCents, transactions, from, to)
        return OverdraftReport(
            from = from,
            to = to,
            interestCents = balances.sumOf { dailyInterest(it.second, ratePercent) }.roundToLong(),
            daysOverdrawn = balances.count { it.second < 0 },
            lowestBalanceCents = balances.minOfOrNull { it.second } ?: openingCents,
            daysOverLimit = balances.count { it.second < -limitCents },
        )
    }

    /** Was es kostet, [amountCents] für [days] Tage im Dispo zu stehen. */
    fun costCents(amountCents: Long, days: Int, ratePercent: Double): Long =
        (amountCents * ratePercent / 100.0 / 365.0 * days).roundToLong()
}
