package net.jandev.financialanalyst.domain

import java.time.LocalDate
import kotlin.math.abs

/** Bereits gespeicherter Umsatz, soweit der Abgleich ihn braucht. */
data class ExistingTx(
    val id: Long,
    val date: LocalDate,
    val amountCents: Long,
    val externalId: String?,
    val source: TxSource,
    val status: TxStatus,
)

/** Neu gelieferter Umsatz aus Import, Bankabruf oder Benachrichtigung. */
data class IncomingTx<T>(
    val date: LocalDate,
    val amountCents: Long,
    val externalId: String?,
    val payload: T,
)

data class Reconciliation<T>(
    /** Wirklich neue Umsätze. */
    val toInsert: List<T>,
    /** Vorhandene vorläufige oder manuelle Einträge, die durch die gelieferte Buchung bestätigt werden. */
    val toConfirm: List<Pair<Long, T>>,
    /** Schon vorhanden, nichts zu tun. */
    val duplicates: Int,
)

/**
 * Gleicht gelieferte Umsätze mit dem Bestand eines Kontos ab, damit derselbe Umsatz nie doppelt erscheint,
 * egal ob er zuerst per Benachrichtigung, von Hand, per Datei oder per Bankabruf kam.
 */
object Reconciler {
    /** Buchungsdatum der Bank und Tag der Kartenzahlung liegen oft ein paar Tage auseinander. */
    const val DATE_TOLERANCE_DAYS = 4L

    fun <T> reconcile(
        existing: List<ExistingTx>,
        incoming: List<IncomingTx<T>>,
        incomingSource: TxSource,
    ): Reconciliation<T> {
        val knownIds = existing.mapNotNull { it.externalId }.toHashSet()
        val claimed = HashSet<Long>()
        val toInsert = ArrayList<T>()
        val toConfirm = ArrayList<Pair<Long, T>>()
        var duplicates = 0

        for (tx in incoming) {
            if (tx.externalId != null && !knownIds.add(tx.externalId)) {
                duplicates++
                continue
            }
            val match = existing
                .asSequence()
                .filter { it.id !in claimed && it.amountCents == tx.amountCents && it.source != incomingSource }
                .filter {
                    if (incomingSource == TxSource.NOTIFICATION) {
                        // Eine Benachrichtigung kommt vor der Buchung: nur ein heute von Hand erfasster Eintrag kann derselbe Umsatz sein.
                        it.source == TxSource.MANUAL && it.date == tx.date
                    } else {
                        abs(it.date.toEpochDay() - tx.date.toEpochDay()) <= DATE_TOLERANCE_DAYS
                    }
                }
                .minByOrNull { abs(it.date.toEpochDay() - tx.date.toEpochDay()) }
            when {
                match == null -> toInsert.add(tx.payload)
                // Von der Bank bereits fest gebuchte Umsätze aus einer anderen Quelle: derselbe Umsatz, nichts tun.
                match.status == TxStatus.BOOKED && match.source in setOf(TxSource.API, TxSource.IMPORT) -> {
                    claimed.add(match.id)
                    duplicates++
                }
                // Eine Benachrichtigung bestätigt nichts, sie ist selbst nur vorläufig.
                incomingSource == TxSource.NOTIFICATION -> {
                    claimed.add(match.id)
                    duplicates++
                }
                else -> {
                    claimed.add(match.id)
                    toConfirm.add(match.id to tx.payload)
                }
            }
        }
        return Reconciliation(toInsert, toConfirm, duplicates)
    }
}
