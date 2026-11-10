package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Fälle, die erst mit echten Bankdaten aufgefallen sind (hier mit erfundenen Namen nachgestellt). */
class RealDataRegressionTest {
    private val day = LocalDate.of(2026, 10, 1)

    @Test
    fun learnedRuleMatchesTheSamePayeeDespitePunctuation() {
        val key = Categorizer.learnKey("XY/ENERGIE GmbH", "Kd.Nr. 123", 1, -3000)
        assertEquals("xy energie", key)
        val categorizer = Categorizer(listOf(CategoryRule(key, 5, CategoryKind.EXPENSE, userDefined = true)))
        assertEquals(5L, categorizer.categorize("XY/ENERGIE GmbH", "Kd.Nr. 999 Abschlag", -3100, 1))
    }

    @Test
    fun learnedRuleDoesNotSpillOverToALongerPayeeName() {
        val categorizer = Categorizer(listOf(CategoryRule("erika muster", 5, CategoryKind.EXPENSE, userDefined = true)))
        assertEquals(5L, categorizer.categorize("Erika Muster", "", -5000, 1))
        assertNull(categorizer.categorize("Erika Muster und Max Muster", "", -29350, 1))
    }

    @Test
    fun paymentsWithoutAnyTextAreRecognisedByAccountAndAmount() {
        val key = Categorizer.learnKey("", "", 6, -77500)
        assertEquals("=6:-77500", key)
        val categorizer = Categorizer(listOf(CategoryRule(key, 3, CategoryKind.EXPENSE, userDefined = true)))
        assertEquals(3L, categorizer.categorize("", "", -77500, 6))
        assertNull(categorizer.categorize("", "", -77500, 5))
        assertNull(categorizer.categorize("", "", -1000, 6))
        // Ohne Händler zählt der Verwendungszweck.
        assertEquals("miete", Categorizer.learnKey("", "Miete", 6, -77500))
    }

    @Test
    fun recurringPaymentsWithoutTextAreDetected() {
        val txs = (0L..3L).map { Tx(it, 6, day.plusMonths(it), -77500, "", "") }
        val found = RecurringDetector.detect(txs, day.plusMonths(3).plusDays(2)).single()
        assertEquals("=6:-77500|-", found.matchKey)
        assertEquals(RecurrenceInterval.MONTHLY, found.interval)
        assertEquals("Zahlung ohne Buchungstext", found.name)
    }

    @Test
    fun bankDebitOfAWalletPurchaseIsPairedWithThePurchase() {
        val candidates = listOf(
            // Zahlungskonto (7): Kauf beim Händler; Bankkonto (5): Abbuchung desselben Betrags zwei Tage später.
            TransferCandidate(1, 7, day, -1299, "Musikdienst AB", ""),
            TransferCandidate(2, 5, day.plusDays(2), -1299, "PayPal Europe S.a.r.l. et Cie S.C.A", "Ihr Einkauf bei Musikdienst"),
            // Gleicher Betrag, aber kein PayPal im Text.
            TransferCandidate(3, 7, day, -500, "Kiosk", ""),
            TransferCandidate(4, 5, day, -500, "Bäckerei", ""),
            // PayPal-Abbuchung ohne passenden Kauf: bleibt eine Ausgabe.
            TransferCandidate(5, 5, day, -899, "PAYPAL *FILMDIENST", ""),
            // Zu weit auseinander.
            TransferCandidate(6, 7, day, -2000, "Laden", ""),
            TransferCandidate(8, 5, day.plusDays(9), -2000, "PayPal Europe", ""),
        )
        assertEquals(listOf(2L to 1L), TransferDetector.detectWalletFunding(mapOf(7L to "paypal"), candidates))
        // Ein schon zugeordneter Kauf wird nicht noch einmal vergeben.
        assertEquals(emptyList<Pair<Long, Long>>(), TransferDetector.detectWalletFunding(mapOf(7L to "paypal"), candidates, taken = setOf(1L)))
    }

