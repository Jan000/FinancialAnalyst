package net.jandev.financialanalyst.data

import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.data.db.RecurringEntity
import net.jandev.financialanalyst.data.db.RecurringIndex
import net.jandev.financialanalyst.data.db.SplitEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.Budget
import net.jandev.financialanalyst.domain.CapitalForecast
import net.jandev.financialanalyst.domain.CapitalProjection
import java.time.LocalDate
import java.time.YearMonth

/** Konto je Dauerposten: das hinterlegte, sonst das Konto der jüngsten Buchung, die zum Posten gehört. */
object RecurringAccounts {
    fun resolve(items: List<RecurringEntity>, transactions: List<TransactionEntity>): Map<Long, Long?> {
        val missing = items.filter { it.accountId == null }
        val found = HashMap<Long, Pair<LocalDate, Long>>()
        if (missing.isNotEmpty()) {
            val index = RecurringIndex(missing)
            for (tx in transactions) {
                if (tx.transferPeerId != null) continue
                val item = index.find(tx) ?: continue
                val known = found[item.id]
                if (known == null || tx.bookingDate > known.first) found[item.id] = tx.bookingDate to tx.accountId
            }
        }
        return items.associate { it.id to (it.accountId ?: found[it.id]?.second) }
    }
}

/** Ein laufender Kredit, dessen Rate als Dauerposten geführt wird: endet laut Tilgungsplan. */
data class LoanEnd(val name: String, val monthlyPaymentCents: Long, val payoff: LocalDate)

data class CapitalResult(
    val projection: CapitalProjection,
    /** Gleiche Rechnung mit der Sparvorgabe; `null` ohne Vorgabe. */
    val scenario: CapitalProjection?,
    /** Tagesdurchschnitt der übrigen Ausgaben (negativ) und Einnahmen (positiv). */
    val otherExpensePerDayCents: Long,
    val otherIncomePerDayCents: Long,
    val includeOtherIncome: Boolean,
    /** Durchschnitt pro Monat laut Dauerposten, ohne Vorzeichen. */
    val fixedIncomeMonthlyCents: Long,
    val fixedCostMonthlyCents: Long,
    val overdraftRatePercent: Double,
    /** Summe der Dispo-Rahmen aller Konten. */
    val overdraftLimitCents: Long,
    /** Kredite, deren Rate im Zeitraum endet, mit dem zugehörigen Dauerposten. */
    val endingLoans: List<Pair<RecurringEntity, LoanEnd>>,
    /** Erster bekannter Umsatz; der Durchschnitt beruht auf den Tagen seither, höchstens [windowDays]. */
    val windowDays: Long,
)

/** Stellt die Daten für die Kapitalprognose zusammen. */
object CapitalCalculator {
    /** Durchschnittliche Länge eines Monats in Tagen, für die Umrechnung der Sparvorgabe. */
    private const val DAYS_PER_MONTH = 30.436875

    fun compute(
        today: LocalDate,
        accounts: List<AccountWithBalance>,
        transactions: List<TransactionEntity>,
        recurring: List<RecurringEntity>,
        splits: List<SplitEntity>,
        transferCategoryIds: Set<Long>,
        loans: List<LoanEnd>,
        months: Int,
        windowDays: Long,
        includeOtherIncome: Boolean,
        savingsPerMonthCents: Long,
    ): CapitalResult {
        val active = recurring.filter { it.active && !it.dismissed }
        val end = YearMonth.from(today).plusMonths(months.toLong()).atEndOfMonth()

        // Kreditraten enden mit dem Tilgungsplan; erkannt am gleichen Betrag.
        val loanOf = active.filter { !it.isPosition && it.amountCents < 0 }.mapNotNull { item ->
            loans.firstOrNull { it.monthlyPaymentCents == -item.amountCents }?.let { item.id to it }
        }.toMap()
        val flows = active.flatMap { item ->
            val until = loanOf[item.id]?.payoff?.let { minOf(it, end) } ?: end
            Budget.plannedFlows(listOf(item.plan()), today, until)
        }

        // Übrige Bewegungen: weder Dauerposten noch Umbuchung zwischen eigenen Konten. Positionen eines Spar-Abos
        // stecken in ihrer Sammelabrechnung und zählen schon als Fixkosten.
        val index = RecurringIndex(active)
        val positionIds = active.filter { it.isPosition }.map { it.id }.toSet()
        val positionShare = splits.filter { it.recurringId in positionIds }.groupBy { it.parentId }
            .mapValues { entry -> entry.value.sumOf { it.amountCents } }
        val other = transactions
            .filter { it.transferPeerId == null && it.categoryId !in transferCategoryIds && index.find(it) == null }
            .map { it.bookingDate to it.amountCents - (positionShare[it.id] ?: 0) }
            .filter { it.second != 0L }
        val firstKnown = transactions.minOfOrNull { it.bookingDate }
        val expensePerDay = CapitalForecast.perDay(other.filter { it.second < 0 }, today, windowDays, firstKnown)
        val incomePerDay = CapitalForecast.perDay(other.filter { it.second > 0 }, today, windowDays, firstKnown)
        val perDay = expensePerDay + if (includeOtherIncome) incomePerDay else 0

        val rate = accounts.filter { it.account.overdraftLimitCents > 0 }.maxOfOrNull { it.account.overdraftRatePercent } ?: 0.0
        val limit = accounts.sumOf { it.account.overdraftLimitCents }
        val start = accounts.sumOf { it.balanceCents }
        val projection = CapitalForecast.project(today, start, flows, perDay, months, rate, limit)
        val scenario = if (savingsPerMonthCents <= 0) null else {
            CapitalForecast.project(today, start, flows, perDay + Math.round(savingsPerMonthCents / DAYS_PER_MONTH), months, rate, limit)
        }
        val span = if (firstKnown == null) 0 else minOf(windowDays, today.toEpochDay() - firstKnown.toEpochDay() + 1)
        return CapitalResult(
            projection = projection,
            scenario = scenario,
            otherExpensePerDayCents = expensePerDay,
            otherIncomePerDayCents = incomePerDay,
            includeOtherIncome = includeOtherIncome,
            fixedIncomeMonthlyCents = active.filter { it.amountCents > 0 }.sumOf { it.yearlyTotalCents } / 12,
            fixedCostMonthlyCents = active.filter { it.amountCents < 0 }.sumOf { it.yearlyTotalCents } / 12,
            overdraftRatePercent = rate,
            overdraftLimitCents = limit,
            endingLoans = active.mapNotNull { item -> loanOf[item.id]?.takeIf { it.payoff <= end }?.let { item to it } },
            windowDays = span,
        )
    }
}
