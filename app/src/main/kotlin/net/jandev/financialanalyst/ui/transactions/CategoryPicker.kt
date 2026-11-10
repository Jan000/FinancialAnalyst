package net.jandev.financialanalyst.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.shortLabel

/**
 * Kategorie eines Umsatzes mit einem Tipp ändern. Auf Wunsch (Voreinstellung) gilt die Wahl für alle
 * bisherigen und künftigen gleichen Zahlungen.
 */
@Composable
fun CategoryPickerDialog(
    tx: TransactionEntity,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onEditDetails: (() -> Unit)? = null,
) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    var applyToAll by remember { mutableStateOf(true) }
    // Passende Art zuerst: bei einer Abbuchung die Ausgaben-Kategorien, bei einem Eingang die Einnahmen.
    val ordered = remember(categories, tx.amountCents) {
        val first = if (tx.amountCents < 0) CategoryKind.EXPENSE else CategoryKind.INCOME
        categories.sortedBy {
            when (it.kind) {
                first -> 0
                CategoryKind.TRANSFER -> 2
                else -> 1
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(tx.counterparty.ifBlank { tx.purpose.ifBlank { "Ohne Bezeichnung" } }, maxLines = 2)
                Text(
                    "${tx.bookingDate.shortLabel()} · ${Money.format(tx.amountCents, withSign = true)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    items(ordered, key = { it.id }) { category ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        app.repository.saveTransaction(tx.copy(categoryId = category.id, categoryLocked = true))
                                        if (applyToAll) app.repository.learnCategory(tx, category.id)
                                        app.afterTransactionsChanged()
                                        onDismiss()
                                    }
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(category.name, modifier = Modifier.weight(1f))
                            if (category.id == tx.categoryId) {
                                Icon(Icons.Filled.Check, contentDescription = "Aktuelle Kategorie", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { applyToAll = !applyToAll },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Text("Für alle gleichen Zahlungen übernehmen", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
        dismissButton = {
            if (onEditDetails != null) {
                TextButton(onClick = { onDismiss(); onEditDetails() }) { Text("Details") }
            }
        },
    )
}
