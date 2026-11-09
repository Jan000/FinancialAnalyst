package net.jandev.financialanalyst.ui.loans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.Overdraft
import net.jandev.financialanalyst.domain.OverdraftReport
import net.jandev.financialanalyst.domain.Period
import net.jandev.financialanalyst.domain.Periods
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.toPercentText
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate
import kotlin.math.roundToLong

private data class AccountOverdraft(
    val quarter: OverdraftReport,
    val previousQuarter: OverdraftReport,
    val year: OverdraftReport,
)

@Composable
fun OverdraftScreen(onBack: () -> Unit, onEditAccount: (Long) -> Unit) {
    val app = LocalApp.current
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = null)
    val today = LocalDate.now()

    Screen(title = "Dispozinsen", onBack = onBack) { padding ->
        val list = accounts ?: return@Screen
        if (list.isEmpty()) {
            EmptyState(
                title = "Noch keine Konten",
                hint = "Lege ein Konto an und trage dort Dispo-Rahmen und Dispozins ein.",
                modifier = Modifier.padding(padding),
            )
            return@Screen
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Die Zinsen werden taggenau aus deinen Umsätzen geschätzt (negativer Tagesendsaldo × Zinssatz ÷ 365). " +
                    "Die Bank rechnet meist quartalsweise ab und kann leicht abweichen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            list.forEach { item -> AccountOverdraftCard(item, today, onEditAccount) }
            Calculator(list)
        }
    }
}

@Composable
private fun AccountOverdraftCard(item: AccountWithBalance, today: LocalDate, onEditAccount: (Long) -> Unit) {
    val app = LocalApp.current
    val account = item.account
    val finance = LocalFinanceColors.current
    val report by produceState<AccountOverdraft?>(null, account, item.balanceCents, today) {
        val txs = app.repository.transactionsOf(account.id).map { it.bookingDate to it.amountCents }
        fun range(from: LocalDate, to: LocalDate) = Overdraft.report(
            account.openingBalanceCents, txs, from, to, account.overdraftRatePercent, account.overdraftLimitCents,
        )
        val quarter = Periods.rangeOf(Period.QUARTER, today)
        val previous = Periods.rangeOf(Period.QUARTER, today.minusMonths(3))
        value = AccountOverdraft(
            quarter = range(quarter.start, today),
            previousQuarter = range(previous.start, previous.endInclusive),
            year = range(today.withDayOfYear(1), today),
        )
    }

    SectionCard(title = account.name) {
        LabeledRow("Kontostand") {
            AmountText(item.balanceCents, withSign = false, colored = item.balanceCents < 0, negativeIsAlarming = true)
        }
        if (account.overdraftRatePercent <= 0.0) {
            Text(
                "Für dieses Konto ist noch kein Dispozins hinterlegt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { onEditAccount(account.id) }, modifier = Modifier.align(Alignment.End)) {
                Text("Dispozins eintragen")
            }
            return@SectionCard
        }
        LabeledRow("Dispozins") { Text("${account.overdraftRatePercent.toPercentText()} % p. a.") }
        if (account.overdraftLimitCents > 0) {
            LabeledRow("Dispo-Rahmen") { AmountText(account.overdraftLimitCents, withSign = false, colored = false) }
            LabeledRow("Verfügbar inkl. Dispo") {
                AmountText(
                    item.balanceCents + account.overdraftLimitCents,
                    withSign = false,
                    colored = item.balanceCents + account.overdraftLimitCents < 0,
                    negativeIsAlarming = true,
                )
            }
        }
        if (item.balanceCents < 0) {
            val perDay = Overdraft.dailyInterest(item.balanceCents, account.overdraftRatePercent)
            Text(
                "Das Konto ist im Minus. Das kostet aktuell ${Money.format(perDay.roundToLong())} pro Tag " +
                    "bzw. ${Money.format((perDay * 30).roundToLong())} pro Monat.",
                color = finance.expense,
            )
            if (account.overdraftLimitCents > 0 && item.balanceCents < -account.overdraftLimitCents) {
                Text(
                    "Der Dispo-Rahmen ist überschritten. Dafür berechnen Banken oft einen höheren Überziehungszins.",
                    color = finance.expense,
                )
            }
        }
        val r = report ?: return@SectionCard
        LabeledRow("Zinsen laufendes Quartal (${r.quarter.daysOverdrawn} Tage im Minus)") {
            AmountText(r.quarter.interestCents, withSign = false, colored = false)
        }
        LabeledRow("Zinsen letztes Quartal (${r.previousQuarter.daysOverdrawn} Tage im Minus)") {
            AmountText(r.previousQuarter.interestCents, withSign = false, colored = false)
        }
        LabeledRow("Zinsen dieses Jahr") {
            AmountText(r.year.interestCents, withSign = false, colored = false)
        }
        if (r.year.daysOverdrawn > 0) {
            LabeledRow("Tiefster Stand dieses Jahr") {
                AmountText(r.year.lowestBalanceCents, withSign = false, negativeIsAlarming = true)
            }
        }
    }
}

@Composable
private fun Calculator(accounts: List<AccountWithBalance>) {
    val withRate = accounts.filter { it.account.overdraftRatePercent > 0 }
    if (withRate.isEmpty()) return
    var accountId by rememberSaveable { mutableStateOf(withRate.first().account.id) }
    var amount by rememberSaveable { mutableStateOf("") }
    var days by rememberSaveable { mutableStateOf("30") }
    val account = withRate.firstOrNull { it.account.id == accountId }?.account ?: withRate.first().account

    SectionCard(title = "Dispo-Rechner") {
        if (withRate.size > 1) {
            DropdownField(
                label = "Konto",
                options = withRate.map { it.account },
                selected = account,
                onSelect = { accountId = it.id },
                optionLabel = { "${it.name} (${it.overdraftRatePercent.toPercentText()} %)" },
            )
        }
        MoneyField(value = amount, onValueChange = { amount = it }, label = "Betrag im Minus")
        OutlinedTextField(
            value = days,
            onValueChange = { text -> days = text.filter(Char::isDigit).take(4) },
            label = { Text("Dauer in Tagen") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        val cents = Money.parse(amount)?.takeIf { it > 0 }
        val dayCount = days.toIntOrNull()?.takeIf { it > 0 }
        if (cents != null && dayCount != null) {
            LabeledRow("Kosten") {
                AmountText(Overdraft.costCents(cents, dayCount, account.overdraftRatePercent), withSign = false, colored = false)
            }
        }
    }
}
