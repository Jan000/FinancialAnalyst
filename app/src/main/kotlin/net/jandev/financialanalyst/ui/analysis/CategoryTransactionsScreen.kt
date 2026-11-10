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
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.transactions.CategoryPickerDialog
import net.jandev.financialanalyst.ui.transactions.TransactionRow
import java.time.LocalDate

/**
 * Alle Umsätze, die in der Analyse in eine Kategorie eingerechnet sind. Antippen ändert die Kategorie.
 *
 * @param categoryId 0 steht für „Ohne Kategorie"; dort entscheidet [expense], ob Ausgänge oder Eingänge gemeint sind
 */
@Composable
fun CategoryTransactionsScreen(
    categoryId: Long,
    from: LocalDate,
    to: LocalDate,
    expense: Boolean,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val app = LocalApp.current
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = null)
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    var pickingId by rememberSaveable { mutableStateOf<Long?>(null) }

    val name = if (categoryId == 0L) "Ohne Kategorie" else categories.firstOrNull { it.id == categoryId }?.name.orEmpty()
    val accountNames = remember(accounts) { accounts.associate { it.account.id to it.account.name } }
    val transferIds = remember(categories) { categories.filter { it.kind == CategoryKind.TRANSFER }.map { it.id }.toSet() }

    Screen(title = name, onBack = onBack) { padding ->
        val all = transactions ?: return@Screen
        // Dieselbe Auswahl wie in der Auswertung: ohne Umbuchungen, im gewählten Zeitraum.
        val list = remember(all, categoryId, from, to, expense, transferIds) {
            all.filter { tx ->
                tx.bookingDate in from..to && tx.transferPeerId == null && tx.categoryId !in transferIds &&
                    if (categoryId == 0L) tx.categoryId == null && (tx.amountCents < 0) == expense else tx.categoryId == categoryId
            }
        }
        if (list.isEmpty()) {
            EmptyState(
                title = "Keine Umsätze mehr",
                hint = "In diesem Zeitraum liegt nichts (mehr) in dieser Kategorie.",
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
                    subtitle = listOfNotNull(tx.bookingDate.shortLabel(), accountNames[tx.accountId]).joinToString(" · "),
                    onClick = { pickingId = tx.id },
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
