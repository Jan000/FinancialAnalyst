package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Die Texte sind typischen Banking-Pushs nachempfunden, nicht aus echten Apps kopiert. */
class NotificationParserTest {

    private fun parse(title: String, text: String) = NotificationParser.parse(title, text)

    @Test
    fun cardPaymentAtMerchant() {
        assertEquals(ParsedNotification(-1234, "REWE Markt"), parse("Zahlung", "Du hast 12,34 € bei REWE Markt bezahlt."))
        assertEquals(ParsedNotification(-4599, "Lidl"), parse("Kartenzahlung", "Kartenzahlung über 45,99 EUR bei Lidl"))
        assertEquals(ParsedNotification(-123456, "Möbelhaus"), parse("", "Zahlung von 1.234,56 € bei Möbelhaus"))
    }

    @Test
    fun amountBeforeOrAfterCurrencySymbol() {
        assertEquals(-999L, parse("Payment", "You paid €9.99 at Coffee Bar")?.amountCents)
        assertEquals("Coffee Bar", parse("Payment", "You paid €9.99 at Coffee Bar")?.counterparty)
        assertEquals(-500L, parse("Zahlung", "5 € an Max gesendet")?.amountCents)
    }

    @Test
    fun incomingMoney() {
        assertEquals(ParsedNotification(250000, "Beispiel AG"), parse("Geldeingang", "Du hast 2.500,00 € von Beispiel AG erhalten"))
        assertEquals(1500L, parse("Erstattung", "15,00 € wurden dir für deine Zahlung bei Shop erstattet")?.amountCents)
    }

    @Test
    fun merchantFromTitleWhenTextNamesNone() {
        assertEquals(ParsedNotification(-799, "Bäckerei Beispiel"), parse("Bäckerei Beispiel", "Kartenzahlung: 7,99 €"))
        assertEquals("", parse("Kartenzahlung", "Kartenzahlung: 7,99 €")?.counterparty)
    }

    @Test
    fun ignoresNonPaymentNotifications() {
        assertNull(parse("Angebot", "Jetzt 50 € Prämie sichern!"))
        assertNull(parse("Kursalarm", "Beispiel-Aktie steht bei 123,45 €"))
        assertNull(parse("Hinweis", "Dein Kontoauszug ist da"))
        assertNull(parse("Zahlung", "Deine Zahlung wurde abgelehnt"))
    }

    @Test
    fun explicitMinusBeatsIncomeWords() {
        assertEquals(-2000L, parse("Umsatz", "-20,00 € Lastschrift von Stadtwerke eingegangen")?.amountCents)
    }
}
