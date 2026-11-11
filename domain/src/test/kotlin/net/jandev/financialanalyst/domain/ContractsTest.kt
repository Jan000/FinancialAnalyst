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
}
