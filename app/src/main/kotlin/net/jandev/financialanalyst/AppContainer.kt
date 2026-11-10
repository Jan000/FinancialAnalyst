package net.jandev.financialanalyst

import android.content.Context
import android.net.Uri
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

/** Hält die langlebigen Objekte der App; ersetzt ein DI-Framework. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.open(context)
    val repository = FinanceRepository(db)
    val settings = SettingsStore(context)
    val bankSync = BankSync(db, repository, BankCredentials(context), afterChange = { afterTransactionsChanged() })
    val backups = BackupManager(context, db, settings)
    val driveBackup = DriveBackup(context, backups)
    val notificationMapping = NotificationMapping(context)
    val lastSyncReport = MutableStateFlow<SyncReport?>(null)

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
