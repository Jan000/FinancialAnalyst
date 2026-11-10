package net.jandev.financialanalyst

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import net.jandev.financialanalyst.data.FinanceRepository
import net.jandev.financialanalyst.data.SettingsStore
import net.jandev.financialanalyst.data.backup.BackupManager
import net.jandev.financialanalyst.data.backup.DriveBackup
import net.jandev.financialanalyst.data.banking.BankCredentials
import net.jandev.financialanalyst.data.banking.BankSync
import net.jandev.financialanalyst.data.banking.SyncReport
import net.jandev.financialanalyst.data.db.AppDatabase
import net.jandev.financialanalyst.data.notifications.NotificationMapping

/**
 * Zustand der App-Sperre. Er gehört zum Prozess und nicht zur Activity: beim Drehen des Bildschirms entsteht die
 * Activity neu, entsperrt bleibt die App trotzdem.
 */
class LockState {
    var initialized = false
    var locked by mutableStateOf(false)
    var promptShowing = false

    /** Zeitpunkt ([android.os.SystemClock.elapsedRealtime]), zu dem die App zuletzt in den Hintergrund ging. */
    var leftAt = 0L
}

/** Hält die langlebigen Objekte der App; ersetzt ein DI-Framework. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.open(context)
    private val bankCredentials = BankCredentials(context)
    val repository = FinanceRepository(db, ownNames = { bankCredentials.holderNames() })
    val settings = SettingsStore(context)
    val bankSync = BankSync(
        db, repository, bankCredentials,
        afterChange = { afterTransactionsChanged() },
        diagnostics = context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )
    val backups = BackupManager(context, db, settings)
    val driveBackup = DriveBackup(context, backups)
    val notificationMapping = NotificationMapping(context)
    val lastSyncReport = MutableStateFlow<SyncReport?>(null)
    val lock = LockState()

    /** Rücksprung aus dem Browser nach der Freigabe bei der Bank. */
    val pendingBankCallback = MutableStateFlow<Uri?>(null)

    /** Datei, die per Teilen-Menü oder „Öffnen mit" an die App übergeben wurde. */
    val pendingImport = MutableStateFlow<Uri?>(null)

    /** Nach Import oder Bankabruf: abgeleitete Daten (Kategorien, Umbuchungen, Dauerposten) nachziehen. */
    suspend fun afterTransactionsChanged() {
        repository.enrich()
        repository.syncRecurring()
    }
}
