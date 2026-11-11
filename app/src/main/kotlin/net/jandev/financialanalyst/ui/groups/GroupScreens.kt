package net.jandev.financialanalyst.ui.groups

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.db.GroupEntity
import net.jandev.financialanalyst.data.editTargetId
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.common.AmountText
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.EmptyState
import net.jandev.financialanalyst.ui.common.LabeledRow
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import net.jandev.financialanalyst.ui.transactions.TransactionRow
import kotlin.math.abs

/** Farbe einer Gruppe aus der festen Diagrammpalette. */
@Composable
fun groupColor(colorIndex: Int): Color {
    val palette = LocalFinanceColors.current.chart
    return palette[colorIndex.mod(palette.size)]
}

private val colorNames = listOf("Farbe 1", "Farbe 2", "Farbe 3", "Farbe 4", "Farbe 5", "Farbe 6", "Farbe 7", "Farbe 8")

@Composable
private fun ColorDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).clip(CircleShape).background(color))
}

/** Gruppen fassen Umsätze zu einem Vorhaben zusammen (Projekt, Reise, Anschaffung) und zeigen dessen Saldo. */
@Composable
fun GroupsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val groups by app.repository.groups.collectAsStateWithLifecycle(initialValue = null)
    val transactions by app.repository.analysisTransactions.collectAsStateWithLifecycle(initialValue = emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }

    Screen(
        title = "Gruppen",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Gruppe") },
            )
        },
    ) { padding ->
        val list = groups ?: return@Screen
        if (list.isEmpty()) {
            EmptyState(
                title = "Noch keine Gruppen",
                hint = "Fasse Umsätze zusammen, die zu einem Vorhaben gehören – etwa alle Bestellungen und Erstattungen " +
                    "für ein Projekt – und sieh, was es unterm Strich gekostet hat.",
                modifier = Modifier.padding(padding),
            )
        } else {
            val byGroup = remember(transactions) { transactions.filter { it.groupId != null }.groupBy { it.groupId } }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.id }) { group ->
                    val members = byGroup[group.id].orEmpty()
                    val sum = members.sumOf { it.amountCents }
                    SectionCard(modifier = Modifier.clickable { onOpen(group.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ColorDot(groupColor(group.colorIndex))
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (members.size == 1) "1 Umsatz" else "${members.size} Umsätze",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AmountText(sum, style = MaterialTheme.typography.titleMedium, negativeIsAlarming = false)
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        GroupDialog(
            initial = null,
            suggestedColor = groups?.size ?: 0,
            onDismiss = { creating = false },
            onSave = { name, color ->
                creating = false
                scope.launch { onOpen(app.repository.saveGroup(GroupEntity(name = name, colorIndex = color))) }
            },
        )
    }
}

@Composable
fun GroupDialog(initial: GroupEntity?, suggestedColor: Int, onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(initial?.colorIndex ?: suggestedColor.mod(8)) }
    val palette = LocalFinanceColors.current.chart
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Neue Gruppe" else "Gruppe bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("z. B. Umbau Arbeitszimmer") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEachIndexed { index, c ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(c)
                                .then(
                                    if (index == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                                )
                                .clickable { color = index }
                                .semantics { contentDescription = colorNames.getOrElse(index) { "Farbe" } + if (index == color) ", gewählt" else "" },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), color) }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun GroupDetailScreen(groupId: Long, onBack: () -> Unit, onEditTransaction: (Long) -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val groups by app.repository.groups.collectAsStateWithLifecycle(initialValue = null)
    val transactions by app.repository.analysisTransactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }

    val group = groups?.firstOrNull { it.id == groupId }
    val accountNames = remember(accounts) { accounts.associate { it.account.id to it.account.name } }

    if (adding && group != null) {
        AddTransactions(
            group = group,
            onDone = { adding = false },
        )
        return
    }

    Screen(
        title = group?.name ?: "Gruppe",
        onBack = onBack,
        actions = {
            if (group != null) {
                IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Gruppe bearbeiten") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, contentDescription = "Gruppe löschen") }
            }
        },
    ) { padding ->
        if (group == null) return@Screen
        val members = remember(transactions, groupId) { transactions.filter { it.groupId == groupId } }
        val income = members.filter { it.amountCents > 0 }.sumOf { it.amountCents }
        val expense = members.filter { it.amountCents < 0 }.sumOf { it.amountCents }
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "head") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionCard(title = "Saldo") {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ColorDot(groupColor(group.colorIndex))
                            AmountText(income + expense, style = MaterialTheme.typography.displaySmall, negativeIsAlarming = false)
                        }
                        LabeledRow("Ausgegeben") { AmountText(expense, colored = false) }
                        LabeledRow("Erhalten") { AmountText(income, colored = false) }
                        if (members.isNotEmpty()) {
                            Text(
                                "${if (members.size == 1) "1 Umsatz" else "${members.size} Umsätze"} · " +
                                    "${members.minOf { it.bookingDate }.shortLabel()} bis ${members.maxOf { it.bookingDate }.shortLabel()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Umsätze hinzufügen") }
                    if (members.isEmpty()) {
                        Text(
                            "Noch keine Umsätze in dieser Gruppe.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            items(members, key = { it.id }) { tx ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        TransactionRow(
                            tx = tx,
                            subtitle = listOfNotNull(tx.bookingDate.shortLabel(), accountNames[tx.accountId]).joinToString(" · "),
                            onClick = { onEditTransaction(tx.editTargetId) },
                        )
                    }
                    IconButton(onClick = { scope.launch { app.repository.removeFromGroup(tx) } }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Aus der Gruppe nehmen")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (editing && group != null) {
        GroupDialog(
            initial = group,
            suggestedColor = group.colorIndex,
            onDismiss = { editing = false },
            onSave = { name, color ->
                editing = false
                scope.launch { app.repository.saveGroup(group.copy(name = name, colorIndex = color)) }
            },
        )
    }
    if (confirmDelete && group != null) {
        ConfirmDialog(
            title = "Gruppe löschen?",
            text = "„${group.name}“ wird entfernt. Die Umsätze selbst bleiben erhalten.",
            confirmLabel = "Löschen",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    app.repository.deleteGroup(group)
                    onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** Mehrere Umsätze auf einmal auswählen und in die Gruppe legen. */
@Composable
private fun AddTransactions(group: GroupEntity, onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val groups by app.repository.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(listOf<Long>()) }

    val accountNames = remember(accounts) { accounts.associate { it.account.id to it.account.name } }
    val groupNames = remember(groups) { groups.associate { it.id to it.name } }
    val candidates = remember(transactions, query, group.id) {
        val q = query.trim().lowercase()
        transactions.filter { it.groupId != group.id }.filter {
            q.isEmpty() || it.counterparty.lowercase().contains(q) || it.purpose.lowercase().contains(q) ||
                Money.format(abs(it.amountCents)).contains(q)
        }
    }

    Screen(title = "Zu „${group.name}“ hinzufügen", onBack = onDone) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Händler, Zweck oder Betrag suchen") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(candidates, key = { it.id }) { tx ->
                    val checked = tx.id in selected
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selected = if (checked) selected - tx.id else selected + tx.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(start = 16.dp))
                        Box(Modifier.weight(1f)) {
                            TransactionRow(
                                tx = tx,
                                subtitle = listOfNotNull(
                                    tx.bookingDate.shortLabel(),
                                    accountNames[tx.accountId],
                                    groupNames[tx.groupId]?.let { "bisher in „$it“" },
                                ).joinToString(" · "),
                            )
                        }
                    }
                }
            }
            val sum = transactions.filter { it.id in selected }.sumOf { it.amountCents }
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(onClick = onDone) { Text("Abbrechen") }
                Button(
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            app.repository.setGroup(selected, group.id)
                            onDone()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (selected.isEmpty()) "Umsätze auswählen"
                        else "${selected.size} hinzufügen (${Money.format(sum, withSign = true)})",
                    )
                }
            }
        }
    }
}
