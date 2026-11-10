package net.jandev.financialanalyst.domain

data class ParsedNotification(val amountCents: Long, val counterparty: String)

data class BankingApp(val packageName: String, val label: String)

/**
 * Liest Betrag, Richtung und Händler aus Push-Benachrichtigungen von Banking-Apps.
 * Die Wortlaute der Apps ändern sich; deshalb wird nicht je App ein festes Muster erwartet, sondern
 * allgemein nach einem Eurobetrag, einem Zahlungswort und einem Händler hinter „bei/an/von" gesucht.
 * Das Ergebnis ist immer nur ein vorläufiger Umsatz, den die echte Buchung später ersetzt.
 */
object NotificationParser {

    val knownApps = listOf(
        BankingApp("de.number26.android", "N26"),
        BankingApp("de.traderepublic.app", "Trade Republic"),
        BankingApp("com.dkbcodefactory.banking", "DKB"),
        BankingApp("de.dkb.portalapp", "DKB (alte App)"),
        BankingApp("com.paypal.android.p2pmobile", "PayPal"),
        BankingApp("com.americanexpress.android.acctsvcs.de", "American Express"),
        BankingApp("com.google.android.apps.walletnfcrel", "Google Wallet"),
        BankingApp("de.ingdiba.bankingapp", "ING"),
        BankingApp("com.starfinanz.smob.android.sfinanzstatus", "Sparkasse"),
        BankingApp("de.comdirect.android", "comdirect"),
        BankingApp("com.revolut.revolut", "Revolut"),
    )

    private const val NUMBER = """(\d{1,3}(?:[.  ]\d{3})+|\d+)(?:[.,](\d{1,2}))?"""
    private val amountPatterns = listOf(
        Regex("""(-)?\s?$NUMBER\s?(?:€|EUR\b|Euro\b)""", RegexOption.IGNORE_CASE),
        Regex("""(?:€|EUR)\s?(-)?\s?$NUMBER""", RegexOption.IGNORE_CASE),
    )

    private val incomeWords = listOf(
        "erhalten", "eingegangen", "geldeingang", "zahlungseingang", "gutschrift", "gutgeschrieben", "erstattet",
        "erstattung", "rückzahlung", "received", "refund", "hat dir", "hat ihnen", "sendet dir", "eingang",
    )
    private val expenseWords = listOf(
        "bezahlt", "zahlung", "kartenzahlung", "belastung", "belastet", "abgebucht", "abbuchung", "lastschrift",
        "gesendet", "überwiesen", "überweisung", "ausgegeben", "gekauft", "einkauf", "paid", "payment", "purchase",
        "spent", "charged", "abgehoben", "auszahlung", "umsatz",
    )

    /** Wörter, hinter denen der Händler bzw. Absender steht; die Reihenfolge ist die Priorität. */
    private val counterpartyMarkers = listOf(" bei ", " an ", " von ", " at ", " to ", " from ", " für ")
    private val genericTitles = setOf(
        "zahlung", "kartenzahlung", "neue zahlung", "neuer umsatz", "umsatz", "transaktion", "payment", "geldeingang",
        "zahlungseingang", "lastschrift", "überweisung", "gutschrift", "belastung",
    )

    fun parse(title: String, text: String): ParsedNotification? {
        val full = listOf(title, text).filter { it.isNotBlank() }.joinToString(". ")
        val lower = full.lowercase()
        val match = amountPatterns.firstNotNullOfOrNull { it.find(full) } ?: return null
        val isIncome = incomeWords.any { it in lower }
        val isExpense = expenseWords.any { it in lower }
        // Ohne Zahlungswort ist es Werbung, ein Kurs-Alarm oder Ähnliches.
        if (!isIncome && !isExpense) return null

        val whole = match.groupValues[2].filter(Char::isDigit)
        val fraction = match.groupValues[3].padEnd(2, '0')
        val cents = (whole.toLongOrNull() ?: return null) * 100 + (fraction.toLongOrNull() ?: 0)
        if (cents == 0L) return null
        val explicitMinus = match.groupValues[1] == "-"
        // „Erstattung für deine Zahlung bei X": das Einnahmewort gewinnt, außer der Betrag trägt ein Minus.
        val income = isIncome && !explicitMinus

        return ParsedNotification(
            amountCents = if (income) cents else -cents,
            counterparty = counterparty(full, match.range, title),
        )
    }

    private fun counterparty(full: String, amountRange: IntRange, title: String): String {
        val padded = " $full "
        for (marker in counterpartyMarkers) {
            var index = padded.indexOf(marker, ignoreCase = true)
            while (index >= 0) {
                val start = index + marker.length
                val candidate = padded.substring(start)
                    .takeWhile { it !in ".!?\n|" && it != '(' }
                    .replace(Regex("""\s+(bezahlt|gezahlt|gesendet|erhalten|überwiesen|ausgegeben|abgebucht|wurde|ist|am|per|mit|über|in Höhe)\b.*""", RegexOption.IGNORE_CASE), "")
                    .trim(' ', ',', ':', ';')
                // Kein Händler, wenn dort nur der Betrag steht („Zahlung von 12,34 €").
                val overlapsAmount = (start - 1) <= amountRange.last && (start - 1 + candidate.length) > amountRange.first
                if (candidate.length in 2..60 && candidate.any(Char::isLetter) && !overlapsAmount && !candidate.first().isDigit()) {
                    return candidate
                }
                index = padded.indexOf(marker, index + 1, ignoreCase = true)
            }
        }
        val cleanTitle = title.trim()
        return if (cleanTitle.isNotEmpty() && cleanTitle.lowercase() !in genericTitles && cleanTitle.none(Char::isDigit)) cleanTitle else ""
    }
}
