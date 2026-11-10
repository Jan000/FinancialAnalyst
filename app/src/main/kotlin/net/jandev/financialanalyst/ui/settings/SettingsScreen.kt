package net.jandev.financialanalyst.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.Settings
import net.jandev.financialanalyst.domain.Budget
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.MoneyField
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.common.shortLabel
import net.jandev.financialanalyst.ui.common.toFieldText
import java.time.LocalDate

@Composable
fun SettingsScreen(onBack: () -> Unit, extraSections: @Composable () -> Unit = {}) {
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val stored by app.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var reserve by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(stored) {
        val current = stored ?: return@LaunchedEffect
        if (reserve == null) reserve = current.reserveCents.takeIf { it > 0 }?.toFieldText().orEmpty()
    }

    fun save(settings: Settings) {
        scope.launch { app.settings.update(settings) }
    }

    Screen(title = "Einstellungen", onBack = onBack) { padding ->
        val settings = stored ?: return@Screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(title = "Budget") {
                DropdownField(
                    label = "Budgetzeitraum beginnt am",
                    options = (1..31).toList(),
                    selected = settings.budgetStartDay,
                    onSelect = { save(settings.copy(budgetStartDay = it)) },
                    optionLabel = { if (it == 1) "1. (Kalendermonat)" else "$it. des Monats" },
                )
                val period = Budget.period(LocalDate.now(), settings.budgetStartDay)
                Text(
                    "Aktueller Zeitraum: ${period.start.shortLabel()} bis ${period.endInclusive.shortLabel()}. " +
                        "Wähle deinen Gehaltstag, wenn dein Monat mit dem Gehaltseingang beginnt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyField(
                    value = reserve.orEmpty(),
                    onValueChange = { text ->
                        reserve = text
                        val cents = if (text.isBlank()) 0L else Money.parse(text)
                        if (cents != null) save(settings.copy(reserveCents = cents))
                    },
                    label = "Reserve am Ende des Zeitraums",
                    supportingText = "Puffer oder Sparrate, die nicht ins Tagesbudget einfließt",
                )
            }
            SectionCard(title = "Schutz") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("App-Sperre")
                        Text(
                            "Beim Öffnen mit Fingerabdruck, Gesicht oder Geräte-PIN entsperren; der Inhalt wird in der App-Übersicht verborgen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.appLock, onCheckedChange = { save(settings.copy(appLock = it)) })
                }
            }
            extraSections()
        }
    }
}
