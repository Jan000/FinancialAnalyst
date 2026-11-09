package net.jandev.financialanalyst.domain.csv

import net.jandev.financialanalyst.domain.Money
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Ein aus einer Datei gelesener Umsatz, noch ohne Konto. */
data class ImportedTx(
    val date: LocalDate,
    val valueDate: LocalDate?,
    val amountCents: Long,
    val counterparty: String,
    val purpose: String,
    /** Stabil über wiederholte Importe derselben Zeile, damit keine Dubletten entstehen. */
    val externalId: String,
)

enum class BankFormat(val label: String) {
    DKB("DKB"),
    N26("N26"),
    AMEX("American Express"),
    PAYPAL("PayPal"),
    GENERIC("Allgemeines CSV"),
}

/** Welche Spalte welche Bedeutung hat. Indizes beziehen sich auf die Kopfzeile. */
data class ColumnMapping(
    val date: Int,
    /** Eine Betragsspalte mit Vorzeichen … */
    val amount: Int? = null,
    /** … oder getrennte Spalten für Eingang und Ausgang. */
    val credit: Int? = null,
    val debit: Int? = null,
    val counterparty: List<Int> = emptyList(),
    val purpose: List<Int> = emptyList(),
    val valueDate: Int? = null,
    val externalId: Int? = null,
    /** Für Kreditkarten-Exporte, in denen Belastungen positiv stehen. */
    val invertSign: Boolean = false,
) {
    val isUsable: Boolean get() = date >= 0 && (amount != null || credit != null || debit != null)
}

data class CsvAnalysis(
    val format: BankFormat,
    val header: List<String>,
    val rows: List<List<String>>,
    /** Automatisch erkannte Zuordnung; `null`, wenn Datum oder Betrag nicht gefunden wurden. */
    val mapping: ColumnMapping?,
    /** In der Datei genannter Kontostand (z. B. DKB-Kopfzeilen). */
    val statedBalanceCents: Long? = null,
)

data class ConversionResult(
    val transactions: List<ImportedTx>,
    /** Zeilen ohne lesbares Datum oder Betrag. */
    val unreadable: Int,
)

object CsvImporter {

    private val dateHeaders = listOf(
        "buchungsdatum", "buchungstag", "booking date", "datum", "date", "valuta", "wertstellung", "transaktionsdatum",
    )
    private val valueDateHeaders = listOf("wertstellung", "value date", "valuta", "wertstellungstag")
    private val amountHeaders = listOf(
        "betrag (€)", "betrag (eur)", "amount (eur)", "betrag", "amount", "umsatz", "netto", "brutto", "wert",
    )
    private val creditHeaders = listOf("eingang", "gutschrift", "haben", "credit", "zahlungseingang", "einnahme")
    private val debitHeaders = listOf("ausgang", "belastung", "lastschrift", "soll", "debit", "zahlungsausgang", "ausgabe")
    private val counterpartyHeaders = listOf(
        "partner name", "name", "empfänger", "zahlungsempfänger", "auftraggeber", "begünstigter", "beguenstigter",
        "payee", "merchant", "händler", "gegenkonto", "beschreibung", "description", "titel",
    )
    private val purposeHeaders = listOf(
        "verwendungszweck", "payment reference", "reference", "referenz", "buchungstext", "notiz", "note", "typ", "type",
    )
    private val idHeaders = listOf("transaktionscode", "transaction id", "transaktions-id", "id", "referenznummer", "betreff")

    private val datePatterns = listOf(
        "dd.MM.yyyy", "dd.MM.yy", "yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "yyyy/MM/dd", "d.M.yyyy", "d.M.yy",
    ).map(DateTimeFormatter::ofPattern)

