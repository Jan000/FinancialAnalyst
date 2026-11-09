package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoansTest {
    private val start = LocalDate.of(2026, 1, 15)

    @Test
    fun firstRowsOfAnAnnuityLoan() {
        // 10.000 € zu 6 % p. a., 200 € Rate: 1. Monat 50,00 € Zinsen, 150,00 € Tilgung.
        val schedule = LoanCalculator.schedule(LoanTerms(1000000, 6.0, 20000, start))
        val first = schedule.rows[0]
        assertEquals(5000L, first.interestCents)
        assertEquals(15000L, first.principalCents)
        assertEquals(985000L, first.remainingCents)
        // 2. Monat: 9.850 € × 0,5 % = 49,25 €.
        assertEquals(4925L, schedule.rows[1].interestCents)
        assertEquals(LocalDate.of(2026, 2, 15), schedule.rows[1].date)
    }

    @Test
    fun scheduleEndsAtZeroAndTotalsAddUp() {
        val schedule = LoanCalculator.schedule(LoanTerms(1000000, 6.0, 20000, start))
        assertEquals(0L, schedule.rows.last().remainingCents)
        assertEquals(schedule.rows.last().date, schedule.payoffDate)
        assertEquals(1000000L + schedule.totalInterestCents, schedule.totalPaidCents)
        assertEquals(1000000L, schedule.rows.sumOf { it.principalCents + it.extraCents })
        // Bekannter Richtwert: rund 58 Monate Laufzeit.
        assertEquals(58, schedule.rows.size)
        assertTrue(schedule.rows.last().paymentCents <= 20000)
    }

    @Test
    fun zeroInterestLoanIsPlainDivision() {
        val schedule = LoanCalculator.schedule(LoanTerms(120000, 0.0, 10000, start))
        assertEquals(12, schedule.rows.size)
        assertEquals(0L, schedule.totalInterestCents)
    }

    @Test
    fun extraPaymentShortensTermAndSavesInterest() {
        val plain = LoanCalculator.schedule(LoanTerms(1000000, 6.0, 20000, start))
        val withExtra = LoanCalculator.schedule(
            LoanTerms(1000000, 6.0, 20000, start, listOf(ExtraPayment(LocalDate.of(2026, 6, 1), 200000))),
        )
        assertEquals(200000L, withExtra.rows.first { it.date == LocalDate.of(2026, 6, 15) }.extraCents)
        assertTrue(withExtra.rows.size < plain.rows.size)
        assertTrue(withExtra.totalInterestCents < plain.totalInterestCents)
        assertEquals(0L, withExtra.rows.last().remainingCents)
    }

    @Test
    fun paymentBelowInterestNeverPaysOff() {
        val schedule = LoanCalculator.schedule(LoanTerms(1000000, 12.0, 5000, start))
        assertNull(schedule.payoffDate)
    }

    @Test
    fun statusAtAGivenDay() {
        val terms = LoanTerms(1000000, 6.0, 20000, start)
        val schedule = LoanCalculator.schedule(terms)
        val status = LoanCalculator.status(terms, schedule, LocalDate.of(2026, 2, 20))
        assertEquals(2, status.paymentsMade)
        assertEquals(5000L + 4925L, status.paidInterestCents)
        assertEquals(1000000L - 15000 - 15075, status.remainingCents)
        assertEquals(1000000L, LoanCalculator.status(terms, schedule, start.minusDays(1)).remainingCents)
    }

    @Test
    fun annuityForAGivenTerm() {
        val payment = LoanCalculator.annuityCents(1000000, 6.0, 60)
        // Lehrbuchwert 193,33 €.
        assertEquals(19333L, payment)
        assertEquals(60, LoanCalculator.schedule(LoanTerms(1000000, 6.0, payment, start)).rows.size)
    }

    @Test
    fun overdraftInterestIsChargedPerDayOnNegativeBalance() {
        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 10, 31)
        val txs = listOf(
            LocalDate.of(2026, 9, 20) to 20000L,
            // Ab 11.10. bei −500 €, ab 21.10. wieder im Plus.
            LocalDate.of(2026, 10, 11) to -100000L,
            LocalDate.of(2026, 10, 21) to 150000L,
        )
        val report = Overdraft.report(30000, txs, from, to, 10.95, 40000)
        assertEquals(10, report.daysOverdrawn)
        assertEquals(-50000L, report.lowestBalanceCents)
        // 500 € × 10,95 % / 365 × 10 Tage = 1,50 €.
        assertEquals(150L, report.interestCents)
        assertEquals(10, report.daysOverLimit)
        assertEquals(150L, Overdraft.costCents(50000, 10, 10.95))
    }

    @Test
    fun dailyBalancesCarryForward() {
        val balances = Overdraft.dailyBalances(
            1000,
            listOf(LocalDate.of(2026, 10, 2) to -300L, LocalDate.of(2026, 10, 2) to -200L),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 3),
        )
        assertEquals(listOf(1000L, 500L, 500L), balances.map { it.second })
    }
}
