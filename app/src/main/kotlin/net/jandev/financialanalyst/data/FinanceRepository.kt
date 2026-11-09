package net.jandev.financialanalyst.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.data.db.AppDatabase
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.CategoryRuleEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.DefaultCategories
import net.jandev.financialanalyst.domain.ExistingTx
import net.jandev.financialanalyst.domain.IncomingTx
import net.jandev.financialanalyst.domain.Reconciler
import net.jandev.financialanalyst.domain.Reconciliation
import net.jandev.financialanalyst.domain.TxSource
import net.jandev.financialanalyst.domain.TxStatus
import net.jandev.financialanalyst.domain.csv.ImportedTx

/** Zentrale Zugriffsschicht auf Konten, Umsätze und Kategorien. */
class FinanceRepository(private val db: AppDatabase) {

    val accounts: Flow<List<AccountWithBalance>> = db.accounts().observeWithBalance()
    val transactions: Flow<List<TransactionEntity>> = db.transactions().observeAll()
    val categories: Flow<List<CategoryEntity>> = db.categories().observeAll()

    /** Legt beim ersten Start die mitgelieferten Kategorien und Zuordnungsregeln an. */
    suspend fun seedDefaults() {
        db.withTransaction {
            if (db.categories().count() > 0) return@withTransaction
            DefaultCategories.all.forEachIndexed { index, template ->
                val id = db.categories().insert(
                    CategoryEntity(
                        name = template.name,
                        kind = template.kind,
                        builtinKey = template.key,
                        colorIndex = index,
                    ),
                )
                db.categories().insertRules(
                    template.keywords.map { CategoryRuleEntity(pattern = it, categoryId = id) },
                )
            }
        }
    }

    suspend fun account(id: Long): AccountEntity? = db.accounts().byId(id)

    suspend fun accountWithBalance(id: Long): AccountWithBalance? = db.accounts().withBalance(id)

    /**
     * Speichert ein Konto so, dass es danach den Kontostand [currentBalanceCents] zeigt:
     * der Anfangssaldo wird aus dem gewünschten Stand und den vorhandenen Umsätzen zurückgerechnet.
     */
    suspend fun saveAccount(account: AccountEntity, currentBalanceCents: Long): Long = db.withTransaction {
        if (account.id == 0L) {
            db.accounts().insert(account.copy(openingBalanceCents = currentBalanceCents))
        } else {
            val sum = db.transactions().byAccount(account.id).sumOf { it.amountCents }
            db.accounts().update(account.copy(openingBalanceCents = currentBalanceCents - sum))
            account.id
        }
    }

    suspend fun deleteAccount(account: AccountEntity) = db.accounts().delete(account)

    suspend fun transaction(id: Long): TransactionEntity? = db.transactions().byId(id)

    suspend fun saveTransaction(transaction: TransactionEntity): Long =
        if (transaction.id == 0L) {
            db.transactions().insert(transaction)
        } else {
            db.transactions().update(transaction)
            transaction.id
        }

    suspend fun deleteTransaction(transaction: TransactionEntity) = db.withTransaction {
        db.transactions().clearTransferPeer(transaction.id)
        db.transactions().delete(transaction)
    }

    private suspend fun reconcile(accountId: Long, incoming: List<ImportedTx>, source: TxSource): Reconciliation<ImportedTx> {
        val existing = db.transactions().byAccount(accountId).map {
            ExistingTx(it.id, it.bookingDate, it.amountCents, it.externalId, it.source, it.status)
        }
        return Reconciler.reconcile(
            existing,
            incoming.map { IncomingTx(it.date, it.amountCents, it.externalId, it) },
            source,
        )
    }

    /** Was ein Import bewirken würde, ohne etwas zu speichern. */
    suspend fun previewImport(accountId: Long, incoming: List<ImportedTx>, source: TxSource): ImportSummary {
        val result = reconcile(accountId, incoming, source)
        return ImportSummary(result.toInsert.size, result.toConfirm.size, result.duplicates)
    }

    /**
     * Übernimmt gelieferte Umsätze in ein Konto. Bereits vorhandene werden übersprungen, vorläufige oder
     * von Hand erfasste Einträge durch die gelieferte Buchung bestätigt.
     * Mit [balanceAfterCents] wird der Kontostand anschließend auf den von der Bank genannten Wert gesetzt.
     */
    suspend fun importTransactions(
        accountId: Long,
        incoming: List<ImportedTx>,
        source: TxSource,
        status: TxStatus = TxStatus.BOOKED,
        balanceAfterCents: Long? = null,
    ): ImportSummary = db.withTransaction {
        val result = reconcile(accountId, incoming, source)
        db.transactions().insertAll(
            result.toInsert.map {
                TransactionEntity(
                    accountId = accountId,
                    bookingDate = it.date,
                    valueDate = it.valueDate,
                    amountCents = it.amountCents,
                    counterparty = it.counterparty,
                    purpose = it.purpose,
                    externalId = it.externalId,
                    status = status,
                    source = source,
                )
            },
        )
        for ((id, tx) in result.toConfirm) {
            val old = db.transactions().byId(id) ?: continue
            db.transactions().update(
                old.copy(
                    bookingDate = tx.date,
                    valueDate = tx.valueDate,
                    counterparty = tx.counterparty.ifBlank { old.counterparty },
                    purpose = tx.purpose.ifBlank { old.purpose },
                    externalId = tx.externalId,
                    status = status,
                    source = source,
                ),
            )
        }
        if (balanceAfterCents != null) {
            val account = db.accounts().byId(accountId)
            if (account != null) {
                val sum = db.transactions().byAccount(accountId).sumOf { it.amountCents }
                db.accounts().update(account.copy(openingBalanceCents = balanceAfterCents - sum))
            }
        }
        ImportSummary(result.toInsert.size, result.toConfirm.size, result.duplicates)
    }
}

data class ImportSummary(val inserted: Int, val confirmed: Int, val duplicates: Int)
