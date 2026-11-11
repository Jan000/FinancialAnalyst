package net.jandev.financialanalyst.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.DefaultCategories
import net.jandev.financialanalyst.ui.common.ConfirmDialog
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors

private fun CategoryKind.title() = when (this) {
    CategoryKind.EXPENSE -> "Ausgaben"
    CategoryKind.INCOME -> "Einnahmen"
    CategoryKind.TRANSFER -> "Umbuchungen"
}

/** Alle Kategorien; eigene lassen sich anlegen, jede umbenennen und umfärben. */
@Composable
fun CategoriesScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = null)
    val transactions by app.repository.transactions.collectAsStateWithLifecycle(initialValue = emptyList())
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    val palette = LocalFinanceColors.current.chart
    val counts = remember(transactions) { transactions.groupingBy { it.categoryId }.eachCount() }

    Screen(
        title = "Kategorien",
        onBack = onBack,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val used = categories.orEmpty().map { it.colorIndex }.toSet()
                    editing = CategoryEntity(
                        name = "",
                        kind = CategoryKind.EXPENSE,
                        colorIndex = palette.indices.firstOrNull { it !in used } ?: categories.orEmpty().size,
                    )
                },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Kategorie") },
            )
        },
    ) { padding ->
        val list = categories ?: return@Screen
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for (kind in CategoryKind.entries) {
                val group = list.filter { it.kind == kind }
                if (group.isEmpty()) continue
                item(key = kind.name) {
                    SectionCard(title = kind.title()) {
                        group.forEachIndexed { index, category ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { editing = category }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.size(12.dp).clip(CircleShape).background(palette[category.colorIndex.mod(palette.size)]))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(category.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        listOfNotNull(
                                            "eigene".takeIf { category.builtinKey == null },
                                            when (val n = counts[category.id] ?: 0) {
                                                0 -> "keine Umsätze"
                                                1 -> "1 Umsatz"
                                                else -> "$n Umsätze"
                                            },
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { category ->
        CategoryDialog(category = category, usedNames = categories.orEmpty().filter { it.id != category.id }.map { it.name.lowercase() }.toSet()) {
            editing = null
        }
    }
}

/**
 * Anlegen oder Bearbeiten einer Kategorie. Löschen ist für alle außer „Umbuchung" möglich;
 * die Umsätze der Kategorie werden danach automatisch neu zugeordnet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryDialog(category: CategoryEntity, usedNames: Set<String>, onSaved: (Long) -> Unit = {}, onDone: () -> Unit) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val palette = LocalFinanceColors.current.chart
    var name by rememberSaveable { mutableStateOf(category.name) }
    var kind by rememberSaveable { mutableStateOf(category.kind) }
    var color by rememberSaveable { mutableStateOf(category.colorIndex.mod(palette.size)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val isNew = category.id == 0L
    val duplicate = name.trim().lowercase() in usedNames
    val valid = name.isNotBlank() && !duplicate

    if (confirmDelete) {
        ConfirmDialog(
            title = "„${category.name}“ löschen?",
            text = "Die Umsätze dieser Kategorie werden danach automatisch neu zugeordnet; was keine Regel trifft, steht ohne Kategorie da. " +
                "Regeln und Budget dieser Kategorie werden ebenfalls gelöscht.",
            confirmLabel = "Löschen",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    app.repository.deleteCategory(category)
                    app.afterTransactionsChanged()
                    onDone()
                }
            },
            onDismiss = { confirmDelete = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (isNew) "Neue Kategorie" else "Kategorie bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("z. B. Server oder KI-Tools") },
                    isError = duplicate,
                    supportingText = if (duplicate) ({ Text("Diesen Namen gibt es schon") }) else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (category.kind != CategoryKind.TRANSFER) {
                    // Die Art einer vorhandenen Kategorie bleibt fest, sonst kippten ihre Umsätze in der Auswertung.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(CategoryKind.EXPENSE to "Ausgabe", CategoryKind.INCOME to "Einnahme").forEachIndexed { index, (option, label) ->
                            SegmentedButton(
                                selected = kind == option,
                                enabled = isNew,
                                onClick = { kind = option },
                                shape = SegmentedButtonDefaults.itemShape(index, 2),
                            ) { Text(label) }
                        }
                    }
                }
                Text("Farbe", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    palette.forEachIndexed { index, swatch ->
                        Box(
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .then(
                                    if (index == color) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier,
                                )
                                .clickable { color = index },
                        )
                    }
                }
                if (!isNew && category.builtinKey != DefaultCategories.KEY_TRANSFER) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Kategorie löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    scope.launch {
                        val id = app.repository.saveCategory(category.copy(name = name.trim(), kind = kind, colorIndex = color))
                        onSaved(id)
                        onDone()
                    }
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}
