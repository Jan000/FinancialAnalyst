package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AnalysisTest {
    private val groceries = 1L
    private val salary = 2L
    private val subscriptions = 3L
    private val transfer = 4L
    private val kinds = mapOf(
        groceries to CategoryKind.EXPENSE,
        salary to CategoryKind.INCOME,
        subscriptions to CategoryKind.EXPENSE,
        transfer to CategoryKind.TRANSFER,
    )
    private val kindOf: (Long?) -> CategoryKind? = { kinds[it] }

    private val categorizer = Categorizer(
        listOf(
            CategoryRule("rewe", groceries, CategoryKind.EXPENSE),
            CategoryRule("gehalt", salary, CategoryKind.INCOME),
            CategoryRule("amazon", 9, CategoryKind.EXPENSE),
            CategoryRule("amazon prime", subscriptions, CategoryKind.EXPENSE),
            CategoryRule("o2 ", 8, CategoryKind.EXPENSE),
            CategoryRule("rewe to go", 7, CategoryKind.EXPENSE, userDefined = true),
        ),
    )

    @Test
    fun longestKeywordWinsAndUserRulesBeatBuiltins() {
        assertEquals(groceries, categorizer.categorize("REWE Markt GmbH", "", -1000))
        assertEquals(subscriptions, categorizer.categorize("AMAZON PRIME*AB12", "", -899))
        assertEquals(9L, categorizer.categorize("Amazon.de", "Bestellung", -2500))
        assertEquals(7L, categorizer.categorize("REWE To Go", "", -350))
    }

    @Test
    fun incomeRulesOnlyMatchIncomingMoney() {
        assertEquals(salary, categorizer.categorize("Beispiel AG", "Gehalt November", 250000))
        assertNull(categorizer.categorize("Kollege", "Anteil Gehaltsfeier", -2000))
    }

    @Test
    fun refundsStayInTheExpenseCategory() {
        assertEquals(groceries, categorizer.categorize("REWE", "Erstattung", 500))
    }

    @Test
    fun trailingSpacePatternMatchesAtEndOfText() {
        assertEquals(8L, categorizer.categorize("Telefonica", "o2", -2999))
        assertNull(categorizer.categorize("Solo2go", "", -2999))
    }

    @Test
    fun merchantKeyDropsNumbersAndLegalForms() {
        assertEquals("rewe markt", Categorizer.merchantKey("REWE Markt GmbH 4711"))
        assertEquals("spotify", Categorizer.merchantKey("Spotify AB"))
        assertEquals("lidl berlin", Categorizer.merchantKey("LIDL*BERLIN/0123"))
    }

    private fun tx(id: Long, date: String, cents: Long, name: String, category: Long? = null, transfer: Boolean = false) =
        Tx(id, 1, LocalDate.parse(date), cents, name, categoryId = category, isTransfer = transfer)

    private val sample = listOf(
        tx(1, "2026-11-01", 250000, "Beispiel AG", salary),
        tx(2, "2026-11-03", -4000, "REWE Markt GmbH", groceries),
        tx(3, "2026-11-05", -6000, "REWE Markt 22", groceries),
        tx(4, "2026-11-06", 1000, "REWE", groceries),
        tx(5, "2026-11-07", -1099, "Spotify AB", subscriptions),
        tx(6, "2026-11-08", -50000, "Mein Tagesgeld", transfer),
        tx(7, "2026-11-09", -2000, "Unbekannt"),
        tx(8, "2026-11-09", -30000, "Depot", null, transfer = true),
        tx(9, "2026-10-31", -9999, "Vormonat", groceries),
    )

    @Test
    fun summaryIgnoresTransfersAndNetsRefunds() {
        val summary = Analytics.summarize(sample, Periods.rangeOf(Period.MONTH, LocalDate.of(2026, 11, 9)), kindOf)
        assertEquals(250000L, summary.incomeCents)
        assertEquals(9000L + 1099L + 2000L, summary.expenseCents)
        assertEquals(
            listOf(CategoryTotal(groceries, 9000, 3), CategoryTotal(null, 2000, 1), CategoryTotal(subscriptions, 1099, 1)),
            summary.expensesByCategory,
        )
        assertEquals("REWE Markt GmbH", summary.topMerchants.first().name)
        assertEquals(10000L, summary.topMerchants.first().cents)
        assertEquals(6, summary.transactionCount)
    }

    @Test
    fun periodRanges() {
        val date = LocalDate.of(2026, 11, 9)
        assertEquals(DateRange(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15)), Periods.rangeOf(Period.WEEK, date))
        assertEquals(DateRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31)), Periods.rangeOf(Period.QUARTER, date))
        assertEquals(DateRange(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), Periods.rangeOf(Period.YEAR, date))
        assertEquals("4. Quartal 2026", Periods.label(Period.QUARTER, date))
        assertEquals("November 2026", Periods.label(Period.MONTH, date))
        assertEquals(LocalDate.of(2026, 2, 28), Periods.rangeOf(Period.MONTH, LocalDate.of(2026, 2, 10)).endInclusive)
    }

    @Test
    fun historyCoversTrailingPeriods() {
        val history = Analytics.history(sample, Period.MONTH, LocalDate.of(2026, 11, 9), 3, kindOf)
        assertEquals(listOf("Sept", "Okt", "Nov"), history.map { it.label.take(4).trimEnd('.') }.map { if (it.startsWith("Sep")) "Sept" else it })
        assertEquals(listOf(0L, 9999L, 12099L), history.map { it.expenseCents })
    }

    @Test
    fun detectsTransfersOnlyWithEvidence() {
        val accounts = listOf(
            OwnAccount(1, "DE00111111110000000001", listOf("dkb")),
            OwnAccount(2, "DE00222222220000000002", listOf("trade republic")),
        )
        val day = LocalDate.of(2026, 11, 5)
        val candidates = listOf(
            TransferCandidate(10, 1, day, -50000, "Trade Republic Bank", "Einzahlung"),
            TransferCandidate(11, 2, day.plusDays(1), 50000, "Max Mustermann", ""),
            // Gleich hoch, aber ohne jeden Bezug zueinander: Einkauf hier, Geschenk dort.
            TransferCandidate(12, 1, day, -5000, "Schuhladen", "Kartenzahlung"),
            TransferCandidate(13, 2, day, 5000, "Oma", "Alles Gute"),
            // Zu weit auseinander.
            TransferCandidate(14, 1, day, -7000, "Trade Republic Bank", ""),
            TransferCandidate(15, 2, day.plusDays(9), 7000, "Max Mustermann", ""),
        )
        assertEquals(listOf(10L to 11L), TransferDetector.detect(accounts, candidates))
    }
}
