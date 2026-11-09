package net.jandev.financialanalyst.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.transactions.TransactionRow

@Composable
fun OverviewScreen(
    onOpenAccounts: () -> Unit,
    onAddAccount: () -> Unit,
    onOpenTransactions: () -> Unit,
    onEditTransaction: (Long) -> Unit,
) {
    val app = LocalApp.current
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = null)
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }

    Screen(title = "Übersicht") { padding ->
        val list = accounts ?: return@Screen
        if (list.isEmpty()) {
            EmptyState(
                title = "Willkommen beim Finanzanalysten",
                hint = "Lege dein erstes Konto an, um Einnahmen und Ausgaben auszuwerten.",
                modifier = Modifier.padding(padding),
            ) {
                Button(onClick = onAddAccount) { Text("Konto anlegen") }
            }
            return@Screen
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Gesamtvermögen") {
                AmountText(
                    list.sumOf { it.balanceCents },
                    style = MaterialTheme.typography.displaySmall,
                    withSign = false,
                    colored = false,
                )
            }
            SectionCard(title = "Konten") {
                list.forEach { item ->
                    LabeledRow(label = item.account.name) {
                        AmountText(
                            item.balanceCents,
                            withSign = false,
                            colored = item.balanceCents < 0,
                            negativeIsAlarming = true,
                        )
                    }
                }
                TextButton(onClick = onOpenAccounts, modifier = Modifier.align(Alignment.End)) {
                    Text("Konten verwalten")
                }
            }
            if (transactions.isNotEmpty()) {
                SectionCard(title = "Letzte Umsätze") {
                    Column(Modifier.padding(horizontal = 0.dp)) {
                        transactions.take(5).forEachIndexed { index, tx ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            TransactionRow(
                                tx = tx,
                                subtitle = categoryNames[tx.categoryId] ?: "Ohne Kategorie",
                                onClick = { onEditTransaction(tx.id) },
                                horizontalPadding = 0.dp,
                            )
                        }
                    }
                    TextButton(onClick = onOpenTransactions, modifier = Modifier.align(Alignment.End)) {
                        Text("Alle Umsätze")
                    }
                }
            }
        }
    }
}
