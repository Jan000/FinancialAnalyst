package net.jandev.financialanalyst.domain

import java.time.LocalDate
import kotlin.math.abs

data class OwnAccount(
    val id: Long,
    val iban: String,
    /** Kleingeschriebene Wörter, an denen das Konto in einem Buchungstext erkennbar ist (Bank, Kontoname). */
    val hints: List<String>,
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
        // Eigene Überweisungen tragen auf beiden Seiten denselben Namen (den des Kontoinhabers).
        val a = out.counterparty.trim().lowercase()
        if (a.length >= 4 && a == incoming.counterparty.trim().lowercase()) return true
        return keywords.any { it in outText || it in inText }
    }

    private fun mentions(text: String, account: OwnAccount): Boolean {
        val compact = text.replace(" ", "")
        if (account.iban.length >= 15 && compact.contains(account.iban.lowercase())) return true
        return account.hints.any { it.length >= 3 && text.contains(it) }
    }
}
