package net.jandev.financialanalyst.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.domain.AccountType
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.PercentField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.parsePercent
import net.jandev.financialanalyst.ui.common.toFieldText
import net.jandev.financialanalyst.ui.common.toPercentText

@Composable
fun AccountsScreen(onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val app = LocalApp.current
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = null)

    Screen(
        title = "Konten",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(0) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Konto") },
            )
        },
    ) { padding ->
        val list = accounts ?: return@Screen
        if (list.isEmpty()) {
            EmptyState(
                title = "Noch keine Konten",
                hint = "Lege dein erstes Konto an. Umsätze kannst du danach manuell erfassen oder importieren.",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.account.id }) { item ->
                    AccountCard(item, onClick = { onEdit(item.account.id) })
                }
            }
        }
    }
}

@Composable
fun AccountCard(item: AccountWithBalance, onClick: (() -> Unit)? = null) {
    val account = item.account
    SectionCard(modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(account.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(account.institution, account.type.label).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AmountText(
                item.balanceCents,
                style = MaterialTheme.typography.titleMedium,
                withSign = false,
                colored = item.balanceCents < 0,
                negativeIsAlarming = true,
            )
        }
    }
}

@Composable
fun AccountEditScreen(accountId: Long, onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()

    var loaded by remember { mutableStateOf<AccountEntity?>(null) }
    var ready by rememberSaveable { mutableStateOf(accountId == 0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var institution by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(AccountType.GIRO) }
    var iban by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("") }
    var overdraftLimit by rememberSaveable { mutableStateOf("") }
    var overdraftRate by rememberSaveable { mutableStateOf("") }
    var includeInBudget by rememberSaveable { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(accountId) {
        if (accountId == 0L) return@LaunchedEffect
        val item = app.repository.accountWithBalance(accountId) ?: return@LaunchedEffect
        loaded = item.account
        if (!ready) {
            name = item.account.name
            institution = item.account.institution
            type = item.account.type
            iban = item.account.iban
            balance = item.balanceCents.toFieldText()
            overdraftLimit = item.account.overdraftLimitCents.takeIf { it != 0L }?.toFieldText().orEmpty()
            overdraftRate = item.account.overdraftRatePercent.toPercentText()
            includeInBudget = item.account.includeInBudget
            ready = true
        }
    }

    val balanceCents = if (balance.isBlank()) 0L else Money.parse(balance)
    val limitCents = if (overdraftLimit.isBlank()) 0L else Money.parse(overdraftLimit)
    val ratePercent = if (overdraftRate.isBlank()) 0.0 else parsePercent(overdraftRate)
    val valid = name.isNotBlank() && balanceCents != null && limitCents != null && ratePercent != null

    Screen(
        title = if (accountId == 0L) "Neues Konto" else "Konto bearbeiten",
        onBack = onDone,
        actions = {
            if (accountId != 0L) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Konto löschen")
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
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = institution,
                onValueChange = { institution = it },
                label = { Text("Bank / Anbieter") },
                placeholder = { Text("z. B. DKB, N26, Trade Republic") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownField(
                label = "Kontoart",
                options = AccountType.entries,
                selected = type,
                onSelect = { type = it },
                optionLabel = { it.label },
            )
            OutlinedTextField(
                value = iban,
                onValueChange = { iban = it.uppercase() },
                label = { Text("IBAN (optional)") },
                supportingText = { Text("Hilft, Umbuchungen zwischen deinen Konten zu erkennen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            MoneyField(
                value = balance,
                onValueChange = { balance = it },
                label = "Aktueller Kontostand",
                allowNegative = true,
            )
            MoneyField(
                value = overdraftLimit,
                onValueChange = { overdraftLimit = it },
                label = "Dispo-Rahmen (optional)",
            )
            PercentField(
                value = overdraftRate,
                onValueChange = { overdraftRate = it },
                label = "Dispozins (optional)",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Im Budget berücksichtigen")
                    Text(
                        "Der Kontostand zählt für „Heute verfügbar“",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = includeInBudget, onCheckedChange = { includeInBudget = it })
            }
            Button(
                enabled = valid,
                onClick = {
                    val base = loaded ?: AccountEntity(name = "")
                    val account = base.copy(
                        name = name.trim(),
                        institution = institution.trim(),
                        type = type,
                        iban = iban.filterNot { it.isWhitespace() },
                        overdraftLimitCents = limitCents ?: 0,
                        overdraftRatePercent = ratePercent ?: 0.0,
                        includeInBudget = includeInBudget,
                    )
                    scope.launch {
                        app.repository.saveAccount(account, balanceCents ?: 0)
                        onDone()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Konto löschen?",
            text = "Das Konto „$name“ und alle zugehörigen Umsätze werden endgültig gelöscht.",
            confirmLabel = "Löschen",
            onConfirm = {
                confirmDelete = false
                val account = loaded ?: return@ConfirmDialog
                scope.launch {
                    app.repository.deleteAccount(account)
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
