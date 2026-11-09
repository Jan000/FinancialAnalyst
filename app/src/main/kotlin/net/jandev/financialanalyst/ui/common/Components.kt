package net.jandev.financialanalyst.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import net.jandev.financialanalyst.AppContainer
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

val LocalApp = staticCompositionLocalOf<AppContainer> { error("AppContainer fehlt") }

private val dayFormat = DateTimeFormatter.ofPattern("EEE, d. MMMM yyyy", Locale.GERMAN)
private val shortFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN)
private val dayMonthFormat = DateTimeFormatter.ofPattern("d. MMM", Locale.GERMAN)

fun LocalDate.longLabel(): String = format(dayFormat)
fun LocalDate.shortLabel(): String = format(shortFormat)
fun LocalDate.dayMonthLabel(): String = format(dayMonthFormat)

/** Bildschirm mit Titelleiste; [onBack] blendet den Zurück-Pfeil ein. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Screen(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = actions,
            )
        },
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** Betrag in Tabellenziffern; Einnahmen grün, Ausgaben in der normalen Textfarbe oder rot. */
@Composable
fun AmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    colored: Boolean = true,
    withSign: Boolean = true,
    negativeIsAlarming: Boolean = false,
) {
    val finance = LocalFinanceColors.current
    val color = when {
        !colored -> Color.Unspecified
        cents > 0 -> finance.income
        cents < 0 && negativeIsAlarming -> finance.expense
        else -> Color.Unspecified
    }
    Text(
        text = Money.format(cents, withSign = withSign),
        modifier = modifier,
        style = style.copy(fontFeatureSettings = "tnum"),
        color = color,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

@Composable
fun EmptyState(title: String, hint: String, modifier: Modifier = Modifier, action: @Composable () -> Unit = {}) {
    Box(modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action()
        }
    }
}

/** Eingabefeld für Geldbeträge; die Auswertung übernimmt [Money.parse]. */
@Composable
fun MoneyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
    supportingText: String? = null,
) {
    val invalid = value.isNotBlank() && Money.parse(value) == null
    OutlinedTextField(
        value = value,
        onValueChange = { text ->
            onValueChange(text.filter { it.isDigit() || it == ',' || it == '.' || (allowNegative && it == '-') })
        },
        label = { Text(label) },
        suffix = { Text("€") },
        singleLine = true,
        isError = invalid,
        supportingText = when {
            invalid -> ({ Text("Bitte einen Betrag wie 12,34 eingeben") })
            supportingText != null -> ({ Text(supportingText) })
            else -> null
        },
        keyboardOptions = KeyboardOptions(keyboardType = if (allowNegative) KeyboardType.Text else KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Betrag für ein Eingabefeld, ohne Währungszeichen und Tausenderpunkte. */
fun Long.toFieldText(): String {
    val sign = if (this < 0) "-" else ""
    val abs = kotlin.math.abs(this)
    return "$sign${abs / 100},${(abs % 100).toString().padStart(2, '0')}"
}

@Composable
fun PercentField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() || it == ',' || it == '.' }) },
        label = { Text(label) },
        suffix = { Text("% p. a.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

fun parsePercent(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

fun Double.toPercentText(): String =
    if (this == 0.0) "" else String.format(Locale.GERMAN, "%.2f", this).trimEnd('0').trimEnd(',')

/** Nur-Lese-Feld, das beim Antippen [onClick] auslöst (für Datum und Auswahllisten). */
@Composable
private fun ClickableField(
    value: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) onClick() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        trailingIcon = trailing,
        interactionSource = interaction,
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(date: LocalDate, onDateChange: (LocalDate) -> Unit, label: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ClickableField(value = date.longLabel(), label = label, onClick = { open = true }, modifier = modifier)
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onDateChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    open = false
                }) { Text("Übernehmen") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Abbrechen") } },
        ) {
            DatePicker(state = state)
        }
    }
}

@Composable
fun <T> DropdownField(
    label: String,
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    optionLabel: (T) -> String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ClickableField(
            value = selected?.let(optionLabel) ?: placeholder,
            label = label,
            onClick = { open = true },
            trailing = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Zeile mit Beschriftung links und Wert rechts. */
@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, value: @Composable () -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        value()
    }
}
