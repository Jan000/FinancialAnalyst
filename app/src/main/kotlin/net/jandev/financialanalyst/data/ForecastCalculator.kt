package net.jandev.financialanalyst.data

import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.data.db.RecurringEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.AccountType
import net.jandev.financialanalyst.domain.Budget
import net.jandev.financialanalyst.domain.MonthEnd
import net.jandev.financialanalyst.domain.MonthEndForecast
import net.jandev.financialanalyst.domain.RecurringDetector
import net.jandev.financialanalyst.domain.RecurringPlan
import java.time.LocalDate

data class AccountForecast(val account: AccountWithBalance, val forecast: MonthEndForecast)

/** Stellt je Konto die Daten für die Hochrechnung zum Monatsende zusammen. */
object ForecastCalculator {
    fun compute(
        today: LocalDate,
        accounts: List<AccountWithBalance>,
        transactions: List<TransactionEntity>,
        recurring: List<RecurringEntity>,
        /** Kategorien der Art „Umbuchung". */
        transferCategoryIds: Set<Long>,
    ): List<AccountForecast> {
        val monthEnd = today.withDayOfMonth(today.lengthOfMonth())
        val byAccount = transactions.groupBy { it.accountId }
        return accounts.map { item ->
            val own = byAccount[item.account.id].orEmpty()
            if (item.account.type == AccountType.PAYPAL) {
                // PayPal zieht jeden Kauf sofort vom Bankkonto nach: der Stand bleibt, wie er ist.
                // Die Abbuchungen dafür stecken in den Bewegungen des Bankkontos.
                return@map AccountForecast(
                    item,
                    MonthEnd.forecast(today, item.account.openingBalanceCents, own.map { it.bookingDate to it.amountCents }, emptyList(), 0, 0.0),
                )
            }
            val stored = recurring.filter { it.active && !it.dismissed && it.accountId == item.account.id }
                .map { it.matchKey to RecurringPlan(it.displayName, it.amountCents, it.interval, it.nextDue) }
            // Regelmäßige Umbuchungen (etwa der monatliche Übertrag aufs Mietkonto) sind keine Dauerposten der
            // Auswertung, verändern aber den Stand dieses Kontos: hier eigens erkennen.
            val moves = RecurringDetector.detect(
                own.filter { it.transferPeerId != null || it.categoryId in transferCategoryIds }.map { it.toTx().copy(isTransfer = false) },
                today,
            ).map { it.matchKey to RecurringPlan(it.name, it.amountCents, it.interval, it.nextDue) }
            val fixedKeys = (stored + moves).map { it.first }.toSet()
            val flows = Budget.plannedFlows((stored + moves).map { it.second }, today, monthEnd).map { it.date to it.amountCents }
            // Alles, was kein Dauerposten ist – auch Umbuchungen, denn sie verändern den Stand dieses Kontos.
            val other = own.filter { RecurringDetector.matchKey(it.toTx()) !in fixedKeys }.map { it.bookingDate to it.amountCents }
            AccountForecast(
                item,
                MonthEnd.forecast(
                    today = today,
                    openingCents = item.account.openingBalanceCents,
                    transactions = own.map { it.bookingDate to it.amountCents },
                    flows = flows,
                    otherPerDayCents = MonthEnd.otherPerDay(today, other, own.minOfOrNull { it.bookingDate }),
                    ratePercent = item.account.overdraftRatePercent,
                ),
            )
        }
    }
}
