package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanningTest {
    private val today = LocalDate.of(2026, 11, 10)

    @Test
    fun netWorthIsRolledBackFromTodaysTotal() {
        val txs = listOf(
            LocalDate.of(2026, 11, 5) to -10000L,
            LocalDate.of(2026, 10, 30) to 250000L,
            LocalDate.of(2026, 10, 2) to -85000L,
            LocalDate.of(2026, 9, 15) to -5000L,
        )
        val history = NetWorth.history(500000, txs, today, 3)
        assertEquals(
            listOf(
                LocalDate.of(2026, 9, 30) to 345000L,
                LocalDate.of(2026, 10, 31) to 510000L,
                today to 500000L,
            ),
            history,
        )
    }

    @Test
    fun budgetStatusProjectsToEndOfPeriod() {
        val range = Periods.rangeOf(Period.MONTH, today)
        val summary = PeriodSummary(range, 0, 30000, listOf(CategoryTotal(1, 20000, 4), CategoryTotal(2, 10000, 2)), emptyList(), emptyList(), 6)
        val status = CategoryBudgets.status(mapOf(1L to 50000L, 2L to 8000L, 3L to 10000L), summary, today)
        assertEquals(listOf(2L, 1L, 3L), status.map { it.categoryId })
        val food = status.first { it.categoryId == 1L }
        // 200 € in 10 von 30 Tagen → hochgerechnet 600 € bei 500 € Budget.
        assertEquals(60000L, food.projectedCents)
        assertTrue(food.willExceed)
        assertFalse(food.isOver)
        assertTrue(status.first { it.categoryId == 2L }.isOver)
        assertEquals(0L, status.first { it.categoryId == 3L }.spentCents)
    }

    @Test
    fun savingsGoalNeedsMonthlyRate() {
        val plan = SavingsGoals.plan(300000, 60000, LocalDate.of(2027, 5, 1), today)
        assertEquals(240000L, plan.remainingCents)
        assertEquals(6, plan.monthsLeft)
        assertEquals(40000L, plan.monthlyCents)
        assertEquals(0.2f, plan.fraction, 0.001f)
    }

    @Test
    fun savingsGoalWithoutDateOrAlreadyReached() {
        assertNull(SavingsGoals.plan(100000, 20000, null, today).monthlyCents)
        assertNull(SavingsGoals.plan(100000, 20000, today.minusDays(1), today).monthlyCents)
        val done = SavingsGoals.plan(100000, 120000, LocalDate.of(2027, 1, 1), today)
        assertEquals(0L, done.remainingCents)
        assertEquals(1f, done.fraction, 0f)
    }
}
