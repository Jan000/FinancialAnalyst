package net.jandev.financialanalyst.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Abweichung eines einzelnen Termins vom regulären Zahlungsplan eines Vertrags. */
enum class ChangeKind(val label: String) {
    /** Nur an diesem Termin ein anderer Betrag, etwa ein Rabatt auf den ersten Beitrag. */
    ONCE("Anderer Betrag nur für diesen Termin"),

    /** Ab diesem Termin gilt dauerhaft ein neuer Betrag (Beitragserhöhung). */
    FROM_HERE("Neuer Betrag ab diesem Termin"),

    /** Der Termin entfällt (Beitragspause, beitragsfreier Monat). */
    SKIP("Termin entfällt"),

    /** Bezahlt, ohne dass eine Buchung dazu kommt (bar, über ein anderes Konto). */
    SETTLED("Ohne Buchung bezahlt"),
}

/** @param amountCents mit Vorzeichen wie der Vertrag; nur für [ChangeKind.ONCE] und [ChangeKind.FROM_HERE] von Bedeutung. */
data class ScheduleChange(val date: LocalDate, val kind: ChangeKind, val amountCents: Long = 0) {
    companion object {
        /** Kompakte Ablage in einer Textspalte: `2026-05-10:ONCE:-17734;…`. Unlesbare Teile werden übergangen. */
        fun encode(changes: List<ScheduleChange>): String =
            changes.sortedBy { it.date }.joinToString(";") { "${it.date}:${it.kind.name}:${it.amountCents}" }

        fun decode(text: String): List<ScheduleChange> = text.split(';').mapNotNull { part ->
            val fields = part.split(':')
            if (fields.size != 3) return@mapNotNull null
            val date = runCatching { LocalDate.parse(fields[0]) }.getOrNull() ?: return@mapNotNull null
            val kind = ChangeKind.entries.firstOrNull { it.name == fields[1] } ?: return@mapNotNull null
            ScheduleChange(date, kind, fields[2].toLongOrNull() ?: 0)
        }
    }
}

/** Ein Termin des Zahlungsplans. */
data class Installment(
    val date: LocalDate,
    val amountCents: Long,
    /** Datum der zugeordneten Buchung; `null`, solange offen. */
    val paidOn: LocalDate? = null,
    val paidCents: Long? = null,
    val settledManually: Boolean = false,
) {
    val isOpen: Boolean get() = paidOn == null && !settledManually
}

/**
 * Zahlungsplan eines Vertrags (Versicherung, Mitgliedschaft, Abo mit festen Terminen): alle Termine ab dem ersten
 * laut Vertrag, Abweichungen einzelner Termine und welche Buchung welchen Termin bezahlt hat.
 * Anders als bei erkannten Dauerposten bleibt ein ausgebliebener Beitrag offen, bis er abgebucht ist.
 */
object ContractSchedule {
    /** So viele Tage vor dem Termin darf eine Buchung schon kommen und zählt trotzdem für ihn. */
    const val EARLY_DAYS = 7L

    /** Der [index]-te Termin; Monatsrhythmen rechnen vom ersten Termin aus, damit der Tag nicht wandert. */
    fun dateOf(firstDue: LocalDate, interval: RecurrenceInterval, index: Int): LocalDate =
        if (interval.months > 0) firstDue.plusMonths(interval.months.toLong() * index) else firstDue.plusDays(interval.days.toLong() * index)

    /** Termine von [firstDue] bis [until] samt Betrag; entfallene sind nicht enthalten. */
    fun dues(
        firstDue: LocalDate,
        interval: RecurrenceInterval,
        amountCents: Long,
        changes: List<ScheduleChange>,
        until: LocalDate,
    ): List<Installment> {
        val byDate = changes.associateBy { it.date }
        val raises = changes.filter { it.kind == ChangeKind.FROM_HERE }.sortedBy { it.date }
        val result = ArrayList<Installment>()
        var index = 0
        while (index < 2400) {
            val date = dateOf(firstDue, interval, index++)
            if (date > until) break
            val change = byDate[date]
            if (change?.kind == ChangeKind.SKIP) continue
            val regular = raises.lastOrNull { it.date <= date }?.amountCents ?: amountCents
            result.add(
                Installment(
                    date = date,
                    amountCents = if (change?.kind == ChangeKind.ONCE) change.amountCents else regular,
                    settledManually = change?.kind == ChangeKind.SETTLED,
                ),
            )
        }
        return result
    }

    /**
     * Ordnet die Buchungen der Reihe nach dem jeweils ältesten offenen Termin zu – auch verspätete. Eine Buchung zählt
     * für einen Termin, wenn sie höchstens [EARLY_DAYS] vor ihm liegt und das Vorzeichen stimmt.
     */
    fun allocate(dues: List<Installment>, bookings: List<Pair<LocalDate, Long>>): List<Installment> {
        val pending = bookings.sortedBy { it.first }.toMutableList()
        return dues.map { due ->
            if (!due.isOpen) return@map due
            val index = pending.indexOfFirst { (date, cents) ->
                (cents < 0) == (due.amountCents < 0) && cents != 0L && date >= due.date.minusDays(EARLY_DAYS)
            }
            if (index < 0) due else pending.removeAt(index).let { (date, cents) -> due.copy(paidOn = date, paidCents = cents) }
        }
    }

    /** Erster offener Termin; ohne offenen der nächste nach dem letzten Termin bis [until]. */
    fun nextOpen(
        firstDue: LocalDate,
        interval: RecurrenceInterval,
        amountCents: Long,
        changes: List<ScheduleChange>,
        bookings: List<Pair<LocalDate, Long>>,
        today: LocalDate,
    ): LocalDate {
        // Weit genug voraus, dass auch Buchungen für künftige Termine (Vorauszahlung) ihren Platz finden.
        val horizon = maxOf(today, bookings.maxOfOrNull { it.first } ?: today).plusYears(2)
        val plan = allocate(dues(firstDue, interval, amountCents, changes, horizon), bookings)
        return plan.firstOrNull { it.isOpen }?.date ?: horizon
    }

    /** Offene Termine ab [nextDue] bis [until]: überfällige zuerst, ihr Betrag gilt bis zur Abbuchung als fällig. */
    fun openDues(plan: RecurringPlan, until: LocalDate): List<Installment> {
        val first = plan.firstDue ?: return emptyList()
        return dues(first, plan.interval, plan.amountCents, plan.changes, until)
            .filter { it.date >= plan.nextDue && !it.settledManually }
    }

    /** Wie viele Tage ein Termin überfällig ist. */
    fun daysLate(date: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(date, today)
}
