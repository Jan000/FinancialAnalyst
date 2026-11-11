package net.jandev.financialanalyst.ui.rules

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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.RuleWithConditions
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.data.db.AutoRuleEntity
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.matching
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.RuleCondition
import net.jandev.financialanalyst.domain.RuleField
import net.jandev.financialanalyst.domain.RuleMatcher
import net.jandev.financialanalyst.domain.RuleOp
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.toFieldText
import net.jandev.financialanalyst.ui.transactions.ApplyCategoryDialog
import kotlin.math.abs

fun RuleField.label(): String = when (this) {
    RuleField.COUNTERPARTY -> "Gegenpartei"
    RuleField.PURPOSE -> "Verwendungszweck"
    RuleField.TEXT -> "Gegenpartei oder Zweck"
    RuleField.AMOUNT -> "Betrag"
    RuleField.DIRECTION -> "Art"
    RuleField.ACCOUNT -> "Konto"
}

fun RuleOp.label(): String = when (this) {
    RuleOp.CONTAINS -> "enthält"
    RuleOp.EQUALS -> "ist gleich"
    RuleOp.STARTS_WITH -> "beginnt mit"
    RuleOp.NOT_CONTAINS -> "enthält nicht"
    RuleOp.AT_LEAST -> "mindestens"
    RuleOp.AT_MOST -> "höchstens"
}

/** Bedingung als lesbarer Halbsatz, etwa: Gegenpartei ist gleich „REWE“. */
fun RuleCondition.describe(accountNames: Map<Long, String>): String = when (field) {
    RuleField.AMOUNT -> "Betrag ${op.label()} ${value.toLongOrNull()?.let { Money.format(it) } ?: "?"}"
    RuleField.DIRECTION -> if (value == RuleMatcher.IN) "nur Einnahmen" else "nur Ausgaben"
    RuleField.ACCOUNT -> "Konto ${accountNames[value.toLongOrNull()] ?: "(gelöscht)"}"
    else -> "${field.label()} ${op.label()} „${value.trim()}“"
}

