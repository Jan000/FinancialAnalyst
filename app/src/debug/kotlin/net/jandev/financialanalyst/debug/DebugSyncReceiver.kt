package net.jandev.financialanalyst.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.FinanceApp

/** Stößt im Testbuild einen Bankabruf an und schreibt das Ergebnis ins Log. */
class DebugSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as FinanceApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                val report = container.bankSync.syncAll()
                container.lastSyncReport.value = report
                Log.d("BankDiag", "Abruf fertig: ${report.accounts} Konten, ${report.newTransactions} neue Umsätze, Fehler ${report.errors}")
            } catch (e: Exception) {
                Log.d("BankDiag", "Abruf gescheitert: $e")
            } finally {
                pending.finish()
            }
        }
    }
}
