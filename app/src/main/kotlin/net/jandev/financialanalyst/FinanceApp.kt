package net.jandev.financialanalyst

import android.app.Application
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.data.banking.BankSync

class FinanceApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.appScope.launch {
            container.repository.seedDefaults()
            container.afterTransactionsChanged()
            if (container.bankSync.credentials.setup.value.isComplete) BankSync.schedule(this@FinanceApp)
        }
    }
}
