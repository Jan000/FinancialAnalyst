package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class CapitalTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test
    fun projectsFixedFlowsAndDailyAverage() {
        val flows = Budget.plannedFlows(
            listOf(
                RecurringPlan("Gehalt", 200000, RecurrenceInterval.MONTHLY, LocalDate.of(2026, 11, 28)),
                RecurringPlan("Miete", -80000, RecurrenceInterval.MONTHLY, LocalDate.of(2026, 12, 1)),
            ),
            today,
            LocalDate.of(2027, 1, 31),
        )
        val result = CapitalForecast.project(today, 100000, flows, -1000, 2, 0.0)
        assertEquals(listOf(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1)), result.months.map { it.month })
        // November: 17 Tage nach heute zu je −10 €, dazu das Gehalt.
        assertEquals(100000 + 200000 - 17 * 1000L, result.months[0].endCents)
        assertEquals(-31 * 1000L, result.months[1].otherCents)
        assertEquals(result.months[0].endCents + 200000 - 80000 - 31 * 1000, result.months[1].endCents)
        assertEquals(0L, result.months[1].interestCents)
        assertNull(result.firstNegative)
        assertEquals(result.months[2].endCents, result.endCents)
    }

    @Test
    fun negativeBalanceCostsInterestAndRecovers() {
        val flows = listOf(PlannedFlow(LocalDate.of(2026, 12, 15), 100000, "Bonus"))
        val result = CapitalForecast.project(today, -36500, flows, 0, 1, 10.0)
        // 10 % auf 365 € sind 10 Cent am Tag: 18 Tage im November (heute eingeschlossen), 14 im Dezember.
        assertEquals(-180L, result.months[0].interestCents)
        assertEquals(-36680L, result.months[0].endCents)
        assertEquals(LocalDate.of(2026, 12, 15), result.backToPlus)
        // Im Dezember auf 366,80 €: 14 × 10,05 Cent.
        assertEquals(-36680L - 141 + 100000, result.months[1].endCents)
        assertEquals(-36680L, result.lowestCents)
    }

    @Test
    fun shortTripAboveZeroIsNotBackToPlus() {
        // Gehalt hebt den Stand kurz über null, danach geht es wieder ins Minus.
        val flows = listOf(PlannedFlow(LocalDate.of(2026, 11, 28), 50000, "Gehalt"))
        val result = CapitalForecast.project(today, -20000, flows, -2000, 1, 0.0, overdraftLimitCents = 30000)
        assertNull(result.backToPlus)
        // −200 € und 20 € am Tag: am 19.11. bei −320 €, also unter dem Dispo-Rahmen von 300 €.
        assertEquals(LocalDate.of(2026, 11, 19), result.beyondLimit)
    }

    @Test
    fun slopeOfALineIsItsStep() {
        assertEquals(500L, CapitalForecast.slope(listOf(0, 500, 1000, 1500)))
        assertEquals(0L, CapitalForecast.slope(listOf(7)))
    }

    @Test
    fun perDayAveragesOverTheKnownDays() {
        val moves = listOf(today to -3000L, today.minusDays(5) to -6000L, today.minusDays(200) to -99999L)
        assertEquals(-100L, CapitalForecast.perDay(moves, today, 90, today.minusDays(400)))
        // Erst seit 30 Tagen Umsätze: über 30 Tage verteilen statt über 90.
        assertEquals(-300L, CapitalForecast.perDay(moves, today, 90, today.minusDays(29)))
    }
}
