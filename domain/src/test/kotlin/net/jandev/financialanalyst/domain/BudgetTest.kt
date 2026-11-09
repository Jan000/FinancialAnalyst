package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BudgetTest {
    private val today = LocalDate.of(2026, 11, 9)

    private fun monthly(name: String, cents: Long, months: Int, day: Int, last: LocalDate = today, account: Long = 1): List<Tx> =
        (0 until months).map { back ->
            val date = last.withDayOfMonth(day).minusMonths(back.toLong())
            Tx(id = (name.hashCode() * 100L + back), accountId = account, date = date, amountCents = cents, counterparty = name)
        }

    @Test
    fun detectsMonthlyPaymentsAndPriceIncrease() {
        val spotify = monthly("Spotify AB", -1099, 3, 3) + Tx(99, 1, LocalDate.of(2026, 8, 3), -999, "Spotify AB")
        val rent = monthly("Hausverwaltung Beispiel", -85000, 4, 2)
        val salary = monthly("Beispiel AG", 250000, 3, 30, last = LocalDate.of(2026, 10, 30))
        val groceries = listOf(3, 6, 14, 15, 29).map { Tx(500L + it, 1, LocalDate.of(2026, 10, it), -1000L * it, "REWE Markt") }
        val detected = RecurringDetector.detect(spotify + rent + salary + groceries, today)

        assertEquals(setOf("spotify|-", "hausverwaltung beispiel|-", "beispiel|+"), detected.map { it.matchKey }.toSet())
        val s = detected.first { it.matchKey == "spotify|-" }
        assertEquals(RecurrenceInterval.MONTHLY, s.interval)
        assertEquals(LocalDate.of(2026, 12, 3), s.nextDue)
        assertEquals(-1099L, s.amountCents)
        assertNull(s.previousAmountCents)
        assertEquals(LocalDate.of(2026, 11, 30), detected.first { it.matchKey == "beispiel|+" }.nextDue)
    }

    @Test
    fun reportsPreviousAmountWhenLatestPaymentDiffers() {
        val txs = monthly("Streamingdienst", -1299, 1, 5) + monthly("Streamingdienst", -1099, 3, 5, last = today.minusMonths(1))
        val item = RecurringDetector.detect(txs, today).single()
        assertEquals(-1299L, item.amountCents)
        assertEquals(-1099L, item.previousAmountCents)
    }

    @Test
    fun ignoresEndedSubscriptionsAndTooFewOccurrences() {
        val ended = monthly("Altes Abo", -500, 4, 5, last = LocalDate.of(2026, 6, 5))
        val twice = monthly("Zweimal", -500, 2, 5)
        assertTrue(RecurringDetector.detect(ended + twice, today).isEmpty())
    }

    @Test
    fun findsSubscriptionAmongOneOffPurchasesAtSameMerchant() {
        val prime = monthly("Amazon", -899, 4, 12, last = LocalDate.of(2026, 10, 12))
        val purchases = listOf(
            Tx(900, 1, LocalDate.of(2026, 9, 20), -4599, "Amazon"),
            Tx(901, 1, LocalDate.of(2026, 10, 1), -12999, "Amazon"),
            Tx(902, 1, LocalDate.of(2026, 10, 28), -2349, "Amazon"),
        )
        val item = RecurringDetector.detect(prime + purchases, today).single()
        assertEquals(-899L, item.amountCents)
        assertEquals(LocalDate.of(2026, 11, 12), item.nextDue)
    }

    @Test
    fun budgetPeriodFollowsStartDay() {
        assertEquals(DateRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30)), Budget.period(today, 1))
        assertEquals(DateRange(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 27)), Budget.period(today, 28))
        // Der 31. rückt im Februar auf den Monatsletzten.
        assertEquals(
            DateRange(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 27)),
            Budget.period(LocalDate.of(2026, 2, 10), 31),
        )
    }

    @Test
    fun plannedFlowsRepeatAndTreatShortlyOverdueAsDueToday() {
        val plans = listOf(
            RecurringPlan("Wöchentlich", -1000, RecurrenceInterval.WEEKLY, today.plusDays(2)),
            RecurringPlan("Überfällig", -5000, RecurrenceInterval.MONTHLY, today.minusDays(3)),
            RecurringPlan("Längst vorbei", -7000, RecurrenceInterval.YEARLY, today.minusDays(40)),
        )
        val flows = Budget.plannedFlows(plans, today, LocalDate.of(2026, 11, 30))
        assertEquals(listOf(11, 18, 25), flows.filter { it.name == "Wöchentlich" }.map { it.date.dayOfMonth })
        assertEquals(listOf(today), flows.filter { it.name == "Überfällig" }.map { it.date })
        assertTrue(flows.none { it.name == "Längst vorbei" })
    }

    @Test
    fun dailyBudgetSpreadsFreeMoneyOverRemainingDays() {
        val period = Budget.period(today, 1)
        val flows = listOf(PlannedFlow(LocalDate.of(2026, 11, 15), -22000, "Versicherung"))
        // 22 Tage inkl. heute, 1.000 € Stand, 220 € Fixkosten, 120 € Reserve → 660 € frei → 30 €/Tag.
        val result = Budget.calculate(today, period, 100000, 0, flows, 12000)
        assertEquals(22, result.daysLeft)
        assertEquals(66000L, result.restOfPeriodCents)
        assertEquals(3000L, result.perDayCents)
        assertEquals(3000L, result.todayRemainingCents)
        assertNull(result.limitedUntil)
        assertEquals(78000L, result.lowestBalanceCents)
    }

    @Test
    fun todaysSpendingReducesTodayButNotTheDailyRate() {
        val period = Budget.period(today, 1)
        // Heute schon 12 € ausgegeben: Stand 988 €, Tagesbudget bleibt 1000/22.
        val result = Budget.calculate(today, period, 98800, -1200, emptyList(), 0)
        assertEquals(Math.floorDiv(100000L, 22L), result.perDayCents)
        assertEquals(1200L, result.spentTodayCents)
        assertEquals(result.perDayCents - 1200, result.todayRemainingCents)
    }

    @Test
    fun laterSalaryDoesNotInflateTheDaysBeforeIt() {
        val period = Budget.period(today, 1)
        val flows = listOf(
            PlannedFlow(LocalDate.of(2026, 11, 12), -5000, "Abo"),
            PlannedFlow(LocalDate.of(2026, 11, 19), 200000, "Gehalt"),
        )
        // Bis zum Gehalt am 19. sind es 10 Tage mit 300 € − 50 € = 250 € → 25 €/Tag, obwohl der Monat insgesamt mehr hergäbe.
        val result = Budget.calculate(today, period, 30000, 0, flows, 0)
        assertEquals(2500L, result.perDayCents)
        assertEquals(LocalDate.of(2026, 11, 19), result.limitedUntil)
        assertEquals(225000L, result.restOfPeriodCents)
    }

    @Test
    fun warnsWhenFixedCostsExceedBalance() {
        val period = Budget.period(today, 1)
        val flows = listOf(PlannedFlow(LocalDate.of(2026, 11, 20), -85000, "Miete"))
        val result = Budget.calculate(today, period, 50000, 0, flows, 0)
        assertEquals(-35000L, result.lowestBalanceCents)
        assertEquals(LocalDate.of(2026, 11, 20), result.lowestBalanceDate)
        assertTrue(result.perDayCents < 0)
    }
}
