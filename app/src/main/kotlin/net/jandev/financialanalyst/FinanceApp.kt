package net.jandev.financialanalyst

import android.app.Application
import kotlinx.coroutines.launch

class FinanceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.repository.seedDefaults()
            container.afterTransactionsChanged()
        }
    }
}
