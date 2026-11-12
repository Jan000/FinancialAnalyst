package net.jandev.financialanalyst.ui.loans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.db.LoanEntity
import net.jandev.financialanalyst.data.db.LoanExtraPaymentEntity
import net.jandev.financialanalyst.domain.ExtraPayment
import net.jandev.financialanalyst.domain.LoanCalculator
import net.jandev.financialanalyst.domain.LoanSchedule
import net.jandev.financialanalyst.domain.LoanTerms
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.DateField
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.PercentField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.ShareBar
import net.jandev.financialanalyst.ui.common.parsePercent
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.common.toFieldText
import net.jandev.financialanalyst.ui.common.toPercentText
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import net.jandev.financialanalyst.data.db.LoanRateChangeEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.Categorizer
import net.jandev.financialanalyst.domain.RateChange
import net.jandev.financialanalyst.ui.common.DropdownField
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)

/** Ausgehende Buchungen an den verknüpften Empfänger; `null`, wenn der Kredit nicht verknüpft ist. */
private fun actualPayments(matchKey: String?, transactions: List<TransactionEntity>): List<ExtraPayment>? {
    if (matchKey.isNullOrBlank()) return null
    return transactions
        .filter { it.amountCents < 0 && Categorizer.learnKey(it.counterparty, it.purpose, it.accountId, it.amountCents) == matchKey }
        .map { ExtraPayment(it.bookingDate, -it.amountCents) }
}

private fun LoanEntity.terms(
    extras: List<LoanExtraPaymentEntity>,
    changes: List<LoanRateChangeEntity>,
    transactions: List<TransactionEntity>,
    today: LocalDate,
) = LoanTerms(
    principalCents = principalCents,
    ratePercent = ratePercent,
    monthlyPaymentCents = monthlyPaymentCents,
    firstPayment = firstPayment,
    extraPayments = extras.filter { it.loanId == id }.map { ExtraPayment(it.date, it.amountCents) },
    rateChanges = changes.filter { it.loanId == id }.map { RateChange(it.fromDate, it.monthlyPaymentCents) },
    actualPayments = actualPayments(matchKey, transactions),
    actualUntil = today,
)

/** Wann die Kredite laut Tilgungsplan abbezahlt sind, mit der heutigen Rate; für die Kapitalprognose. */
internal fun loanEnds(
    loans: List<LoanEntity>,
    extras: List<LoanExtraPaymentEntity>,
    changes: List<LoanRateChangeEntity>,
    transactions: List<TransactionEntity>,
    today: LocalDate,
): List<net.jandev.financialanalyst.data.LoanEnd> = loans.mapNotNull { loan ->
    val terms = loan.terms(extras, changes, transactions, today)
    val payoff = LoanCalculator.schedule(terms).payoffDate ?: return@mapNotNull null
    net.jandev.financialanalyst.data.LoanEnd(loan.name, terms.plannedPayment(today), payoff)
}

/** Restschuld je Kredit zum Stichtag, für Übersichten außerhalb des Kreditbereichs. */
internal fun remainingDebts(
    loans: List<LoanEntity>,
    extras: List<LoanExtraPaymentEntity>,
    changes: List<LoanRateChangeEntity>,
    transactions: List<TransactionEntity>,
    today: LocalDate,
): List<Pair<LoanEntity, Long>> = loans.map { loan ->
    val terms = loan.terms(extras, changes, transactions, today)
    loan to LoanCalculator.status(terms, LoanCalculator.schedule(terms), today).remainingCents
}

private data class Payee(val key: String, val name: String, val count: Int)

private fun durationLabel(months: Int): String {
    val years = months / 12
    val rest = months % 12
    return listOfNotNull(
        when (years) {
            0 -> null
            1 -> "1 Jahr"
            else -> "$years Jahre"
        },
        when (rest) {
            0 -> null
            1 -> "1 Monat"
            else -> "$rest Monate"
        },
    ).joinToString(" ").ifEmpty { "0 Monate" }
}

