package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ForecastTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun endBalanceAddsFixedFlowsAndTheDailyTrend() {
        val forecast = MonthEnd.forecast(
            today = today,
            openingCents = 100000,
            transactions = listOf(LocalDate.of(2026, 11, 3) to -20000L),
            flows = listOf(
                LocalDate.of(2026, 11, 15) to -30000L,
                LocalDate.of(2026, 11, 28) to 200000L,
                // Nächster Monat: zählt nicht.
                LocalDate.of(2026, 12, 1) to -50000L,
            ),
            otherPerDayCents = -1000,
            ratePercent = 0.0,
        )
        assertEquals(LocalDate.of(2026, 11, 30), forecast.monthEnd)
        assertEquals(-30000L, forecast.fixedExpenseCents)
        assertEquals(200000L, forecast.expectedIncomeCents)
        assertEquals(-20000L, forecast.otherCents) // 20 Resttage
        assertEquals(80000L - 30000 + 200000 - 20000, forecast.endBalanceCents)
        // Tiefpunkt am Tag vor dem Geldeingang.
        assertEquals(LocalDate.of(2026, 11, 27), forecast.lowestDate)
        assertEquals(80000L - 30000 - 17000, forecast.lowestBalanceCents)
        assertEquals(0L, forecast.interestMonthCents)
    }

    @Test
    fun overdraftInterestAccruesOnNegativeDays() {
        // Den ganzen Monat 1.000 € im Minus bei 12 %: 30 Tage × 1000 × 0,12 / 365.
        val forecast = MonthEnd.forecast(today, -100000, emptyList(), emptyList(), 0, 12.0)
        assertEquals(329L, forecast.interestSoFarCents) // 10 Tage
        assertEquals(986L, forecast.interestMonthCents)
        assertEquals(-100000L, forecast.endBalanceCents)
    }

    @Test
    fun trendNeedsEnoughHistory() {
        val movements = (1L..90L).map { today.minusDays(it - 1) to -900L }
        assertEquals(-900L, MonthEnd.otherPerDay(today, movements, today.minusDays(400)))
        // Nur 30 Tage bekannt: der Durchschnitt bezieht sich auf diese 30 Tage.
        assertEquals(-900L, MonthEnd.otherPerDay(today, movements.take(30), today.minusDays(29)))
        assertEquals(0L, MonthEnd.otherPerDay(today, movements.take(5), today.minusDays(4)))
        assertEquals(0L, MonthEnd.otherPerDay(today, emptyList(), null))
    }
}
