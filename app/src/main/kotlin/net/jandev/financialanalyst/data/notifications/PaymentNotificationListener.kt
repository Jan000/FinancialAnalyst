package net.jandev.financialanalyst.data.notifications

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.jandev.financialanalyst.FinanceApp
import net.jandev.financialanalyst.domain.NotificationParser
import net.jandev.financialanalyst.domain.TxSource
import net.jandev.financialanalyst.domain.TxStatus
import net.jandev.financialanalyst.domain.csv.ImportedTx
import java.time.Instant
import java.time.ZoneId

/** Welche Banking-App welchem Konto zugeordnet ist. Nur zugeordnete Apps werden überhaupt ausgewertet. */
class NotificationMapping(context: Context) {
    private val prefs = context.getSharedPreferences("notification_mapping", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val mapping: StateFlow<Map<String, Long>> = state

    private fun read(): Map<String, Long> =
        prefs.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()

    fun set(packageName: String, accountId: Long?) {
        prefs.edit().apply { if (accountId == null) remove(packageName) else putLong(packageName, accountId) }.apply()
        state.value = read()
    }

    fun accountFor(packageName: String): Long? = state.value[packageName]

    companion object {
        fun isListenerEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
            val own = ComponentName(context, PaymentNotificationListener::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}

/**
 * Erzeugt aus Zahlungs-Pushs der zugeordneten Banking-Apps vorläufige Umsätze.
 * Alles bleibt auf dem Gerät; Benachrichtigungen anderer Apps werden nicht angefasst.
 */
class PaymentNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val container = (applicationContext as FinanceApp).container
        val accountId = container.notificationMapping.accountFor(sbn.packageName) ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()
        val parsed = NotificationParser.parse(title, text) ?: return
        val date = Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault()).toLocalDate()
        // Apps aktualisieren dieselbe Benachrichtigung gern mehrfach; die Kennung macht das unschädlich.
        val externalId = "nt:${sbn.packageName}:${date.toEpochDay()}:${parsed.amountCents}:${parsed.counterparty.lowercase().hashCode()}:${sbn.id}"

        container.appScope.launch {
            if (container.repository.account(accountId) == null) return@launch
            val summary = container.repository.importTransactions(
                accountId = accountId,
                incoming = listOf(
                    ImportedTx(
                        date = date,
                        valueDate = null,
                        amountCents = parsed.amountCents,
                        counterparty = parsed.counterparty,
                        purpose = "Aus Benachrichtigung",
                        externalId = externalId,
                    ),
                ),
                source = TxSource.NOTIFICATION,
                status = TxStatus.PENDING,
            )
            if (summary.inserted > 0) container.afterTransactionsChanged()
        }
    }
}
