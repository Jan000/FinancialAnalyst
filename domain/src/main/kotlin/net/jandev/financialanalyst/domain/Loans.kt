package net.jandev.financialanalyst.domain

import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Zahlung außer der Reihe. Positiv: Sondertilgung, senkt die Schuld. Negativ: weitere Auszahlung (Aufstockung),
 * die Schuld steigt ab diesem Tag um den Betrag.
 */
data class ExtraPayment(val date: LocalDate, val amountCents: Long)

/** Ab [from] gilt eine andere Monatsrate; 0 bedeutet eine Zahlpause. */
data class RateChange(val from: LocalDate, val monthlyPaymentCents: Long)

data class LoanTerms(
    val principalCents: Long,
    /** Sollzins p. a. in Prozent. */
    val ratePercent: Double,
    val monthlyPaymentCents: Long,
    val firstPayment: LocalDate,
    val extraPayments: List<ExtraPayment> = emptyList(),
    val rateChanges: List<RateChange> = emptyList(),
    /**
     * Tatsächlich geleistete Zahlungen (aus den Umsätzen). Sind sie angegeben, zählen bis [actualUntil]
     * sie statt der geplanten Rate – auch wenn mal weniger, mehr oder gar nichts gezahlt wurde.
     */
    val actualPayments: List<ExtraPayment>? = null,
    val actualUntil: LocalDate = LocalDate.MIN,
) {
    /** Die an [date] geplante Monatsrate. */
    fun plannedPayment(date: LocalDate): Long =
        rateChanges.filter { it.from <= date }.maxByOrNull { it.from }?.monthlyPaymentCents ?: monthlyPaymentCents
}

data class LoanRow(
    val date: LocalDate,
    /** Tatsächlich gezahlte Rate (die letzte ist meist kleiner). */
    val paymentCents: Long,
    val interestCents: Long,
    val principalCents: Long,
    /** Sondertilgungen in diesem Ratenzeitraum. */
    val extraCents: Long,
    val remainingCents: Long,
    /** Weitere Auszahlungen (Aufstockung) in diesem Ratenzeitraum, positiv. */
    val disbursedCents: Long = 0,
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

        // Weiter, solange Schuld offen ist oder noch eine Auszahlung kommt.
        while ((remaining > 0 || extras.any { it.amountCents < 0 && it.date > previous }) && rows.size < MAX_MONTHS) {
            // Sondertilgungen senken die Schuld ab ihrem Buchungstag: der Monatszins wird anteilig (30/360) gerechnet.
            val start = if (previous == LocalDate.MIN) date.minusMonths(1) else previous
            var interestExact = 0.0
            var share = 0.0
            var extra = 0L
            var disbursed = 0L
            for (payment in extras.filter { it.date > previous && it.date <= date }) {
                val upTo = (days360(start, payment.date) / 30.0).coerceIn(share, 1.0)
                interestExact += remaining * monthlyRate * (upTo - share)
                share = upTo
                // Eine Auszahlung (negativ) erhöht die Schuld; eine Sondertilgung höchstens bis auf null.
                val paid = if (payment.amountCents < 0) payment.amountCents else minOf(remaining, payment.amountCents)
                remaining -= paid
                if (paid < 0) disbursed -= paid else extra += paid
            }
            interestExact += remaining * monthlyRate * (1.0 - share)
            val interest = interestExact.roundToLong()
            val planned = terms.plannedPayment(date)
            val actual = terms.actualPayments?.filter { it.date > previous && it.date <= date }?.sumOf { it.amountCents }
            val past = actual != null && date <= terms.actualUntil
            val wanted = when {
                // Vergangenheit: es zählt, was wirklich gezahlt wurde.
                past -> actual ?: 0
                // Die laufende Rate ist schon (ganz oder teilweise) bezahlt: nicht doppelt ansetzen.
                actual != null && actual > 0 -> maxOf(planned, actual)
                else -> planned
            }
            val payment = minOf(wanted, remaining + interest)
            val principal = payment - interest
            if (!past && principal <= 0 && extras.none { it.date > date } && terms.rateChanges.none { it.from > date }) {
                // Die Rate deckt nicht einmal die Zinsen: der Kredit wird nie getilgt.
                return LoanSchedule(rows, totalInterest, totalPaid, null)
            }
            remaining -= principal
            totalInterest += interest
            totalPaid += payment + extra
            rows.add(LoanRow(date, payment, interest, principal, extra, remaining.coerceAtLeast(0), disbursed))
            previous = date
            date = terms.firstPayment.plusMonths(rows.size.toLong())
        }
        return LoanSchedule(rows, totalInterest, totalPaid, if (remaining <= 0) rows.lastOrNull()?.date else null)
    }

    /** Tage zwischen zwei Daten nach der deutschen Zinsmethode 30E/360. */
    private fun days360(from: LocalDate, to: LocalDate): Int =
        360 * (to.year - from.year) + 30 * (to.monthValue - from.monthValue) + (minOf(to.dayOfMonth, 30) - minOf(from.dayOfMonth, 30))

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
