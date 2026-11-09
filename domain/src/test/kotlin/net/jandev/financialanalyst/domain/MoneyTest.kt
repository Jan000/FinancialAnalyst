package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun formatsGermanStyle() {
        assertEquals("1.234,56 €", Money.format(123456))
        assertEquals("-0,05 €", Money.format(-5))
        assertEquals("0,00 €", Money.format(0))
        assertEquals("+12,00 €", Money.format(1200, withSign = true))
        assertEquals("1.000.000,00 €", Money.format(100000000))
    }

    @Test
    fun parsesGermanAndEnglishNotation() {
        assertEquals(123456L, Money.parse("1.234,56"))
        assertEquals(123456L, Money.parse("1,234.56"))
        assertEquals(-1250L, Money.parse("-12,50 €"))
        assertEquals(-1250L, Money.parse("12,50-"))
        assertEquals(99L, Money.parse("0.99"))
        assertEquals(1200L, Money.parse("12"))
        assertEquals(123400L, Money.parse("1.234"))
        assertEquals(1250L, Money.parse("+12,5"))
        assertEquals(-4999L, Money.parse("−49,99 EUR"))
        assertEquals(13L, Money.parse("0,125"))
        assertEquals(1234500L, Money.parse("12,345"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("12,3a"))
    }
}