@Composable
fun RulesScreen(onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val app = LocalApp.current
    val rules by app.repository.rules.collectAsStateWithLifecycle(initialValue = null)
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.account.id to it.account.name } }

    Screen(
        title = "Regeln",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(0) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Regel") },
            )
        },
    ) { padding ->
        val list = rules ?: return@Screen
        if (list.isEmpty()) {
            EmptyState(
                title = "Noch keine Regeln",
                hint = "Eine Regel ordnet Umsätze automatisch einer Kategorie zu, sobald alle ihre Bedingungen zutreffen. " +
                    "Regeln entstehen auch, wenn du beim Kategorisieren „als Regel merken“ wählst.",
                modifier = Modifier.padding(padding),
            )
            return@Screen
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(list, key = { it.rule.id }) { item ->
                val conditions = item.conditions.joinToString(" und ") { it.describe(accountNames) }
                val target = "→ ${categoryNames[item.rule.categoryId] ?: "gelöschte Kategorie"}" + if (item.rule.enabled) "" else " · ausgeschaltet"
                ListItem(
                    headlineContent = { Text(item.rule.name.ifBlank { conditions }) },
                    supportingContent = { Text(if (item.rule.name.isBlank()) target else "$conditions\n$target") },
                    modifier = Modifier.clickable { onEdit(item.rule.id) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** Bedingungen im Formular; beim Betrag steht in `value` der eingetippte Text statt der Cent. */
private val draftSaver = listSaver<List<RuleCondition>, String>(
    save = { list -> list.flatMap { listOf(it.field.name, it.op.name, it.value) } },
    restore = { flat -> flat.chunked(3).map { RuleCondition(RuleField.valueOf(it[0]), RuleOp.valueOf(it[1]), it[2]) } },
)

/** Bedingung aus dem Formular in gespeicherter Form (Betrag in Cent). */
fun RuleCondition.toStored(): RuleCondition =
    if (field == RuleField.AMOUNT) copy(value = Money.parse(value)?.let { abs(it).toString() }.orEmpty()) else copy(value = value.trim())

/** Gespeicherte Bedingung als Formularwert (Betrag als Text). */
fun RuleCondition.toDraft(): RuleCondition =
    if (field == RuleField.AMOUNT) copy(value = value.toLongOrNull()?.toFieldText().orEmpty()) else this

@Composable
fun RuleEditScreen(ruleId: Long, onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())

    var loaded by remember { mutableStateOf<RuleWithConditions?>(null) }
    var ready by rememberSaveable { mutableStateOf(ruleId == 0L) }
    var name by rememberSaveable { mutableStateOf("") }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var enabled by rememberSaveable { mutableStateOf(true) }
    var drafts by rememberSaveable(stateSaver = draftSaver) {
        mutableStateOf(listOf(RuleCondition(RuleField.COUNTERPARTY, RuleOp.CONTAINS, "")))
    }
    var confirmDelete by remember { mutableStateOf(false) }
    /** Gespeicherte Regel, deren Treffer gerade zur Auswahl stehen. */
    var applying by remember { mutableStateOf<Pair<Long, List<RuleCondition>>?>(null) }

    LaunchedEffect(ruleId) {
        if (ruleId == 0L) return@LaunchedEffect
        val rule = app.repository.rule(ruleId) ?: return@LaunchedEffect
        loaded = rule
        if (!ready) {
            name = rule.rule.name
            categoryId = rule.rule.categoryId
            enabled = rule.rule.enabled
            drafts = rule.conditions.map { it.toDraft() }
            ready = true
        }
    }

    val stored = drafts.map { it.toStored() }
    val valid = categoryId != null && stored.isNotEmpty() && stored.all(RuleMatcher::isValid)
    val hits = remember(transactions, stored) { if (stored.all(RuleMatcher::isValid)) transactions.matching(stored, null) else emptyList() }
    val open = hits.count { it.categoryId != categoryId }
    val accountNames = remember(accounts) { accounts.associate { it.account.id to it.account.name } }

    fun save(then: (Long) -> Unit) {
        val category = categoryId ?: return
        scope.launch {
            val id = app.repository.saveRule(
                (loaded?.rule ?: AutoRuleEntity(categoryId = category)).copy(name = name.trim(), categoryId = category, enabled = enabled),
                stored,
            )
            then(id)
        }
    }

    Screen(
        title = if (ruleId == 0L) "Neue Regel" else "Regel bearbeiten",
        onBack = onDone,
        actions = {
            if (ruleId != 0L) {
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Regel löschen") }
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
            Text("Wenn alle Bedingungen zutreffen …", style = MaterialTheme.typography.titleSmall)
            drafts.forEachIndexed { index, condition ->
                ConditionEditor(
                    condition = condition,
                    accounts = accounts.map { it.account },
                    removable = drafts.size > 1,
                    onChange = { changed -> drafts = drafts.toMutableList().also { it[index] = changed } },
                    onRemove = { drafts = drafts.filterIndexed { i, _ -> i != index } },
                )
            }
            TextButton(onClick = { drafts = drafts + RuleCondition(RuleField.PURPOSE, RuleOp.CONTAINS, "") }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("Bedingung hinzufügen", modifier = Modifier.padding(start = 8.dp))
            }
            Text("… dann zuordnen zu", style = MaterialTheme.typography.titleSmall)
            DropdownField<CategoryEntity>(
                label = "Kategorie",
                options = categories,
                selected = categories.firstOrNull { it.id == categoryId },
                onSelect = { categoryId = it.id },
                optionLabel = { it.name },
                placeholder = "Bitte wählen",
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name der Regel (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Neue Umsätze automatisch zuordnen", modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Text(
                when {
                    !stored.all(RuleMatcher::isValid) -> "Fülle alle Bedingungen aus, um die Treffer zu sehen."
                    hits.isEmpty() -> "Trifft bisher auf keinen Umsatz zu."
                    categoryId == null -> "Trifft auf ${hits.size} ${if (hits.size == 1) "Umsatz" else "Umsätze"} zu."
                    else -> "Trifft auf ${hits.size} ${if (hits.size == 1) "Umsatz" else "Umsätze"} zu, davon $open noch nicht in dieser Kategorie."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                enabled = valid && open > 0,
                onClick = { save { id -> applying = id to stored } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern und Umsätze zuordnen …") }
            OutlinedButton(enabled = valid, onClick = { save { onDone() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Nur speichern")
            }
        }
    }

    applying?.let { (_, conditions) ->
        val category = categoryId ?: return@let
        ApplyCategoryDialog(
            title = "Zuordnen zu „${categories.firstOrNull { it.id == category }?.name.orEmpty()}“",
            description = conditions.joinToString(" und ") { it.describe(accountNames) },
            candidates = remember(transactions, conditions, category) { transactions.matching(conditions, category) },
            categories = categories,
            dismissLabel = "Nicht zuordnen",
            onDismiss = onDone,
            onConfirm = { selected, excluded ->
                scope.launch {
                    app.repository.applyCategory(selected, category, keepOut = excluded)
                    app.afterTransactionsChanged()
                    onDone()
                }
            },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Regel löschen?",
            text = "Bereits zugeordnete Umsätze behalten ihre Kategorie.",
            confirmLabel = "Löschen",
            onConfirm = {
                confirmDelete = false
                val rule = loaded?.rule ?: return@ConfirmDialog
                scope.launch {
                    app.repository.deleteRule(rule)
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private data class Direction(val value: String, val label: String)

private val directions = listOf(Direction(RuleMatcher.OUT, "Ausgabe"), Direction(RuleMatcher.IN, "Einnahme"))

@Composable
fun ConditionEditor(
    condition: RuleCondition,
    accounts: List<AccountEntity>,
    removable: Boolean,
    onChange: (RuleCondition) -> Unit,
    onRemove: () -> Unit,
) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropdownField(
                label = "Feld",
                options = RuleField.entries,
                selected = condition.field,
                onSelect = { field ->
                    if (field != condition.field) {
                        val ops = RuleMatcher.opsFor(field)
                        val value = when (field) {
                            RuleField.DIRECTION -> RuleMatcher.OUT
                            RuleField.ACCOUNT -> accounts.firstOrNull()?.id?.toString().orEmpty()
                            RuleField.AMOUNT -> ""
                            // Zwischen den Textfeldern bleibt der getippte Text erhalten.
                            else -> if (condition.field in textFields) condition.value else ""
                        }
                        onChange(RuleCondition(field, if (condition.op in ops) condition.op else ops.first(), value))
                    }
                },
                optionLabel = { it.label() },
                modifier = Modifier.weight(1f),
            )
            if (removable) {
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Clear, contentDescription = "Bedingung entfernen") }
            }
        }
        val ops = RuleMatcher.opsFor(condition.field)
        if (ops.size > 1) {
            DropdownField(
                label = "Vergleich",
                options = ops,
                selected = condition.op,
                onSelect = { onChange(condition.copy(op = it)) },
                optionLabel = { it.label() },
            )
        }
        when (condition.field) {
            RuleField.AMOUNT -> MoneyField(value = condition.value, onValueChange = { onChange(condition.copy(value = it)) }, label = "Betrag")
            RuleField.DIRECTION -> DropdownField(
                label = "Art",
                options = directions,
                selected = directions.firstOrNull { it.value == condition.value },
                onSelect = { onChange(condition.copy(value = it.value)) },
                optionLabel = { it.label },
            )
            RuleField.ACCOUNT -> DropdownField(
                label = "Konto",
                options = accounts,
                selected = accounts.firstOrNull { it.id.toString() == condition.value },
                onSelect = { onChange(condition.copy(value = it.id.toString())) },
                optionLabel = { it.name },
                placeholder = "Bitte wählen",
            )
            else -> OutlinedTextField(
                value = condition.value,
                onValueChange = { onChange(condition.copy(value = it)) },
                label = { Text("Text") },
                supportingText = if (condition.op == RuleOp.EQUALS) {
                    { Text("Ziffern, Filialnummern und Rechtsformen (GmbH …) spielen keine Rolle.") }
                } else {
                    null
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val textFields = setOf(RuleField.COUNTERPARTY, RuleField.PURPOSE, RuleField.TEXT)
