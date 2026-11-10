package net.jandev.financialanalyst.data.banking

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.jandev.financialanalyst.FinanceApp
import net.jandev.financialanalyst.data.FinanceRepository
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.data.db.AppDatabase
import net.jandev.financialanalyst.data.db.BankSessionEntity
import net.jandev.financialanalyst.domain.AccountType
import net.jandev.financialanalyst.domain.ConnectorType
import net.jandev.financialanalyst.domain.Money
import net.jandev.financialanalyst.domain.PayPalText
import net.jandev.financialanalyst.domain.TxSource
import net.jandev.financialanalyst.domain.csv.CsvImporter
import net.jandev.financialanalyst.domain.csv.ImportedTx
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

data class SyncReport(
    val accounts: Int,
    val newTransactions: Int,
    val errors: List<String>,
    val needsReconnect: Boolean,
)

/** Verbindet Banken über Enable Banking und holt Salden und Umsätze in die lokale Datenbank. */
class BankSync(
    private val db: AppDatabase,
    private val repository: FinanceRepository,
    val credentials: BankCredentials,
    private val afterChange: suspend () -> Unit,
    /** Nur in Testbuilds: protokolliert, welche Felder eine Bank liefert (Namen und Anzahl, keine Inhalte). */
    private val diagnostics: Boolean = false,
    private val api: EnableBankingApi = EnableBankingApi(tokenProvider = { credentials.token() }),
) {
    private val mutex = Mutex()

    suspend fun banks(country: String): List<Aspsp> = api.banks(country).sortedBy { it.name.lowercase() }

    /** Startet die Freigabe bei [bank] und liefert die Adresse, die im Browser geöffnet werden muss. */
    suspend fun startConnection(bank: Aspsp): String {
        val setup = credentials.setup.value
        // Banken erlauben meist 180 Tage; ohne Angabe vorsichtig 90.
        val seconds = bank.maximumConsentValidity.takeIf { it > 0 }?.coerceAtMost(180L * 86400) ?: (90L * 86400)
        val validUntil = OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(seconds - 3600).withNano(0)
        val state = UUID.randomUUID().toString()
        val response = api.startAuth(
            StartAuthRequest(
                access = Access(validUntil.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                aspsp = AspspRef(bank.name, bank.country),
                state = state,
                redirectUrl = setup.redirectUrl,
            ),
        )
        credentials.pendingAuth = PendingAuth(state, bank.name, bank.country, validUntil.toEpochSecond())
        return response.url
    }

    /**
     * Schließt die Freigabe ab, sobald der Browser mit `code` und `state` zurückkehrt:
     * legt die Sitzung an, ordnet die Bankkonten lokalen Konten zu und holt die Umsätze.
     */
    suspend fun completeConnection(callback: Uri): SyncReport {
        val pending = credentials.pendingAuth ?: error("Es läuft gerade keine Bankfreigabe. Bitte die Verbindung neu starten.")
        callback.getQueryParameter("error")?.let { code ->
            credentials.pendingAuth = null
            val description = callback.getQueryParameter("error_description")
            error(
                if (code == "access_denied") "Die Freigabe wurde bei der Bank abgebrochen."
                else "Die Bank hat die Freigabe abgelehnt: ${description ?: code}",
            )
        }
        val code = callback.getQueryParameter("code") ?: error("In der Rückmeldung der Bank fehlt der Freigabe-Code.")
        if (callback.getQueryParameter("state") != pending.state) {
            error("Die Rückmeldung gehört nicht zur gestarteten Freigabe. Bitte die Verbindung neu starten.")
        }
        val session = api.createSession(code)
        credentials.pendingAuth = null

        val validUntil = session.access?.validUntil?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }
            ?: pending.validUntilEpochSeconds
        // Eine erneute Freigabe derselben Bank ersetzt die alte Sitzung.
        val old = db.bankSessions().all().filter { it.bankName == pending.bankName && it.country == pending.country }
        val sessionRowId = db.bankSessions().insert(
            BankSessionEntity(
                sessionId = session.sessionId,
                bankName = pending.bankName,
                country = pending.country,
                validUntilEpochSeconds = validUntil,
                createdAt = Instant.now().epochSecond,
            ),
        )
        credentials.addHolderNames(session.accounts.mapNotNull { it.name })
        val existing = db.accounts().all()
        for (remote in session.accounts) {
            val uid = remote.uid ?: continue
            val iban = remote.accountId?.iban.orEmpty()
            val match = existing.firstOrNull { remote.identificationHash != null && it.externalHash == remote.identificationHash }
                ?: existing.firstOrNull { iban.isNotBlank() && it.iban.equals(iban, ignoreCase = true) }
            if (match != null) {
                db.accounts().update(
                    match.copy(
                        connector = ConnectorType.ENABLE_BANKING,
                        externalId = uid,
                        externalHash = remote.identificationHash,
                        sessionId = sessionRowId,
                        iban = match.iban.ifBlank { iban },
                        // Kurz nach der Freigabe gibt die Bank die ganze Historie heraus: noch einmal alles holen.
                        lastSync = null,
                    ),
                )
            } else {
                db.accounts().insert(
                    AccountEntity(
                        // `name` ist laut Schnittstelle der Kontoinhaber und taugt nicht als Kontoname.
                        name = listOfNotNull(remote.product, remote.details).firstOrNull { it.isNotBlank() }
                            ?.let { "${pending.bankName} $it" } ?: pending.bankName,
                        institution = pending.bankName,
                        type = when {
                            pending.bankName.contains("paypal", ignoreCase = true) -> AccountType.PAYPAL
                            remote.cashAccountType == "CARD" -> AccountType.CREDIT_CARD
                            remote.cashAccountType == "SVGS" -> AccountType.SAVINGS
                            else -> AccountType.GIRO
                        },
                        iban = iban,
                        connector = ConnectorType.ENABLE_BANKING,
                        externalId = uid,
                        externalHash = remote.identificationHash,
                        sessionId = sessionRowId,
                    ),
                )
            }
        }
        for (row in old) {
            runCatching { api.deleteSession(row.sessionId) }
            db.bankSessions().delete(row)
        }
        distinguishNames(renameAll = false)
        return syncAll()
    }

    /**
     * Sorgt dafür, dass verbundene Konten unterscheidbare Namen tragen: mehrere gleichnamige Konten einer Bank
     * (Haupt- und Unterkonten) bekommen die letzten IBAN-Stellen oder eine laufende Nummer.
     * Mit [renameAll] werden auch die Namen ersetzt, die frühere Versionen aus dem Namen des Kontoinhabers gebildet haben.
     */
    private suspend fun distinguishNames(renameAll: Boolean) {
        val connected = db.accounts().all().filter { it.connector == ConnectorType.ENABLE_BANKING }
        val named = if (renameAll) connected.map { it.copy(name = it.institution) } else connected
        for (group in named.groupBy { it.institution.lowercase() to it.name.lowercase() }.values) {
            var counter = 0
            for (account in group.sortedBy { it.id }) {
                val name = when {
                    group.size == 1 -> account.name
                    account.iban.length >= 4 -> "${account.name} ••${account.iban.takeLast(4)}"
                    else -> "${account.name} Unterkonto ${++counter}"
                }
                val type = if (account.institution.contains("paypal", ignoreCase = true)) AccountType.PAYPAL else account.type
                val current = connected.first { it.id == account.id }
                if (name != current.name || type != current.type) db.accounts().update(current.copy(name = name, type = type))
            }
        }
    }

    suspend fun disconnect(session: BankSessionEntity) {
        runCatching { api.deleteSession(session.sessionId) }
        for (account in db.accounts().all().filter { it.sessionId == session.id }) {
            // Konto und Umsätze bleiben erhalten, es wird nur nicht mehr abgerufen.
            db.accounts().update(account.copy(connector = ConnectorType.MANUAL, externalId = null, sessionId = null))
        }
        db.bankSessions().delete(session)
    }

    /** Holt für alle verbundenen Konten Umsätze und Salden. Läuft nie doppelt gleichzeitig. */
    suspend fun syncAll(): SyncReport = mutex.withLock {
        if (!credentials.onceDone(ONCE_ACCOUNT_NAMES)) {
            distinguishNames(renameAll = true)
            credentials.markDone(ONCE_ACCOUNT_NAMES)
        }
        val now = Instant.now().epochSecond
        val sessions = db.bankSessions().all().associateBy { it.id }
        val accounts = db.accounts().all().filter { it.connector == ConnectorType.ENABLE_BANKING && it.externalId != null }
        if (!credentials.onceDone(ONCE_HOLDER_NAMES)) {
            // Für Verbindungen aus früheren Versionen die Inhabernamen nachholen (an ihnen hängen Umbuchungen).
            val names = accounts.groupBy { it.sessionId }.values.mapNotNull { group ->
                runCatching { api.accountDetails(group.last().externalId!!).name }.getOrNull()
            }
            credentials.addHolderNames(names)
            if (names.isNotEmpty() || accounts.isEmpty()) credentials.markDone(ONCE_HOLDER_NAMES)
        }
        var inserted = 0
        var synced = 0
        var needsReconnect = false
        val errors = ArrayList<String>()

        for (account in accounts) {
            val session = sessions[account.sessionId]
            if (session == null || session.validUntilEpochSeconds < now) {
                needsReconnect = true
                errors.add("${account.name}: Die Freigabe ist abgelaufen. Bitte die Bank neu verbinden.")
                continue
            }
            try {
                inserted += syncAccount(account)
                synced++
            } catch (e: BankApiException) {
                if (e.needsReconnect) needsReconnect = true
                errors.add("${account.name}: ${e.message}")
                // Bei einem Abruflimit bringen weitere Konten derselben Bank auch nichts, andere Banken aber schon.
            } catch (e: Exception) {
                errors.add("${account.name}: ${e.message ?: "Keine Verbindung"}")
            }
        }
        if (synced > 0) afterChange()
        SyncReport(synced, inserted, errors.distinct(), needsReconnect)
    }

    private suspend fun syncAccount(account: AccountEntity): Int {
        val uid = account.externalId ?: return 0
        val today = LocalDate.now()
        val firstSync = account.lastSync == null
        val from = if (firstSync) {
            // Keine Untergrenze: die Bank liefert dann so weit zurück, wie sie kann.
            null
        } else {
            // Großzügig überlappen: Buchungen tauchen bei manchen Banken rückdatiert auf.
            Instant.ofEpochSecond(account.lastSync).atZone(ZoneOffset.UTC).toLocalDate().minusDays(14)
        }
        val fields = java.util.TreeMap<String, Int>()
        val remote = api.transactions(
            uid, from?.toString(), longest = firstSync,
            inspect = if (diagnostics) { text -> runCatching { countFields(Json.parseToJsonElement(text), "", fields) } } else null,
        )
        if (diagnostics) Log.d("BankDiag", "${account.institution} #${account.id}: ${remote.size} Umsätze, Felder $fields")
        val seen = HashMap<String, Int>()
        val incoming = remote.mapNotNull { tx ->
            // Vorgemerkte Umsätze haben noch keine stabile Kennung und können sich ändern; es zählen die gebuchten.
            if (tx.status != null && tx.status != "BOOK") return@mapNotNull null
            if (tx.amount.currency != "EUR") return@mapNotNull null
            val date = listOfNotNull(tx.bookingDate, tx.valueDate, tx.transactionDate).firstNotNullOfOrNull(CsvImporter::parseDate)
                ?: return@mapNotNull null
            val absolute = Money.parse(tx.amount.amount)?.let { kotlin.math.abs(it) } ?: return@mapNotNull null
            if (absolute == 0L) return@mapNotNull null
            val debit = tx.creditDebit == "DBIT"
            val amount = if (debit) -absolute else absolute
            val counterparty = (if (debit) tx.creditor?.name else tx.debtor?.name)
                ?: tx.creditor?.name ?: tx.debtor?.name ?: ""
            val purpose = (tx.remittanceInformation.orEmpty() + listOfNotNull(tx.note))
                .map { it.trim() }.filter { it.isNotEmpty() }.distinct().joinToString(" ")
            val reference = tx.entryReference?.takeIf { it.isNotBlank() }
            val externalId = if (reference != null) {
                "eb:$reference"
            } else {
                val base = "${date.toEpochDay()}|$amount|${counterparty.lowercase()}|${purpose.lowercase()}"
                "ebh:${base.hashCode().toUInt().toString(16)}${base.length}#${seen.merge(base, 1, Int::plus)}"
            }
            ImportedTx(
                date = date,
                valueDate = tx.valueDate?.let(CsvImporter::parseDate),
                amountCents = amount,
                // Die Kennung oben hängt bewusst am Rohtext, damit sie über Versionen hinweg gleich bleibt.
                counterparty = PayPalText.merchant(counterparty, purpose) ?: counterparty.trim(),
                purpose = purpose,
                externalId = externalId,
            )
        }
        val balance = pickBalance(runCatching { api.balances(uid) }.getOrDefault(emptyList()))
        val summary = repository.importTransactions(account.id, incoming, TxSource.API, balanceAfterCents = balance)
        db.accounts().byId(account.id)?.let { db.accounts().update(it.copy(lastSync = Instant.now().epochSecond)) }
        return summary.inserted
    }

    /** Zählt je Feldpfad, wie oft er einen nicht leeren Wert trägt. Inhalte werden nicht festgehalten. */
    private fun countFields(element: JsonElement, path: String, into: MutableMap<String, Int>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) -> countFields(value, if (path.isEmpty()) key else "$path.$key", into) }
            is JsonArray -> element.forEach { countFields(it, "$path[]", into) }
            is JsonNull -> Unit
            is JsonPrimitive -> if (element.content.isNotBlank()) into.merge(path, 1, Int::plus)
        }
    }

    /** Gebuchte Salden zuerst; „verfügbare" enthalten bei manchen Banken den Dispo-Rahmen. */
    private fun pickBalance(balances: List<Balance>): Long? {
        val order = listOf("CLBD", "ITBD", "XPCD", "ITAV", "CLAV", "OPBD", "PRCD")
        return balances
            .filter { it.amount.currency == "EUR" }
            .sortedBy { order.indexOf(it.type).let { index -> if (index < 0) order.size else index } }
            .firstNotNullOfOrNull { Money.parse(it.amount.amount) }
    }

    companion object {
        private const val WORK_NAME = "bank-sync"
        private const val ONCE_ACCOUNT_NAMES = "account_names_v2"
        private const val ONCE_HOLDER_NAMES = "holder_names_v1"

        /** Dreimal täglich im Hintergrund abrufen – Banken erlauben meist vier Abrufe ohne den Nutzer. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BankSyncWorker>(8, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}

class BankSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as FinanceApp).container
        if (!container.bankSync.credentials.setup.value.isComplete) return Result.success()
        val report = runCatching { container.bankSync.syncAll() }.getOrElse { return Result.retry() }
        container.lastSyncReport.value = report
        return Result.success()
    }
}