    @Test
    fun movesBetweenSubAccountsOfOneBankArePairedWithoutCounterparty() {
        val accounts = listOf(
            OwnAccount(1, "", listOf("beispielbank"), group = 10),
            OwnAccount(2, "", listOf("beispielbank"), group = 10),
            OwnAccount(3, "", listOf("andere"), group = 11),
        )
        val candidates = listOf(
            TransferCandidate(1, 1, day, -10000, "", "Urlaub"),
            TransferCandidate(2, 2, day, 10000, "", "Urlaub"),
            TransferCandidate(3, 1, day, -4000, "", "Essen"),
            TransferCandidate(4, 3, day, 4000, "", "Danke"),
        )
        assertEquals(listOf(1L to 2L), TransferDetector.detect(accounts, candidates))
    }
}

class PayPalTextTest {
    @Test
    fun readsTheMerchantFromThePurpose() {
        assertEquals(
            "Beispielnetz (PayPal)",
            PayPalText.merchant("PayPal Europe S.a.r.l. et Cie S.C.A", "1050148826527/. Beispielnetz, Ihr Einkauf bei Beispielnetz"),
        )
        assertEquals(
            "Laden GmbH (PayPal)",
            PayPalText.merchant("PayPal Europe S.a.r.l. et Cie S.C.A", ". Laden GmbH, Ihr Einkauf bei Laden GmbH/ABBUCHUNG VOM PAYPAL-KONTO"),
        )
        assertNull(PayPalText.merchant("PayPal Europe S.a.r.l. et Cie S.C.A", "1048327967161/PP.9070.PP/. , Ihr Einkauf bei"))
        assertNull(PayPalText.merchant("Laden GmbH", "Ihr Einkauf bei Laden"))
    }

    @Test
    fun recognisesPayoutsOfTheBalance() {
        assertEquals(true, PayPalText.isPayout("PAYPAL", "INSTANT TRANSFER", 5000))
        assertEquals(false, PayPalText.isPayout("PAYPAL", "INSTANT TRANSFER", -5000))
        assertEquals(false, PayPalText.isPayout("Laden (PayPal)", "Erstattung", 500))
    }
}

class LoanVariablePaymentsTest {
    private val start = LocalDate.of(2026, 1, 1)

    @Test
    fun rateChangesApplyFromTheirDate() {
        val terms = LoanTerms(
            principalCents = 100000, ratePercent = 0.0, monthlyPaymentCents = 10000, firstPayment = start,
            // März und April Pause, ab Mai doppelte Rate.
            rateChanges = listOf(RateChange(start.plusMonths(2), 0), RateChange(start.plusMonths(4), 20000)),
        )
        val rows = LoanCalculator.schedule(terms).rows
        assertEquals(listOf(10000L, 10000L, 0L, 0L, 20000L, 20000L, 20000L, 20000L), rows.map { it.paymentCents })
        assertEquals(0L, rows.last().remainingCents)
    }

    @Test
    fun actualPaymentsReplaceThePlanInThePast() {
        val today = start.plusMonths(3).plusDays(10)
        val terms = LoanTerms(
            principalCents = 100000, ratePercent = 0.0, monthlyPaymentCents = 10000, firstPayment = start,
            // Mal 50 €, mal nichts, mal 200 €.
            actualPayments = listOf(
                ExtraPayment(start, 5000),
                ExtraPayment(start.plusMonths(2).minusDays(3), 20000),
                ExtraPayment(start.plusMonths(3), 5000),
            ),
            actualUntil = today,
        )
        val schedule = LoanCalculator.schedule(terms)
        assertEquals(listOf(5000L, 0L, 20000L, 5000L), schedule.rows.take(4).map { it.paymentCents })
        val status = LoanCalculator.status(terms, schedule, today)
        assertEquals(70000L, status.remainingCents)
        // Danach läuft es mit der geplanten Rate weiter: 700 € in sieben Raten.
        assertEquals(4 + 7, schedule.rows.size)
    }

    @Test
    fun aPaymentAlreadyMadeForTheCurrentInstalmentIsNotCountedTwice() {
        val today = start.plusDays(20)
        val terms = LoanTerms(
            principalCents = 30000, ratePercent = 0.0, monthlyPaymentCents = 10000, firstPayment = start,
            actualPayments = listOf(ExtraPayment(start, 10000), ExtraPayment(start.plusDays(15), 10000)),
            actualUntil = today,
        )
        val rows = LoanCalculator.schedule(terms).rows
        assertEquals(listOf(10000L, 10000L, 10000L), rows.map { it.paymentCents })
    }
}
