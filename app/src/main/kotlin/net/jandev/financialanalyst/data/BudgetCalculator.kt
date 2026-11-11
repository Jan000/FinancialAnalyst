package net.jandev.financialanalyst.data

import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.data.db.RecurringEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.Budget
import net.jandev.financialanalyst.domain.BudgetResult
import net.jandev.financialanalyst.domain.RecurringDetector
import net.jandev.financialanalyst.domain.RecurringPlan
import java.time.LocalDate

/** Führt Konten, heutige Umsätze, Dauerposten und Einstellungen zum Tagesbudget zusammen. */
object BudgetCalculator {

    fun compute(
        today: LocalDate,
        settings: Settings,
        accounts: List<AccountWithBalance>,
        transactions: List<TransactionEntity>,
        recurring: List<RecurringEntity>,
    ): BudgetResult? {
        val budgetAccounts = accounts.filter { it.account.includeInBudget }
        if (budgetAccounts.isEmpty()) return null
        val accountIds = budgetAccounts.map { it.account.id }.toSet()
        // Positionen einer Sammelabrechnung belasten das Konto erst mit der Abrechnung selbst.
        val active = recurring.filter { it.active && !it.dismissed && !it.isPosition && (it.accountId == null || it.accountId in accountIds) }
        val fixedKeys = active.map { it.matchKey }.toSet()

        // Heutige Buchungen, die keine Dauerposten sind: das ist das, was man heute „frei" ausgegeben hat.
        val todayVariableNet = transactions
            .asSequence()
            .filter { it.bookingDate == today && it.accountId in accountIds }
            .filter { RecurringDetector.matchKey(it.toTx()) !in fixedKeys }
            .sumOf { it.amountCents }

        val period = Budget.period(today, settings.budgetStartDay)
        val flows = Budget.plannedFlows(
            active.map { RecurringPlan(it.displayName, it.amountCents, it.interval, it.nextDue) },
            today,
            period.endInclusive,
        )
        return Budget.calculate(
            today = today,
            period = period,
            balanceCents = budgetAccounts.sumOf { it.balanceCents },
            todayVariableNetCents = todayVariableNet,
            flows = flows,
            reserveCents = settings.reserveCents,
        )
    }
}
