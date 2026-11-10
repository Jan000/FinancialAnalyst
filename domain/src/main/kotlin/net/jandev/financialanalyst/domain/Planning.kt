package net.jandev.financialanalyst.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object NetWorth {
    /**
     * Vermögen am Ende der letzten [months] Monate (der laufende Monat endet „heute").
     * Zurückgerechnet vom heutigen Gesamtstand über alle Umsätze; Umbuchungen heben sich dabei selbst auf.
     */
    fun history(currentTotalCents: Long, transactions: List<Pair<LocalDate, Long>>, today: LocalDate, months: Int): List<Pair<LocalDate, Long>> {
        val sorted = transactions.sortedByDescending { it.first }
        val result = ArrayList<Pair<LocalDate, Long>>()
        var total = currentTotalCents
        var index = 0
        for (back in 0 until months) {
            val pointDate = if (back == 0) today else today.minusMonths(back.toLong()).with(TemporalAdjusters.lastDayOfMonth())
            // Alles abziehen, was nach diesem Stichtag passiert ist.
            while (index < sorted.size && sorted[index].first > pointDate) {
                total -= sorted[index].second
                index++
            }
            result.add(pointDate to total)
        }
        return result.reversed()
    }
}

data class BudgetStatus(
    val categoryId: Long,
    val limitCents: Long,
    val spentCents: Long,
    /** Was bei gleichbleibendem Tempo bis zum Ende des Zeitraums ausgegeben wäre. */
    val projectedCents: Long,
) {
    val remainingCents: Long get() = limitCents - spentCents
    val fraction: Float get() = if (limitCents <= 0) 0f else spentCents.toFloat() / limitCents
    val isOver: Boolean get() = spentCents > limitCents
    val willExceed: Boolean get() = !isOver && projectedCents > limitCents
}

object CategoryBudgets {
    fun status(limits: Map<Long, Long>, summary: PeriodSummary, today: LocalDate): List<BudgetStatus> {
        val spent = summary.expensesByCategory.associate { it.categoryId to it.cents }
        val elapsed = (ChronoUnit.DAYS.between(summary.range.start, minOf(today, summary.range.endInclusive)) + 1).coerceAtLeast(1)
        return limits.map { (categoryId, limit) ->
            val used = (spent[categoryId] ?: 0).coerceAtLeast(0)
            BudgetStatus(categoryId, limit, used, used * summary.range.days / elapsed)
        }.sortedByDescending { it.fraction }
    }
}

data class GoalPlan(
    val remainingCents: Long,
    val fraction: Float,
    /** Nötige Sparrate pro Monat bis zum Zieldatum; `null` ohne Datum oder wenn es schon vorbei ist. */
    val monthlyCents: Long?,
    val monthsLeft: Int?,
)

object SavingsGoals {
    fun plan(targetCents: Long, savedCents: Long, targetDate: LocalDate?, today: LocalDate): GoalPlan {
        val remaining = (targetCents - savedCents).coerceAtLeast(0)
        val fraction = if (targetCents <= 0) 1f else (savedCents.toFloat() / targetCents).coerceIn(0f, 1f)
        if (targetDate == null || !targetDate.isAfter(today) || remaining == 0L) return GoalPlan(remaining, fraction, null, null)
        // Angefangene Monate zählen voll: lieber eine Rate zu früh fertig als eine zu spät.
        val months = (ChronoUnit.MONTHS.between(today, targetDate).toInt() + 1).coerceAtLeast(1)
        return GoalPlan(remaining, fraction, Math.ceilDiv(remaining, months.toLong()), months)
    }
}
