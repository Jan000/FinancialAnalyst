package net.jandev.financialanalyst.ui.analysis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.data.toTx
import net.jandev.financialanalyst.data.editTargetId
import net.jandev.financialanalyst.data.isSplitPart
import net.jandev.financialanalyst.domain.Analytics
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.transactions.CategoryPickerDialog
import net.jandev.financialanalyst.ui.transactions.TransactionRow
import net.jandev.financialanalyst.ui.transactions.rememberRowContext
import java.time.LocalDate

/**
 * Alle Umsätze, die in der Analyse in eine Kategorie oder zu einem Empfänger eingerechnet sind. Antippen ändert die Kategorie.
 *
 * @param categoryId 0 steht für „Ohne Kategorie"; dort entscheidet [expense], ob Ausgänge oder Eingänge gemeint sind
 * @param merchantKey gesetzt: statt einer Kategorie die Ausgaben an diesen Empfänger ([Analytics.merchantKey])
 */
@Composable
fun CategoryTransactionsScreen(
    categoryId: Long,
    from: LocalDate,
    to: LocalDate,
    expense: Boolean,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    merchantKey: String? = null,
) {
    val app = LocalApp.current
    val rowContext = rememberRowContext()
    val transactions by app.repository.analysisTransactions.collectAsStateWithLifecycle(initialValue = null)
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    var pickingId by rememberSaveable { mutableStateOf<Long?>(null) }

    val kinds = remember(categories) { categories.associate { it.id to it.kind } }
    val merchantName = remember(transactions, merchantKey) {
        merchantKey?.let { key ->
            transactions?.firstOrNull { Analytics.merchantKey(it.counterparty) == key }?.counterparty?.ifBlank { null } ?: "Ohne Bezeichnung"
        }
    }
    val name = merchantName
        ?: if (categoryId == 0L) "Ohne Kategorie" else categories.firstOrNull { it.id == categoryId }?.name.orEmpty()
    val accountNames = remember(accounts) { accounts.associate { it.account.id to it.account.name } }
    val transferIds = remember(categories) { categories.filter { it.kind == CategoryKind.TRANSFER }.map { it.id }.toSet() }

    Screen(title = name, onBack = onBack) { padding ->
        val all = transactions ?: return@Screen
        // Dieselbe Auswahl wie in der Auswertung: ohne Umbuchungen, im gewählten Zeitraum.
        val list = remember(all, categoryId, from, to, expense, transferIds, merchantKey, kinds) {
            all.filter { tx ->
                when {
                    tx.bookingDate !in from..to -> false
                    merchantKey != null ->
                        Analytics.isMerchantExpense(tx.toTx(), kinds[tx.categoryId]) && Analytics.merchantKey(tx.counterparty) == merchantKey
                    tx.transferPeerId != null || tx.categoryId in transferIds -> false
                    categoryId == 0L -> tx.categoryId == null && (tx.amountCents < 0) == expense
                    else -> tx.categoryId == categoryId
                }
            }
        }
        val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
        if (list.isEmpty()) {
            EmptyState(
                title = "Keine Umsätze mehr",
                hint = if (merchantKey != null) "In diesem Zeitraum gibt es keine Ausgaben an diesen Empfänger." else "In diesem Zeitraum liegt nichts (mehr) in dieser Kategorie.",
                modifier = Modifier.padding(padding),
            )
            return@Screen
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        Money.format(list.sumOf { it.amountCents }, withSign = true),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "${if (list.size == 1) "1 Umsatz" else "${list.size} Umsätze"} · ${from.shortLabel()} bis ${to.shortLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Tippe einen Umsatz an, um seine Kategorie zu ändern.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            items(list, key = { it.id }) { tx ->
                TransactionRow(
                    tx = tx,
                    title = rowContext.titleOf(tx),
                    subtitle = listOfNotNull(
                        tx.bookingDate.shortLabel(),
                        // Beim Empfänger mischen sich Kategorien; dort gehört sie in die Zeile.
                        if (merchantKey != null) categoryNames[tx.categoryId] ?: "Ohne Kategorie" else null,
                        accountNames[tx.accountId],
                    ).joinToString(" · "),
                    // Eine Position bearbeitet man in ihrem Umsatz.
                    onClick = { if (tx.isSplitPart) onEdit(tx.editTargetId) else pickingId = tx.id },
                )
            }
        }
        val picking = all.firstOrNull { it.id == pickingId }
        if (picking != null) {
            CategoryPickerDialog(
                tx = picking,
                categories = categories,
                onDismiss = { pickingId = null },
                onEditDetails = { onEdit(picking.id) },
            )
        }
    }
}