    fun parseDate(text: String): LocalDate? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        // Zeitanteile ("2026-11-09T10:15:00", "09.11.2026 10:15") abschneiden.
        val datePart = trimmed.substringBefore('T').substringBefore(' ')
        for (pattern in datePatterns) {
            try {
                return LocalDate.parse(datePart, pattern)
            } catch (_: DateTimeParseException) {
            }
        }
        return null
    }

    fun analyze(text: String): CsvAnalysis {
        val all = Csv.parse(text).filter { row -> row.any { it.isNotBlank() } }
        val headerIndex = findHeaderRow(all)
        if (headerIndex < 0) return CsvAnalysis(BankFormat.GENERIC, emptyList(), emptyList(), null)
        val header = all[headerIndex]
        val rows = all.drop(headerIndex + 1).filter { it.size >= 2 }
        val lower = header.map { it.lowercase().trim() }
        val format = detectFormat(lower)
        val mapping = when (format) {
            BankFormat.DKB -> dkbMapping(lower)
            BankFormat.N26 -> n26Mapping(lower)
            BankFormat.AMEX -> amexMapping(lower)
            BankFormat.PAYPAL -> paypalMapping(lower)
            BankFormat.GENERIC -> null
        } ?: genericMapping(lower)
        val balance = all.take(headerIndex).firstNotNullOfOrNull { row ->
            if (row.size >= 2 && row[0].lowercase().startsWith("kontostand")) Money.parse(row[1]) else null
        }
        return CsvAnalysis(format, header, rows, mapping?.takeIf { it.isUsable }, balance)
    }

    /** Die Kopfzeile ist die erste Zeile, in der eine Datums- und eine Betragsspalte benannt sind. */
    private fun findHeaderRow(rows: List<List<String>>): Int {
        rows.take(40).forEachIndexed { index, row ->
            val lower = row.map { it.lowercase().trim() }
            val hasDate = lower.any { cell -> dateHeaders.any { cell == it || cell.startsWith(it) } }
            val hasAmount = lower.any { cell ->
                (amountHeaders + creditHeaders + debitHeaders).any { cell == it || cell.startsWith(it) }
            }
            if (row.size >= 3 && hasDate && hasAmount) return index
        }
        // Keine erkennbare Kopfzeile: breiteste der ersten Zeilen nehmen, der Nutzer ordnet dann von Hand zu.
        val widest = rows.take(40).maxOfOrNull { it.size } ?: return -1
        if (widest < 2) return -1
        return rows.indexOfFirst { it.size == widest }
    }

    private fun detectFormat(header: List<String>): BankFormat = when {
        header.any { it.startsWith("zahlungspflichtige") } && header.any { it.startsWith("zahlungsempfänger") } -> BankFormat.DKB
        "transaktionscode" in header && "brutto" in header -> BankFormat.PAYPAL
        "partner name" in header || ("payment reference" in header && "booking date" in header) -> BankFormat.N26
        "beschreibung" in header && "datum" in header && header.any { it == "karteninhaber" || it == "konto #" || it == "erscheint auf ihrer abrechnung als" } -> BankFormat.AMEX
        else -> BankFormat.GENERIC
    }

    private fun List<String>.col(vararg names: String): Int? =
        names.firstNotNullOfOrNull { name -> indexOfFirst { it == name || it.startsWith(name) }.takeIf { it >= 0 } }

    private fun dkbMapping(h: List<String>): ColumnMapping? {
        val date = h.col("buchungsdatum") ?: return null
        val amount = h.col("betrag") ?: return null
        return ColumnMapping(
            date = date,
            amount = amount,
            // Welche der beiden Namensspalten die Gegenpartei ist, hängt vom Vorzeichen ab; siehe counterpartyOf().
            counterparty = listOfNotNull(h.col("zahlungsempfänger"), h.col("zahlungspflichtige")),
            purpose = listOfNotNull(h.col("verwendungszweck")),
            valueDate = h.col("wertstellung"),
        )
    }

    private fun n26Mapping(h: List<String>): ColumnMapping? {
        val date = h.col("booking date", "datum") ?: return null
        val amount = h.col("amount (eur)", "betrag (eur)", "amount", "betrag") ?: return null
        return ColumnMapping(
            date = date,
            amount = amount,
            counterparty = listOfNotNull(h.col("partner name", "empfänger")),
            purpose = listOfNotNull(h.col("payment reference", "verwendungszweck")),
            valueDate = h.col("value date"),
        )
    }

    private fun amexMapping(h: List<String>): ColumnMapping? {
        val date = h.col("datum") ?: return null
        val amount = h.col("betrag") ?: return null
        return ColumnMapping(
            date = date,
            amount = amount,
            counterparty = listOfNotNull(h.col("beschreibung")),
            purpose = listOfNotNull(h.col("weitere details", "erscheint auf ihrer abrechnung als")),
            externalId = h.col("betreff", "referenz"),
            invertSign = true,
        )
    }

    private fun paypalMapping(h: List<String>): ColumnMapping? {
        val date = h.col("datum") ?: return null
        val amount = h.col("netto", "brutto") ?: return null
        return ColumnMapping(
            date = date,
            amount = amount,
            counterparty = listOfNotNull(h.col("name")),
            purpose = listOfNotNull(h.col("beschreibung"), h.col("artikelbezeichnung"), h.col("hinweis")),
            externalId = h.col("transaktionscode"),
        )
    }

    fun genericMapping(h: List<String>): ColumnMapping? {
        val date = h.col(*dateHeaders.toTypedArray()) ?: return null
        val amount = h.col(*amountHeaders.toTypedArray())
        val credit = h.col(*creditHeaders.toTypedArray())
        val debit = h.col(*debitHeaders.toTypedArray())
        if (amount == null && credit == null && debit == null) return null
        val counterparty = h.col(*counterpartyHeaders.toTypedArray())
        val purpose = h.col(*purposeHeaders.toTypedArray())?.takeIf { it != counterparty }
        return ColumnMapping(
            date = date,
            amount = amount,
            credit = if (amount == null) credit else null,
            debit = if (amount == null) debit else null,
            counterparty = listOfNotNull(counterparty),
            purpose = listOfNotNull(purpose),
            valueDate = h.col(*valueDateHeaders.toTypedArray())?.takeIf { it != date },
            externalId = h.col(*idHeaders.toTypedArray()),
        )
    }

    fun convert(analysis: CsvAnalysis, mapping: ColumnMapping): ConversionResult {
        val result = ArrayList<ImportedTx>()
        var unreadable = 0
        val seen = HashMap<String, Int>()
        val header = analysis.header.map { it.lowercase() }
        val currencyColumn = header.indexOfFirst { it == "währung" || it == "currency" }
        val typeColumn = header.indexOfFirst { it == "beschreibung" || it == "typ" }

        for (row in analysis.rows) {
            fun cell(index: Int?): String = index?.let { row.getOrNull(it) }.orEmpty()

            val date = parseDate(cell(mapping.date))
            var amount = when {
                mapping.amount != null -> Money.parse(cell(mapping.amount))
                else -> {
                    val credit = Money.parse(cell(mapping.credit))
                    val debit = Money.parse(cell(mapping.debit))
                    when {
                        credit != null && credit != 0L -> kotlin.math.abs(credit)
                        debit != null && debit != 0L -> -kotlin.math.abs(debit)
                        credit != null || debit != null -> 0L
                        else -> null
                    }
                }
            }
            if (date == null || amount == null) {
                unreadable++
                continue
            }
            if (amount == 0L) continue
            if (analysis.format == BankFormat.PAYPAL) {
                // Fremdwährungszeilen und reine Autorisierungen bewegen das Euro-Guthaben nicht.
                if (currencyColumn >= 0 && cell(currencyColumn).isNotBlank() && cell(currencyColumn) != "EUR") continue
                if (typeColumn >= 0 && cell(typeColumn).contains("Autorisierung", ignoreCase = true)) continue
            }
            if (mapping.invertSign) amount = -amount

            val counterparty = counterpartyOf(analysis.format, mapping, row, amount)
            val purpose = mapping.purpose.map { cell(it) }.filter { it.isNotBlank() && it != counterparty }
                .distinct().joinToString(" · ")
            val bankId = cell(mapping.externalId).takeIf { it.isNotBlank() }
            val externalId = if (bankId != null) {
                "id:$bankId"
            } else {
                // Gleiche Zeilen am selben Tag (zwei identische Kaffees) bekommen eine laufende Nummer.
                val base = "${date.toEpochDay()}|$amount|${counterparty.lowercase()}|${purpose.lowercase()}"
                val occurrence = seen.merge(base, 1, Int::plus)!!
                "csv:${fingerprint(base)}#$occurrence"
            }
            result.add(
                ImportedTx(
                    date = date,
                    valueDate = parseDate(cell(mapping.valueDate)),
                    amountCents = amount,
                    counterparty = counterparty,
                    purpose = purpose,
                    externalId = externalId,
                ),
            )
        }
        return ConversionResult(result, unreadable)
    }

    private fun counterpartyOf(format: BankFormat, mapping: ColumnMapping, row: List<String>, amount: Long): String {
        val cells = mapping.counterparty.map { row.getOrNull(it).orEmpty().trim() }
        if (format == BankFormat.DKB && cells.size == 2) {
            // Reihenfolge laut dkbMapping(): [Empfänger, Zahlungspflichtige].
            val preferred = if (amount < 0) cells[0] else cells[1]
            return preferred.ifBlank { cells.firstOrNull { it.isNotBlank() }.orEmpty() }
        }
        return cells.firstOrNull { it.isNotBlank() }.orEmpty()
    }

    /** 64-Bit-FNV-1a als kurzer, stabiler Fingerabdruck. */
    private fun fingerprint(text: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(hash)
    }
}
