package net.jandev.financialanalyst

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.jandev.financialanalyst.data.FinanceRepository
import net.jandev.financialanalyst.data.db.AppDatabase

/** Hält die langlebigen Objekte der App; ersetzt ein DI-Framework. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: AppDatabase = AppDatabase.open(context)
    val repository = FinanceRepository(db)
}