@Composable
fun LoansScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val app = LocalApp.current
    val loans by app.repository.loans.collectAsStateWithLifecycle(initialValue = null)
    val extras by app.repository.loanExtraPayments.collectAsStateWithLifecycle(initialValue = emptyList())
    val changes by app.repository.loanRateChanges.collectAsStateWithLifecycle(initialValue = emptyList())
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = LocalDate.now()

    Screen(
        title = "Kredite",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpen(0) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Kredit") },
            )
        },
    ) { padding ->
        val list = loans ?: return@Screen
        if (list.isEmpty()) {
            EmptyState(
                title = "Keine Kredite eingetragen",
                hint = "Trage laufende Kredite ein, um Restschuld, Zinskosten und das Ende der Laufzeit zu sehen.",
                modifier = Modifier.padding(padding),
            )
            return@Screen
        }
        val computed = remember(list, extras, changes, transactions, today) {
            list.map { loan ->
                val terms = loan.terms(extras, changes, transactions, today)
                val schedule = LoanCalculator.schedule(terms)
                Triple(loan, schedule, LoanCalculator.status(terms, schedule, today))
            }
        }
        val plannedNow = remember(list, changes, today) {
            list.associate { loan ->
                loan.id to (changes.filter { it.loanId == loan.id && it.fromDate <= today }.maxByOrNull { it.fromDate }?.monthlyPaymentCents
                    ?: loan.monthlyPaymentCents)
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard(title = "Restschuld gesamt") {
                    AmountText(
                        computed.sumOf { it.third.remainingCents },
                        style = MaterialTheme.typography.displaySmall,
                        withSign = false,
                        colored = false,
                    )
                    LabeledRow("Raten pro Monat") {
                        AmountText(
                            computed.filter { it.third.remainingCents > 0 }.sumOf { plannedNow[it.first.id] ?: 0 },
                            withSign = false,
                            colored = false,
                        )
                    }
                    LabeledRow("Noch anfallende Zinsen") {
                        AmountText(computed.sumOf { it.third.remainingInterestCents }, withSign = false, colored = false)
                    }
                }
            }
            items(computed, key = { it.first.id }) { (loan, schedule, status) ->
                SectionCard(modifier = Modifier.clickable { onOpen(loan.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(loan.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${loan.ratePercent.toPercentText().ifEmpty { "0" }} % · ${Money.format(plannedNow[loan.id] ?: 0)} pro Monat" +
                                    if (loan.matchKey != null) " · nach Umsätzen" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        AmountText(status.remainingCents, style = MaterialTheme.typography.titleMedium, withSign = false, colored = false)
                    }
                    ShareBar(
                        fraction = 1f - status.remainingCents.toFloat() / loan.principalCents.coerceAtLeast(1),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        when {
                            schedule.payoffDate == null -> "Die Rate deckt die Zinsen nicht – der Kredit wird so nie getilgt."
                            status.remainingCents == 0L -> "Abbezahlt seit ${schedule.payoffDate!!.format(monthYear)}"
                            else -> "Abbezahlt im ${schedule.payoffDate!!.format(monthYear)} (noch ${durationLabel(status.paymentsLeft)})"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (schedule.payoffDate == null) LocalFinanceColors.current.expense else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun LoanDetailScreen(loanId: Long, onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val allExtras by app.repository.loanExtraPayments.collectAsStateWithLifecycle(initialValue = emptyList())
    val allChanges by app.repository.loanRateChanges.collectAsStateWithLifecycle(initialValue = emptyList())
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val today = LocalDate.now()

    var loaded by remember { mutableStateOf<LoanEntity?>(null) }
    var ready by rememberSaveable { mutableStateOf(loanId == 0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var principal by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("") }
    var payment by rememberSaveable { mutableStateOf("") }
    var firstPayment by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var whatIf by rememberSaveable { mutableStateOf("") }
    var extraAmount by rememberSaveable { mutableStateOf("") }
    // Art der Zahlung außer der Reihe: Sondertilgung oder weitere Auszahlung (Schuld steigt).
    var extraIsDisbursement by rememberSaveable { mutableStateOf(false) }
    var note by rememberSaveable { mutableStateOf("") }
    var extraDate by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var changeAmount by rememberSaveable { mutableStateOf("") }
    var changeDate by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    var matchKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(loanId) {
        if (loanId == 0L) return@LaunchedEffect
        val loan = app.repository.loan(loanId) ?: return@LaunchedEffect
        loaded = loan
        if (!ready) {
            name = loan.name
            principal = loan.principalCents.toFieldText()
            rate = loan.ratePercent.toPercentText().ifEmpty { "0" }
            payment = loan.monthlyPaymentCents.toFieldText()
            firstPayment = loan.firstPayment.toEpochDay()
            matchKey = loan.matchKey
            note = loan.note
            ready = true
        }
    }

    val principalCents = Money.parse(principal)?.takeIf { it > 0 }
    val ratePercent = parsePercent(rate)?.takeIf { it >= 0 && it < 100 }
    val paymentCents = Money.parse(payment)?.takeIf { it > 0 }
    val valid = name.isNotBlank() && principalCents != null && ratePercent != null && paymentCents != null
    val extras = allExtras.filter { it.loanId == loanId && loanId != 0L }
    val changes = allChanges.filter { it.loanId == loanId && loanId != 0L }
    val actual = remember(matchKey, transactions) { actualPayments(matchKey, transactions) }
    // Empfänger, an die mehrfach gezahlt wurde – daraus wählt man, wohin die Raten gehen.
    val payees = remember(transactions) {
        transactions.filter { it.amountCents < 0 && it.transferPeerId == null }
            .groupBy { Categorizer.learnKey(it.counterparty, it.purpose, it.accountId, it.amountCents) }
            .filter { it.value.size >= 2 }
            .map { (key, list) ->
                val newest = list.maxBy { it.bookingDate }
                Payee(key, newest.counterparty.ifBlank { newest.purpose }.ifBlank { "Ohne Text, je ${Money.format(-newest.amountCents)}" }, list.size)
            }
            .sortedBy { it.name.lowercase() }
    }

    val terms = if (principalCents != null && ratePercent != null && paymentCents != null) {
        LoanTerms(
            principalCents, ratePercent, paymentCents, LocalDate.ofEpochDay(firstPayment),
            extraPayments = extras.map { ExtraPayment(it.date, it.amountCents) },
            rateChanges = changes.map { RateChange(it.fromDate, it.monthlyPaymentCents) },
            actualPayments = actual,
            actualUntil = today,
        )
    } else {
        null
    }
    val schedule = remember(terms) { terms?.let(LoanCalculator::schedule) }

    Screen(
        title = if (loanId == 0L) "Neuer Kredit" else "Kredit",
        onBack = onDone,
        actions = {
            if (loanId != 0L) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Kredit löschen")
                }
            }
        },
    ) { padding ->
        if (!ready) return@Screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Bezeichnung") },
                placeholder = { Text("z. B. Autokredit") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MoneyField(value = principal, onValueChange = { principal = it }, label = "Darlehensbetrag")
            PercentField(value = rate, onValueChange = { rate = it }, label = "Sollzins")
            MoneyField(value = payment, onValueChange = { payment = it }, label = "Monatliche Rate")
            DateField(date = LocalDate.ofEpochDay(firstPayment), onDateChange = { firstPayment = it.toEpochDay() }, label = "Erste Rate am")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Notiz (optional)") },
                supportingText = { Text("Etwa wofür das Geld war oder was vereinbart ist") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionCard(title = "Tatsächliche Zahlungen") {
                Text(
                    "Wähle, an wen die Raten gehen. Dann zählt für die Vergangenheit, was laut Umsätzen wirklich gezahlt wurde – " +
                        "auch wenn es mal 50 €, mal 200 € oder gar nichts war. Die Rate oben gilt für die Zukunft.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DropdownField(
                    label = "Zahlungen an",
                    options = listOf(Payee("", "Nicht verknüpfen (nach Plan rechnen)", 0)) + payees,
                    selected = payees.firstOrNull { it.key == matchKey }
                        ?: matchKey?.let { Payee(it, "Verknüpfter Empfänger (derzeit ohne Umsätze)", 0) },
                    onSelect = { matchKey = it.key.ifEmpty { null } },
                    optionLabel = { if (it.count > 0) "${it.name} (${it.count} Zahlungen)" else it.name },
                    placeholder = "Nicht verknüpft",
                )
                if (actual != null) {
                    val since = actual.filter { it.date >= LocalDate.ofEpochDay(firstPayment).minusMonths(1) }
                    LabeledRow("Gezahlt laut Umsätzen (${since.size} Zahlungen)") {
                        AmountText(since.sumOf { it.amountCents }, withSign = false, colored = false)
                    }
                }
            }

            if (terms != null && schedule != null) {
                LoanSummary(terms, schedule, today)
            }

            Button(
                enabled = valid,
                onClick = {
                    val base = loaded ?: LoanEntity(name = "", principalCents = 0, ratePercent = 0.0, monthlyPaymentCents = 0, firstPayment = today)
                    val loan = base.copy(
                        name = name.trim(),
                        principalCents = principalCents ?: return@Button,
                        ratePercent = ratePercent ?: return@Button,
                        monthlyPaymentCents = paymentCents ?: return@Button,
                        firstPayment = LocalDate.ofEpochDay(firstPayment),
                        matchKey = matchKey,
                        note = note.trim(),
                    )
                    scope.launch {
                        app.repository.saveLoan(loan)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }

            if (loanId != 0L) {
                SectionCard(title = "Raten nach Zeitraum") {
                    Text(
                        "Wenn sich die Rate ändert oder du eine Zeit lang aussetzt (Rate 0 €): ab wann gilt welcher Betrag?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    changes.forEach { change ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ab ${change.fromDate.shortLabel()}", modifier = Modifier.weight(1f))
                            Text(if (change.monthlyPaymentCents == 0L) "Pause" else "${Money.format(change.monthlyPaymentCents)} pro Monat")
                            IconButton(onClick = { scope.launch { app.repository.deleteRateChange(change) } }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Ratenänderung entfernen")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    MoneyField(value = changeAmount, onValueChange = { changeAmount = it }, label = "Neue Monatsrate")
                    DateField(date = LocalDate.ofEpochDay(changeDate), onDateChange = { changeDate = it.toEpochDay() }, label = "Gilt ab")
                    val changeCents = Money.parse(changeAmount)?.takeIf { it >= 0 }
                    OutlinedButton(
                        enabled = changeCents != null,
                        onClick = {
                            val cents = changeCents ?: return@OutlinedButton
                            scope.launch {
                                app.repository.addRateChange(
                                    LoanRateChangeEntity(loanId = loanId, fromDate = LocalDate.ofEpochDay(changeDate), monthlyPaymentCents = cents),
                                )
                                changeAmount = ""
                            }
                        },
                    ) { Text("Ratenänderung hinzufügen") }
                }
                SectionCard(title = "Sondertilgungen und weitere Auszahlungen") {
                    Text(
                        "Sondertilgung: einmalige Zahlung zusätzlich zur Rate, auch bar oder über ein anderes Konto. " +
                            "Weitere Auszahlung: der Kredit wird aufgestockt, die Schuld steigt ab diesem Tag.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (extras.isEmpty()) {
                        Text("Noch keine eingetragen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    extras.sortedBy { it.date }.forEach { extra ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(extra.date.shortLabel())
                                Text(
                                    if (extra.amountCents < 0) "Weitere Auszahlung" else "Sondertilgung",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Aus Sicht der Schuld: Auszahlung erhöht sie (+), Tilgung senkt sie (−).
                            Text(Money.format(-extra.amountCents, withSign = true))
                            IconButton(onClick = { scope.launch { app.repository.deleteExtraPayment(extra) } }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Sondertilgung entfernen")
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !extraIsDisbursement, onClick = { extraIsDisbursement = false }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("Sondertilgung") }
                        SegmentedButton(selected = extraIsDisbursement, onClick = { extraIsDisbursement = true }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("Auszahlung") }
                    }
                    MoneyField(value = extraAmount, onValueChange = { extraAmount = it }, label = "Betrag")
                    DateField(date = LocalDate.ofEpochDay(extraDate), onDateChange = { extraDate = it.toEpochDay() }, label = "Datum")
                    val extraCents = Money.parse(extraAmount)?.takeIf { it > 0 }
                    OutlinedButton(
                        enabled = extraCents != null,
                        onClick = {
                            val cents = extraCents ?: return@OutlinedButton
                            scope.launch {
                                app.repository.addExtraPayment(
                                    LoanExtraPaymentEntity(loanId = loanId, date = LocalDate.ofEpochDay(extraDate), amountCents = if (extraIsDisbursement) -cents else cents),
                                )
                                extraAmount = ""
                            }
                        },
                    ) { Text(if (extraIsDisbursement) "Auszahlung hinzufügen" else "Sondertilgung hinzufügen") }
                }
            }

            if (terms != null && schedule?.payoffDate != null) {
                SectionCard(title = "Was wäre, wenn ich mehr zahle?") {
                    MoneyField(value = whatIf, onValueChange = { whatIf = it }, label = "Zusätzlich pro Monat")
                    val more = Money.parse(whatIf)?.takeIf { it > 0 }
                    if (more != null) {
                        val faster = remember(terms, more) {
                            LoanCalculator.schedule(terms.copy(monthlyPaymentCents = terms.monthlyPaymentCents + more))
                        }
                        val savedMonths = schedule.rows.size - faster.rows.size
                        LabeledRow("Früher schuldenfrei") { Text(durationLabel(savedMonths)) }
                        LabeledRow("Gesparte Zinsen") {
                            AmountText(schedule.totalInterestCents - faster.totalInterestCents, withSign = false)
                        }
                        faster.payoffDate?.let { date ->
                            LabeledRow("Neues Ende") { Text(date.format(monthYear)) }
                        }
                    }
                }
                YearlyPlan(schedule)
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Kredit löschen?",
            text = "„$name“ wird samt Sondertilgungen aus der App entfernt.",
            confirmLabel = "Löschen",
            onConfirm = {
                confirmDelete = false
                val loan = loaded ?: return@ConfirmDialog
                scope.launch {
                    app.repository.deleteLoan(loan)
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun LoanSummary(terms: LoanTerms, schedule: LoanSchedule, today: LocalDate) {
    val status = LoanCalculator.status(terms, schedule, today)
    SectionCard(title = "Stand heute") {
        if (schedule.payoffDate == null) {
            Text(
                "Mit dieser Rate werden nicht einmal die monatlichen Zinsen von " +
                    "${Money.format(Math.round(terms.principalCents * terms.ratePercent / 1200.0))} gedeckt. Der Kredit wird so nie getilgt.",
                color = LocalFinanceColors.current.expense,
            )
            return@SectionCard
        }
        LabeledRow("Restschuld") { AmountText(status.remainingCents, withSign = false, colored = false) }
        LabeledRow("Bisher gezahlte Zinsen") { AmountText(status.paidInterestCents, withSign = false, colored = false) }
        LabeledRow("Noch anfallende Zinsen") { AmountText(status.remainingInterestCents, withSign = false, colored = false) }
        LabeledRow("Zinsen über die gesamte Laufzeit") { AmountText(schedule.totalInterestCents, withSign = false, colored = false) }
        LabeledRow("Gesamtlaufzeit") { Text(durationLabel(schedule.rows.size)) }
        LabeledRow("Letzte Rate") { Text(schedule.payoffDate!!.format(monthYear)) }
        schedule.rows.firstOrNull { it.date > today }?.let { next ->
            LabeledRow("Nächste Rate (${next.date.shortLabel()})") {
                Text("${Money.format(next.interestCents)} Zinsen + ${Money.format(next.principalCents)} Tilgung")
            }
        }
    }
}

@Composable
private fun YearlyPlan(schedule: LoanSchedule) {
    val years = schedule.rows.groupBy { it.date.year }
    SectionCard(title = "Tilgungsplan nach Jahren") {
        Row {
            Text("Jahr", Modifier.weight(0.7f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Zinsen", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Tilgung", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Restschuld", Modifier.weight(1.2f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val disbursed = years.mapValues { (_, rows) -> rows.sumOf { it.disbursedCents } }.filterValues { it > 0 }
        if (disbursed.isNotEmpty()) {
            Text(
                "Weitere Auszahlungen: " + disbursed.entries.joinToString { (year, cents) -> "$year ${Money.format(cents)}" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        years.forEach { (year, rows) ->
            Row {
                Text(year.toString(), Modifier.weight(0.7f), style = MaterialTheme.typography.bodySmall)
                Text(Money.format(rows.sumOf { it.interestCents }), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(Money.format(rows.sumOf { it.principalCents + it.extraCents }), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(Money.format(rows.last().remainingCents), Modifier.weight(1.2f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
