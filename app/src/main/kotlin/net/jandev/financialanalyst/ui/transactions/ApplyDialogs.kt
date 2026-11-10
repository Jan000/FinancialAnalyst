package net.jandev.financialanalyst.ui.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.db.AutoRuleEntity
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.data.matching
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.RuleField
import net.jandev.financialanalyst.domain.RuleMatcher
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.rules.describe

/**
 * Vorschau vor dem Zuordnen: zeigt die Umsätze, die eine Kategorie bekommen sollen, und lässt einzelne abwählen.
 * Von Hand kategorisierte Umsätze sind zunächst nicht angehakt.
 *
 * @param onConfirm bekommt die angehakten und die abgewählten Umsätze
 */
@Composable
fun ApplyCategoryDialog(
    title: String,
    description: String,
    candidates: List<TransactionEntity>,
    categories: List<CategoryEntity>,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (selected: List<Long>, excluded: List<Long>) -> Unit,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    // Nur was der Nutzer angefasst hat; alles andere folgt der Voreinstellung.
    var touched by remember { mutableStateOf(mapOf<Long, Boolean>()) }
    fun checked(tx: TransactionEntity) = touched[tx.id] ?: !tx.categoryLocked
    val selected = candidates.filter { checked(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(description, style = MaterialTheme.typography.bodyMedium)
                extra()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${selected.size} von ${candidates.size} ausgewählt",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { touched = candidates.associate { it.id to true } }) { Text("Alle") }
                    TextButton(onClick = { touched = candidates.associate { it.id to false } }) { Text("Keine") }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(candidates, key = { it.id }) { tx ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { touched = touched + (tx.id to !checked(tx)) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked(tx), onCheckedChange = { touched = touched + (tx.id to it) })
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(
                                    tx.counterparty.ifBlank { tx.purpose.ifBlank { "Ohne Bezeichnung" } },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    listOf(
                                        tx.bookingDate.shortLabel(),
                                        Money.format(tx.amountCents, withSign = true),
                                        "bisher: ${categoryNames[tx.categoryId] ?: "ohne Kategorie"}" +
                                            if (tx.categoryLocked && tx.categoryId != null) " (von Hand)" else "",
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.map { it.id }, candidates.filter { !checked(it) }.map { it.id }) }) {
                Text(if (selected.isEmpty()) "Übernehmen" else "Übernehmen (${selected.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/**
 * Nach dem Kategorisieren von [tx]: welche gleichen Zahlungen sollen dieselbe Kategorie bekommen?
 * Gibt es keine weiteren, wird nur die Regel für künftige Zahlungen gemerkt.
 */
@Composable
fun SimilarPaymentsDialog(tx: TransactionEntity, categoryId: Long, categories: List<CategoryEntity>, onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = null)
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    var sameAmount by remember { mutableStateOf(false) }
    var keepRule by remember { mutableStateOf(true) }
    var decided by remember { mutableStateOf(false) }

    val base = remember(tx) { RuleMatcher.suggest(tx.counterparty, tx.purpose, tx.accountId, tx.amountCents) }
    // Ohne Buchungstext gehört der Betrag schon zum Vorschlag.
    val canNarrow = base.none { it.field == RuleField.AMOUNT }
    val conditions = if (sameAmount && canNarrow) base + RuleMatcher.sameAmount(tx.amountCents) else base
    val all = transactions
    val others = remember(all, conditions, categoryId) {
        all?.matching(conditions, categoryId)?.filter { it.id != tx.id }
    }

    fun finish(selected: List<Long>, excluded: List<Long>, remember: Boolean) {
        if (decided) return
        decided = true
        scope.launch {
            if (selected.isNotEmpty() || (remember && excluded.isNotEmpty())) {
                app.repository.applyCategory(selected, categoryId, keepOut = if (remember) excluded else emptyList())
            }
            if (remember) app.repository.saveRule(AutoRuleEntity(categoryId = categoryId), conditions)
            app.afterTransactionsChanged()
            onDone()
        }
    }

    // Nichts auszuwählen: die Regel für künftige Zahlungen still merken, wie bisher.
    LaunchedEffect(all != null) {
        if (all != null && all.matching(base, categoryId).none { it.id != tx.id }) finish(emptyList(), emptyList(), remember = true)
    }
    if (others == null || decided || (others.isEmpty() && !sameAmount)) return

    val accountNames = accounts.associate { it.account.id to it.account.name }
    val categoryName = categories.firstOrNull { it.id == categoryId }?.name.orEmpty()
    ApplyCategoryDialog(
        title = "Auch nach „$categoryName“?",
        description = "Gleiche Zahlungen: " + conditions.joinToString(" und ") { it.describe(accountNames) },
        candidates = others,
        categories = categories,
        dismissLabel = "Nur dieser Umsatz",
        onDismiss = { finish(emptyList(), emptyList(), remember = false) },
        onConfirm = { selected, excluded -> finish(selected, excluded, keepRule) },
    ) {
        if (canNarrow) {
            FilterChip(
                selected = sameAmount,
                onClick = { sameAmount = !sameAmount },
                label = { Text("Nur mit Betrag ${Money.format(tx.amountCents, withSign = true)}") },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().clickable { keepRule = !keepRule },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = keepRule, onCheckedChange = { keepRule = it })
            Text("Als Regel für künftige Zahlungen merken", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
