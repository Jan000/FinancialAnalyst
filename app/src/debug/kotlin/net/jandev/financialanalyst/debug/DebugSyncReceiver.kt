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
                // --es find "american" listet stattdessen die bei Enable Banking gelisteten Banken mit diesem Namensteil.
                val find = intent.getStringExtra("find")
                if (find != null) {
                    val banks = container.bankSync.banks(intent.getStringExtra("country") ?: "DE")
                    val hits = banks.filter { it.name.contains(find, ignoreCase = true) }.map { it.name }
                    Log.d("BankDiag", "Banken (${banks.size} gesamt) mit „$find“: $hits")
                    return@launch
                }
                // --ez sessions true beschreibt nur die Sitzungen, ohne die Banken abzufragen.
                if (intent.getBooleanExtra("sessions", false)) {
                    container.bankSync.describeSessions().forEach { Log.d("BankDiag", it) }
                    return@launch
                }
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
