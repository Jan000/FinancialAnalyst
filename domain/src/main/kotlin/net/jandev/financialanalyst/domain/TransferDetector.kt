package net.jandev.financialanalyst.domain

import java.time.LocalDate
import kotlin.math.abs

data class OwnAccount(
    val id: Long,
    val iban: String,
    /** Kleingeschriebene Wörter, an denen das Konto in einem Buchungstext erkennbar ist (Bank, Kontoname). */
    val hints: List<String>,
    /** Konten derselben Bankverbindung (Haupt- und Unterkonten) tragen dieselbe Gruppe. */
    val group: Long? = null,
)

data class TransferCandidate(
    val id: Long,
    val accountId: Long,
    val date: LocalDate,
    val amountCents: Long,
    val counterparty: String,
    val purpose: String,
)

/**
 * Findet Umbuchungen zwischen eigenen Konten: ein Ausgang und ein gleich hoher Eingang auf einem anderen
 * eigenen Konto innerhalb weniger Tage. Damit zwei zufällig gleich hohe, unabhängige Buchungen nicht
 * verheiratet werden, muss zusätzlich ein Hinweis im Text stehen.
 */
object TransferDetector {
    const val MAX_DAYS_APART = 4L
    const val WALLET_DAYS_APART = 5L
    private val keywords = listOf(
        "umbuchung", "übertrag", "uebertrag", "eigenes konto", "kreditkartenabrechnung", "kartenabrechnung",
        "einzahlung", "auszahlung", "aufladung", "top-up", "top up", "guthaben", "sparen",
    )

    fun detect(accounts: List<OwnAccount>, candidates: List<TransferCandidate>): List<Pair<Long, Long>> {
        val byId = accounts.associateBy { it.id }
        val incoming = candidates.filter { it.amountCents > 0 }.groupBy { it.amountCents }
        val used = HashSet<Long>()
        val pairs = ArrayList<Pair<Long, Long>>()

        for (out in candidates.filter { it.amountCents < 0 }.sortedBy { it.date }) {
            val match = incoming[-out.amountCents].orEmpty()
                .asSequence()
                .filter { it.id !in used && it.accountId != out.accountId }
                .filter { abs(it.date.toEpochDay() - out.date.toEpochDay()) <= MAX_DAYS_APART }
                .filter { hasEvidence(out, it, byId[out.accountId], byId[it.accountId]) }
                .minByOrNull { abs(it.date.toEpochDay() - out.date.toEpochDay()) }
                ?: continue
            used.add(match.id)
            pairs.add(out.id to match.id)
        }
        return pairs
    }

    private fun hasEvidence(out: TransferCandidate, incoming: TransferCandidate, from: OwnAccount?, to: OwnAccount?): Boolean {
        val outText = "${out.counterparty} ${out.purpose}".lowercase()
        val inText = "${incoming.counterparty} ${incoming.purpose}".lowercase()
        // Der Ausgang nennt das Zielkonto oder der Eingang das Quellkonto.
        if (to != null && mentions(outText, to)) return true
        if (from != null && mentions(inText, from)) return true
        // Verschiebungen zwischen Haupt- und Unterkonto derselben Bank nennen keine Gegenpartei.
        if (from?.group != null && from.group == to?.group && out.counterparty.isBlank() && incoming.counterparty.isBlank()) return true
        // Eigene Überweisungen tragen auf beiden Seiten denselben Namen (den des Kontoinhabers).
        val a = out.counterparty.trim().lowercase()
        if (a.length >= 4 && a == incoming.counterparty.trim().lowercase()) return true
        return keywords.any { it in outText || it in inText }
    }

    /**
     * Käufe über ein Zahlungskonto wie PayPal erscheinen zweimal: dort mit dem Händler und auf dem Bankkonto
     * als Abbuchung des Zahlungsdienstes über denselben Betrag. Liefert zu jeder solchen Abbuchung (oder
     * Erstattung) den Umsatz auf dem Zahlungskonto, damit die Bankseite nur noch als Umbuchung zählt.
     *
     * @param wallets Zahlungskonto → kleingeschriebener Name des Dienstes, wie er im Bankumsatz steht ("paypal")
     * @param taken Umsätze auf Zahlungskonten, die schon einer Bankbuchung zugeordnet sind
     * @return Paare (Bankumsatz, Umsatz auf dem Zahlungskonto)
     */
    fun detectWalletFunding(
        wallets: Map<Long, String>,
        candidates: List<TransferCandidate>,
        taken: Set<Long> = emptySet(),
    ): List<Pair<Long, Long>> {
        if (wallets.isEmpty()) return emptyList()
        val used = HashSet(taken)
        val pairs = ArrayList<Pair<Long, Long>>()
        val walletSide = candidates.filter { it.accountId in wallets }.groupBy { it.amountCents }
        for (bank in candidates.filter { it.accountId !in wallets }.sortedBy { it.date }) {
            val text = "${bank.counterparty} ${bank.purpose}".lowercase()
            val match = walletSide[bank.amountCents].orEmpty()
                .asSequence()
                .filter { it.id !in used && text.contains(wallets.getValue(it.accountId)) }
                // Die Bank bucht meist am selben Tag oder wenige Tage später.
                .filter { abs(it.date.toEpochDay() - bank.date.toEpochDay()) <= WALLET_DAYS_APART }
                .minByOrNull { abs(it.date.toEpochDay() - bank.date.toEpochDay()) }
                ?: continue
            used.add(match.id)
            pairs.add(bank.id to match.id)
        }
        return pairs
    }

    private fun mentions(text: String, account: OwnAccount): Boolean {
        val compact = text.replace(" ", "")
        if (account.iban.length >= 15 && compact.contains(account.iban.lowercase())) return true
        return account.hints.any { it.length >= 3 && text.contains(it) }
    }
}

/** Liest aus den Bankbuchungen von PayPal heraus, was dahintersteckt. */
object PayPalText {
    private val purchase = Regex("ihr einkauf bei\\s+(.+?)\\s*(/|$)", RegexOption.IGNORE_CASE)

    /**
     * PayPal bucht Käufe als „PayPal Europe S.a.r.l." ab und nennt den Händler nur im Verwendungszweck
     * („…, Ihr Einkauf bei Händler"). Liefert dafür „Händler (PayPal)", sonst `null`.
     */
    fun merchant(counterparty: String, purpose: String): String? {
        if (!counterparty.trim().startsWith("paypal europe", ignoreCase = true)) return null
        val name = purchase.find(purpose)?.groupValues?.get(1)?.trim().orEmpty()
        return if (name.length >= 2) "$name (PayPal)" else null
    }

    /** Auszahlung des PayPal-Guthabens aufs Bankkonto: Geld, das nur den Ort wechselt. */
    fun isPayout(counterparty: String, purpose: String, amountCents: Long): Boolean =
        amountCents > 0 && counterparty.trim().equals("paypal", ignoreCase = true) ||
            amountCents > 0 && counterparty.contains("paypal", ignoreCase = true) && purpose.contains("instant transfer", ignoreCase = true)
}
