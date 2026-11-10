package net.jandev.financialanalyst.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.data.notifications.NotificationMapping
import net.jandev.financialanalyst.domain.NotificationParser
import net.jandev.financialanalyst.ui.common.DropdownField
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.Screen
import net.jandev.financialanalyst.ui.common.SectionCard
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors

@Composable
fun NotificationScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val mapping by app.notificationMapping.mapping.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(NotificationMapping.isListenerEnabled(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = NotificationMapping.isListenerEnabled(context) }

    val installed = remember {
        NotificationParser.knownApps.filter { known ->
            runCatching { context.packageManager.getPackageInfo(known.packageName, 0) }.isSuccess
        }
    }

    Screen(title = "Zahlungen sofort erfassen", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Die App kann Zahlungs-Benachrichtigungen deiner Banking-Apps mitlesen und daraus sofort einen vorläufigen Umsatz anlegen. " +
                    "Sobald die echte Buchung per Bankabruf oder Import eintrifft, ersetzt sie den vorläufigen Eintrag.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Ausgewertet werden nur die Apps, denen du unten ein Konto zuordnest. Die Inhalte verlassen das Gerät nicht.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionCard(title = "Berechtigung") {
                if (enabled) {
                    Text("Der Zugriff auf Benachrichtigungen ist erteilt.")
                } else {
                    Text("Android muss der App den Zugriff auf Benachrichtigungen erlauben.", color = LocalFinanceColors.current.warning)
                }
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(if (enabled) "Zugriff verwalten" else "Zugriff erlauben") }
            }
            SectionCard(title = "Apps und Konten") {
                if (accounts.isEmpty()) {
                    Text("Lege zuerst ein Konto an.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (installed.isEmpty()) {
                    Text(
                        "Auf diesem Gerät wurde keine der unterstützten Banking-Apps gefunden " +
                            "(${NotificationParser.knownApps.joinToString { it.label }}).",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val options: List<AccountEntity?> = listOf<AccountEntity?>(null) + accounts.map { it.account }
                if (accounts.isNotEmpty()) {
                    installed.forEach { known ->
                        DropdownField(
                            label = known.label,
                            options = options,
                            selected = accounts.firstOrNull { it.account.id == mapping[known.packageName] }?.account,
                            onSelect = { app.notificationMapping.set(known.packageName, it?.id) },
                            optionLabel = { it?.name ?: "Nicht mitlesen" },
                            placeholder = "Nicht mitlesen",
                        )
                    }
                }
            }
        }
    }
}
