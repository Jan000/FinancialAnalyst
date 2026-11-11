package net.jandev.financialanalyst.domain

import java.time.LocalDate

enum class AccountType(val label: String) {
    GIRO("Girokonto"),
    CREDIT_CARD("Kreditkarte"),
    SAVINGS("Tagesgeld / Sparkonto"),
    BROKER("Depot / Verrechnungskonto"),
    PAYPAL("PayPal"),
    CASH("Bargeld"),
}

/** Woher ein Konto seine Umsätze bekommt. */
enum class ConnectorType(val label: String) {
    MANUAL("Manuell"),
    CSV("Datei-Import"),
    ENABLE_BANKING("Bankabruf"),
}

enum class TxStatus { PENDING, BOOKED }

enum class TxSource { MANUAL, IMPORT, API, NOTIFICATION }

enum class CategoryKind { EXPENSE, INCOME, TRANSFER }

enum class RecurrenceInterval(val label: String, val months: Int, val days: Int) {
    WEEKLY("wöchentlich", 0, 7),
    BIWEEKLY("14-tägig", 0, 14),
    MONTHLY("monatlich", 1, 0),
    BIMONTHLY("alle 2 Monate", 2, 0),
    QUARTERLY("vierteljährlich", 3, 0),
    FOUR_MONTHLY("alle 4 Monate", 4, 0),
    FIVE_MONTHLY("alle 5 Monate", 5, 0),
    HALF_YEARLY("halbjährlich", 6, 0),
    YEARLY("jährlich", 12, 0);

    /** Nächster Termin nach [date]. */
    fun next(date: LocalDate): LocalDate =
        if (months > 0) date.plusMonths(months.toLong()) else date.plusDays(days.toLong())

    /** Umrechnung eines Betrags pro Intervall auf einen durchschnittlichen Monat. */
    fun monthlyCents(amountCents: Long): Long = when (this) {
        WEEKLY -> amountCents * 52 / 12
        BIWEEKLY -> amountCents * 26 / 12
        else -> amountCents / months
    }

    /** Was ein Betrag pro Intervall in einem Jahr ausmacht. */
    fun yearlyCents(amountCents: Long): Long = when (this) {
        WEEKLY -> amountCents * 52
        BIWEEKLY -> amountCents * 26
        else -> amountCents * 12 / months
    }
}

/** Schlanke Sicht auf einen Umsatz, auf der die gesamte Finanzlogik arbeitet. */
data class Tx(
    val id: Long,
    val accountId: Long,
    val date: LocalDate,
    val amountCents: Long,
    val counterparty: String,
    val purpose: String = "",
    val categoryId: Long? = null,
    /** Umbuchung zwischen eigenen Konten; zählt weder als Einnahme noch als Ausgabe. */
    val isTransfer: Boolean = false,
)
