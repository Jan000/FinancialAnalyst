package net.jandev.financialanalyst.domain

import java.time.LocalDate

/** Ein erwarteter fester Geldfluss (Miete, Abo, Gehalt) an einem bestimmten Tag. */
data class PlannedFlow(val date: LocalDate, val amountCents: Long, val name: String)

data class RecurringPlan(
    val name: String,
    val amountCents: Long,
    val interval: RecurrenceInterval,
    /** Erster noch offener Termin. */
    val nextDue: LocalDate,
    /** Gesetzt: fester Zahlungsplan laut Vertrag ab diesem ersten Termin ([ContractSchedule]). */
    val firstDue: LocalDate? = null,
    val changes: List<ScheduleChange> = emptyList(),
)

data class BudgetResult(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    /** Tage bis Periodenende einschließlich heute. */
    val daysLeft: Int,
    /** Frei verfügbar bis Periodenende: Kontostand + erwartete Einnahmen − Fixkosten − Reserve. */
    val restOfPeriodCents: Long,
    /** Was man ab heute jeden Tag ausgeben kann, ohne vor einem Geldeingang ins Minus zu rutschen. */
    val perDayCents: Long,
    val spentTodayCents: Long,
    /** Tagesbudget abzüglich der heutigen Ausgaben. */
    val todayRemainingCents: Long,
    val upcomingExpenseCents: Long,
    val upcomingIncomeCents: Long,
    /** Wenn das Tagesbudget durch einen späteren Geldeingang begrenzt ist: dessen Datum. */
    val limitedUntil: LocalDate?,
    /** Niedrigster Kontostand bis Periodenende, wenn nur noch die Fixkosten abgehen. */
    val lowestBalanceCents: Long,
    val lowestBalanceDate: LocalDate,
    val flows: List<PlannedFlow>,
)

object Budget {
    /** Überfällige Posten gelten noch so viele Tage als „kommt gleich"; danach als ausgefallen. */
    const val OVERDUE_GRACE_DAYS = 7L

    /**
     * Budgetzeitraum, der [today] enthält. [startDay] ist der Tag des Monats, an dem er beginnt
     * (1 = Kalendermonat, sonst z. B. der Gehaltstag); in kurzen Monaten rückt er auf den Monatsletzten.
     */
    fun period(today: LocalDate, startDay: Int): DateRange {
        fun startIn(month: LocalDate) = month.withDayOfMonth(minOf(startDay.coerceIn(1, 31), month.lengthOfMonth()))
        val thisMonth = startIn(today)
        val start = if (today >= thisMonth) thisMonth else startIn(today.minusMonths(1))
        val next = startIn(start.plusMonths(1).withDayOfMonth(1))
        return DateRange(start, next.minusDays(1))
    }

    /** Alle Termine der Dauerposten von heute bis [until]; kurz überfällige werden für heute erwartet. */
    fun plannedFlows(plans: List<RecurringPlan>, today: LocalDate, until: LocalDate): List<PlannedFlow> {
        val flows = ArrayList<PlannedFlow>()
        for (plan in plans) {
            if (plan.firstDue != null) {
                // Vertrag: was offen ist, bleibt fällig, bis es abgebucht ist – auch mehrere Beiträge auf einmal.
                for (due in ContractSchedule.openDues(plan, until)) {
                    flows.add(PlannedFlow(maxOf(due.date, today), due.amountCents, plan.name))
                }
                continue
            }
            var due = plan.nextDue
            val extras = YearlyExtras(plan.changes)
            if (due < today) {
                if (due.plusDays(OVERDUE_GRACE_DAYS) >= today) {
                    // Wird für heute erwartet; der nächste reguläre Termin liegt dann strikt in der Zukunft.
                    flows.add(PlannedFlow(today, plan.amountCents + extras.take(due), plan.name))
                    while (due <= today) due = plan.interval.next(due)
                } else {
                    while (due < today) due = plan.interval.next(due)
                }
            }
            while (due <= until) {
                flows.add(PlannedFlow(due, plan.amountCents + extras.take(due), plan.name))
                due = plan.interval.next(due)
            }
        }
        return flows.sortedBy { it.date }
    }

    /**
     * @param balanceCents aktueller Stand aller Budget-Konten zusammen
     * @param todayVariableNetCents Summe der heutigen Umsätze, die keine Fixkosten und keine festen Einnahmen sind
     * @param reserveCents Betrag, der am Periodenende übrig bleiben soll (Puffer, Sparrate)
     */
    fun calculate(
        today: LocalDate,
        period: DateRange,
        balanceCents: Long,
        todayVariableNetCents: Long,
        flows: List<PlannedFlow>,
        reserveCents: Long,
    ): BudgetResult {
        val relevant = flows.filter { it.date >= today && it.date <= period.endInclusive }.sortedBy { it.date }
        val daysLeft = (period.endInclusive.toEpochDay() - today.toEpochDay() + 1).toInt().coerceAtLeast(1)
        // Stand am Tagesanfang, so als wären die heutigen freien Ausgaben noch nicht passiert.
        val startOfDay = balanceCents - todayVariableNetCents

        // Bis zum Periodenende …
        var perDay = Math.floorDiv(startOfDay + relevant.sumOf { it.amountCents } - reserveCents, daysLeft.toLong())
        var limitedUntil: LocalDate? = null
        // … und zusätzlich vor jedem Geldeingang: das Geld bis dahin muss für die Tage bis dahin reichen.
        for (income in relevant.filter { it.amountCents > 0 && it.date > today }) {
            val days = income.date.toEpochDay() - today.toEpochDay()
            val before = startOfDay + relevant.filter { it.date < income.date }.sumOf { it.amountCents }
            val candidate = Math.floorDiv(before, days)
            if (candidate < perDay) {
                perDay = candidate
                limitedUntil = income.date
            }
        }

        var running = balanceCents
        var lowest = balanceCents
        var lowestDate = today
        for (flow in relevant) {
            running += flow.amountCents
            if (running < lowest) {
                lowest = running
                lowestDate = flow.date
            }
        }

        val spentToday = -todayVariableNetCents
        return BudgetResult(
            periodStart = period.start,
            periodEnd = period.endInclusive,
            daysLeft = daysLeft,
            restOfPeriodCents = balanceCents + relevant.sumOf { it.amountCents } - reserveCents,
            perDayCents = perDay,
            spentTodayCents = spentToday,
            todayRemainingCents = perDay - spentToday,
            upcomingExpenseCents = -relevant.filter { it.amountCents < 0 }.sumOf { it.amountCents },
            upcomingIncomeCents = relevant.filter { it.amountCents > 0 }.sumOf { it.amountCents },
            limitedUntil = limitedUntil,
            lowestBalanceCents = lowest,
            lowestBalanceDate = lowestDate,
            flows = relevant,
        )
    }
}
