package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ContractsTest {
    private val first = LocalDate.of(2026, 3, 15)
    private val rate = -12000L
    // Erster Beitrag mit Rabatt, später abgebucht als der Termin.
    private val changes = listOf(ScheduleChange(first, ChangeKind.ONCE, -10500))

    @Test
    fun lateAndMissingPaymentsStayOpen() {
        val today = LocalDate.of(2026, 9, 20)
        val bookings = listOf(LocalDate.of(2026, 4, 2) to -10500L)
        val plan = ContractSchedule.allocate(ContractSchedule.dues(first, RecurrenceInterval.QUARTERLY, rate, changes, today), bookings)
        assertEquals(listOf(LocalDate.of(2026, 3, 15), LocalDate.of(2026, 6, 15), LocalDate.of(2026, 9, 15)), plan.map { it.date })
        assertEquals(LocalDate.of(2026, 4, 2), plan[0].paidOn)
        assertEquals(-10500L, plan[0].amountCents)
        assertTrue(plan[1].isOpen && plan[2].isOpen)

        val next = ContractSchedule.nextOpen(first, RecurrenceInterval.QUARTERLY, rate, changes, bookings, today)
        assertEquals(LocalDate.of(2026, 6, 15), next)
        // Beide offenen Beiträge sind heute fällig und verschwinden nicht nach ein paar Tagen.
        val flows = Budget.plannedFlows(
            listOf(RecurringPlan("Versicherung", rate, RecurrenceInterval.QUARTERLY, next, first, changes)),
            today,
            LocalDate.of(2026, 12, 31),
        )
        assertEquals(listOf(today, today, LocalDate.of(2026, 12, 15)), flows.map { it.date })
        assertEquals(-36000L, flows.sumOf { it.amountCents })
    }

    @Test
    fun latePaymentPaysTheOldestOpenDue() {
        val bookings = listOf(LocalDate.of(2026, 3, 20) to -10500L, LocalDate.of(2026, 9, 18) to -12000L)
        val today = LocalDate.of(2026, 9, 20)
        val plan = ContractSchedule.allocate(ContractSchedule.dues(first, RecurrenceInterval.QUARTERLY, rate, changes, today), bookings)
        // Die Septemberbuchung bezahlt den Juni-Beitrag; der September-Beitrag bleibt offen.
        assertEquals(LocalDate.of(2026, 9, 18), plan[1].paidOn)
        assertNull(plan[2].paidOn)
    }

    @Test
    fun raisesSkipsAndManualSettlement() {
        val more = changes + listOf(
            ScheduleChange(LocalDate.of(2026, 9, 15), ChangeKind.FROM_HERE, -13000),
            ScheduleChange(LocalDate.of(2026, 6, 15), ChangeKind.SETTLED),
            ScheduleChange(LocalDate.of(2027, 3, 15), ChangeKind.SKIP),
        )
        val dues = ContractSchedule.dues(first, RecurrenceInterval.QUARTERLY, rate, more, LocalDate.of(2027, 6, 30))
        assertEquals(listOf(-10500L, -12000L, -13000L, -13000L, -13000L), dues.map { it.amountCents })
        assertTrue(dues.none { it.date == LocalDate.of(2027, 3, 15) })
        assertTrue(!dues[1].isOpen)
        assertEquals(more.sortedBy { it.date }, ScheduleChange.decode(ScheduleChange.encode(more)))
    }

    @Test
    fun monthlyDatesDoNotDrift() {
        val dates = (0..3).map { ContractSchedule.dateOf(LocalDate.of(2026, 1, 31), RecurrenceInterval.MONTHLY, it) }
        assertEquals(listOf(31, 28, 31, 30), dates.map { it.dayOfMonth })
    }

    @Test
    fun yearlyExtrasComeWithTheFirstPaymentOfTheirMonth() {
        // Gehalt 2.000 €, ab Juni 2026 jedes Jahr Urlaubsgeld im Juni und Weihnachtsgeld im November.
        val extras = listOf(
            ScheduleChange(LocalDate.of(2026, 6, 1), ChangeKind.YEARLY, 80000, "Urlaubsgeld"),
            ScheduleChange(LocalDate.of(2026, 11, 1), ChangeKind.YEARLY, 75000, "Weihnachtsgeld"),
        )
        val dues = ContractSchedule.dues(LocalDate.of(2026, 1, 28), RecurrenceInterval.MONTHLY, 200000, extras, LocalDate.of(2027, 12, 31))
        val byMonth = dues.associate { it.date.monthValue + it.date.year * 100 to it.amountCents }
        assertEquals(200000L, byMonth[202605])
        assertEquals(280000L, byMonth[202606])
        assertEquals(275000L, byMonth[202611])
        assertEquals(280000L, byMonth[202706])
        assertEquals(275000L, byMonth[202711])
        assertEquals(24 * 200000L + 2 * 80000 + 2 * 75000, dues.sumOf { it.amountCents })

        // Ohne Vertragsplan: gleiche Zuschläge in der Planung, bei wöchentlichem Rhythmus nur einmal im Monat.
        val flows = Budget.plannedFlows(
            listOf(RecurringPlan("Gehalt", 200000, RecurrenceInterval.MONTHLY, LocalDate.of(2026, 11, 28), changes = extras)),
            LocalDate.of(2026, 11, 12),
            LocalDate.of(2027, 6, 30),
        )
        assertEquals(listOf(275000L, 200000L, 200000L, 200000L, 200000L, 200000L, 200000L, 280000L), flows.map { it.amountCents })
        val weekly = Budget.plannedFlows(
            listOf(RecurringPlan("Taschengeld", 1000, RecurrenceInterval.WEEKLY, LocalDate.of(2026, 11, 2), changes = extras)),
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 30),
        )
        assertEquals(listOf(76000L, 1000L, 1000L, 1000L, 1000L), weekly.map { it.amountCents })
    }

    @Test
    fun scheduleChangesKeepTheirNote() {
        val changes = listOf(
            ScheduleChange(LocalDate.of(2026, 6, 1), ChangeKind.YEARLY, 81004, "Urlaubsgeld: Juni; brutto"),
            ScheduleChange(LocalDate.of(2026, 3, 15), ChangeKind.ONCE, -10500),
        )
        val decoded = ScheduleChange.decode(ScheduleChange.encode(changes))
        assertEquals(listOf("", "Urlaubsgeld  Juni  brutto"), decoded.map { it.note })
        assertEquals(listOf(ChangeKind.ONCE, ChangeKind.YEARLY), decoded.map { it.kind })
        // Ältere Einträge ohne Bezeichnung bleiben lesbar.
        assertEquals(1, ScheduleChange.decode("2026-05-10:ONCE:-17734").size)
    }
}
