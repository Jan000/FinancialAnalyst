package net.jandev.financialanalyst.domain.csv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Alle Dateien hier sind erfunden und bilden nur den Aufbau der jeweiligen Exporte nach. */
class CsvImporterTest {

    private fun import(text: String): Pair<CsvAnalysis, List<ImportedTx>> {
        val analysis = CsvImporter.analyze(text)
        val mapping = assertNotNull(analysis.mapping).let { analysis.mapping!! }
        return analysis to CsvImporter.convert(analysis, mapping).transactions
    }

    @Test
    fun parsesQuotedFieldsWithDelimitersAndNewlines() {
        val rows = Csv.parse("a;\"b;c\";\"d \"\"e\"\"\"\r\n1;\"zwei\nzeilen\";3\n")
        assertEquals(listOf(listOf("a", "b;c", "d \"e\""), listOf("1", "zwei\nzeilen", "3")), rows)
    }

    @Test
    fun dkbExportWithMetadataLines() {
        val text = """
            "Girokonto";"DE00123456780000000000"
            ""
            "Kontostand vom 09.11.2026:";"1.234,56 €"
            ""
            "Buchungsdatum";"Wertstellung";"Status";"Zahlungspflichtige*r";"Zahlungsempfänger*in";"Verwendungszweck";"Umsatztyp";"IBAN";"Betrag (€)";"Gläubiger-ID";"Mandatsreferenz";"Kundenreferenz"
            "06.11.26";"06.11.26";"Gebucht";"Max Mustermann";"REWE Markt GmbH";"Einkauf 05.11.";"Ausgang";"DE001";"-45,67";"";"";""
            "01.11.26";"01.11.26";"Gebucht";"Beispiel AG";"Max Mustermann";"Gehalt November";"Eingang";"DE002";"2.500,00";"";"";""
        """.trimIndent()
        val (analysis, txs) = import(text)
        assertEquals(BankFormat.DKB, analysis.format)
        assertEquals(123456L, analysis.statedBalanceCents)
        assertEquals(2, txs.size)
        assertEquals(LocalDate.of(2026, 11, 6), txs[0].date)
        assertEquals(-4567L, txs[0].amountCents)
        assertEquals("REWE Markt GmbH", txs[0].counterparty)
        assertEquals("Einkauf 05.11.", txs[0].purpose)
        assertEquals(250000L, txs[1].amountCents)
        assertEquals("Beispiel AG", txs[1].counterparty)
    }

    @Test
    fun n26Export() {
        val text = """
            "Booking Date","Value Date","Partner Name","Partner Iban",Type,"Payment Reference","Account Name","Amount (EUR)","Original Amount","Original Currency","Exchange Rate"
            2026-11-03,2026-11-03,"Spotify AB",,Presentment,"Spotify Premium","Hauptkonto",-10.99,,,
            2026-11-02,2026-11-02,"Erika Musterfrau","DE003",Credit Transfer,"Danke fürs Auslegen","Hauptkonto",25.0,,,
        """.trimIndent()
        val (analysis, txs) = import(text)
        assertEquals(BankFormat.N26, analysis.format)
        assertEquals(listOf(-1099L, 2500L), txs.map { it.amountCents })
        assertEquals("Spotify AB", txs[0].counterparty)
        assertEquals("Spotify Premium", txs[0].purpose)
        assertEquals(LocalDate.of(2026, 11, 3), txs[0].date)
    }

    @Test
    fun amexChargesArePositiveInTheFileButExpensesForUs() {
        val text = """
            Datum,Beschreibung,Karteninhaber,Konto #,Betrag
            04/11/2026,"AMAZON.DE*AB12CD",MAX MUSTERMANN,-12345,"23,49"
            01/11/2026,"ZAHLUNG ERHALTEN. BESTEN DANK",MAX MUSTERMANN,-12345,"-500,00"
        """.trimIndent()
        val (analysis, txs) = import(text)
        assertEquals(BankFormat.AMEX, analysis.format)
        assertEquals(listOf(-2349L, 50000L), txs.map { it.amountCents })
        assertEquals(LocalDate.of(2026, 11, 4), txs[0].date)
    }

    @Test
    fun paypalSkipsAuthorizationsAndForeignCurrency() {
        val text = """
            "Datum","Uhrzeit","Zeitzone","Beschreibung","Währung","Brutto","Entgelt","Netto","Guthaben","Transaktionscode","Absender E-Mail-Adresse","Name","Name der Bank","Bankkonto"
            "05.11.2026","10:00:00","Europe/Berlin","Allgemeine Autorisierung","EUR","-19,99","0,00","-19,99","0,00","AUTH1","","Beispielshop","",""
            "05.11.2026","10:00:05","Europe/Berlin","Handyzahlung","EUR","-19,99","0,00","-19,99","-19,99","PAY1","","Beispielshop","",""
            "05.11.2026","10:00:06","Europe/Berlin","Bankgutschrift auf PayPal-Konto","EUR","19,99","0,00","19,99","0,00","PAY2","","","Beispielbank","1234"
            "04.11.2026","09:00:00","Europe/Berlin","Zahlung","USD","-5,00","0,00","-5,00","-5,00","PAY3","","US Shop","",""
        """.trimIndent()
        val (analysis, txs) = import(text)
        assertEquals(BankFormat.PAYPAL, analysis.format)
        assertEquals(listOf("id:PAY1", "id:PAY2"), txs.map { it.externalId })
        assertEquals("Beispielshop", txs[0].counterparty)
        assertEquals("Handyzahlung", txs[0].purpose)
    }

    @Test
    fun genericFileWithSeparateCreditAndDebitColumns() {
        val text = """
            Datum;Typ;Beschreibung;Eingang;Ausgang;Saldo
            05.11.2026;Kartentransaktion;Bäckerei Beispiel;;3,20;996,80
            01.11.2026;Überweisung;Einzahlung;1000,00;;1000,00
        """.trimIndent()
        val (analysis, txs) = import(text)
        assertEquals(BankFormat.GENERIC, analysis.format)
        assertEquals(listOf(-320L, 100000L), txs.map { it.amountCents })
        assertEquals("Bäckerei Beispiel", txs[0].counterparty)
        assertEquals("Kartentransaktion", txs[0].purpose)
    }

    @Test
    fun identicalRowsGetDistinctButStableIds() {
        val text = """
            Datum;Name;Betrag
            05.11.2026;Kaffeebar;-2,50
            05.11.2026;Kaffeebar;-2,50
        """.trimIndent()
        val first = import(text).second
        val second = import(text).second
        assertNotEquals(first[0].externalId, first[1].externalId)
        assertEquals(first.map { it.externalId }, second.map { it.externalId })
    }

    @Test
    fun unknownColumnsNeedManualMapping() {
        val analysis = CsvImporter.analyze("Spalte A;Spalte B;Spalte C\n05.11.2026;Irgendwer;-2,50\n")
        assertNull(analysis.mapping)
        assertEquals(listOf("Spalte A", "Spalte B", "Spalte C"), analysis.header)
        val manual = ColumnMapping(date = 0, amount = 2, counterparty = listOf(1))
        val result = CsvImporter.convert(analysis, manual)
        assertEquals(-250L, result.transactions.single().amountCents)
    }

    @Test
    fun countsUnreadableRows() {
        val analysis = CsvImporter.analyze("Datum;Name;Betrag\nkein datum;X;-2,50\n05.11.2026;Y;-1,00\n")
        val result = CsvImporter.convert(analysis, analysis.mapping!!)
        assertEquals(1, result.unreadable)
        assertEquals(1, result.transactions.size)
    }
}
