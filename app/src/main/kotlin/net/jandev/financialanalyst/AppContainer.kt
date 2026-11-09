package net.jandev.financialanalyst

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import net.jandev.financialanalyst.data.FinanceRepository
import net.jandev.financialanalyst.data.db.AppDatabase

/** Hält die langlebigen Objekte der App; ersetzt ein DI-Framework. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.open(context)
    val repository = FinanceRepository(db)

    /** Datei, die per Teilen-Menü oder „Öffnen mit" an die App übergeben wurde. */
    val pendingImport = MutableStateFlow<Uri?>(null)

    /** Nach Import oder Bankabruf: abgeleitete Daten (Kategorien, Umbuchungen, Dauerposten) nachziehen. */
    suspend fun afterTransactionsChanged() {
        repository.enrich()
    }
}
