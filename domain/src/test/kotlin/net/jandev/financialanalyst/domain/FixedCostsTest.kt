package net.jandev.financialanalyst.domain

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FixedCostsTest {
    @Test
    fun builtinKeysMapToAreas() {
        assertEquals(CostArea.HOUSING, CostArea.of("utilities", "Nebenkosten"))
        assertEquals(CostArea.DIGITAL, CostArea.of("subscriptions", "Abos"))
        assertEquals(CostArea.LOANS, CostArea.of("loans", null))
    }

    @Test
    fun customCategoriesUseTheirName() {
        assertEquals(CostArea.DIGITAL, CostArea.of(null, "Server"))
        assertEquals(CostArea.LIVING, CostArea.of(null, "Katzen"))
        assertEquals(CostArea.OTHER, CostArea.of(null, "Allerlei"))
        assertEquals(CostArea.OTHER, CostArea.of(null, null))
    }

    @Test
    fun familiesRecogniseServices() {
        assertEquals(ServiceFamily.CLOUD, ServiceFamily.of("Google Payment Ireland", "Google One"))
        assertEquals(ServiceFamily.CLOUD, ServiceFamily.of("OneDrive Speicher"))
        assertEquals(ServiceFamily.VIDEO, ServiceFamily.of("Netflix.com"))
        assertEquals(ServiceFamily.VIDEO, ServiceFamily.of("Amazon Prime"))
        assertEquals(ServiceFamily.MUSIC, ServiceFamily.of("Spotify AB"))
        assertEquals(ServiceFamily.MOBILE, ServiceFamily.of("fraenk - eine Marke der Telekom"))
        assertNull(ServiceFamily.of("Telekom Glasfaser 300"))
        assertNull(ServiceFamily.of("VATTENFALL EUROPE SALES"))
    }

    @Test
    fun monthlyTotalsBucketExactly() {
        val from = YearMonth.of(2026, 11)
        val payments = listOf(
            LocalDate.of(2026, 11, 3) to -1999L,
            LocalDate.of(2026, 11, 30) to -1L,
            LocalDate.of(2027, 1, 10) to -2280L,
            LocalDate.of(2026, 10, 31) to -500L,
            LocalDate.of(2027, 2, 1) to -100L,
        )
        assertEquals(listOf(2000L, 0L, 2280L), FixedCostCalendar.monthlyTotals(payments, from, 3))
    }
}
