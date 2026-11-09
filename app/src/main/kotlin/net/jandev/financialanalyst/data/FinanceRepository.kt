package net.jandev.financialanalyst.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.data.db.AppDatabase
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.CategoryRuleEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.CategoryRule
import net.jandev.financialanalyst.domain.Categorizer
import net.jandev.financialanalyst.domain.DefaultCategories
import net.jandev.financialanalyst.domain.OwnAccount
import net.jandev.financialanalyst.domain.TransferCandidate
import net.jandev.financialanalyst.domain.TransferDetector
import net.jandev.financialanalyst.domain.Tx
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

    /**
     * Zieht abgeleitete Angaben nach: Kategorien für noch nicht zugeordnete Umsätze und
     * Umbuchungen zwischen eigenen Konten. Vom Nutzer gesetzte Kategorien bleiben unberührt.
     */
    suspend fun enrich() = db.withTransaction {
        val categories = db.categories().all()
        val kinds = categories.associate { it.id to it.kind }
        val transferCategory = categories.firstOrNull { it.builtinKey == DefaultCategories.KEY_TRANSFER }?.id
        val categorizer = Categorizer(
            db.categories().rules().mapNotNull { rule ->
                kinds[rule.categoryId]?.let { CategoryRule(rule.pattern, rule.categoryId, it, rule.userDefined) }
            },
        )
        val all = db.transactions().all()
        val changed = HashMap<Long, TransactionEntity>()

        for (tx in all) {
            if (tx.categoryId != null || tx.categoryLocked) continue
            val category = categorizer.categorize(tx.counterparty, tx.purpose, tx.amountCents) ?: continue
            changed[tx.id] = tx.copy(categoryId = category)
        }

        val accounts = db.accounts().all().map { account ->
            OwnAccount(
                id = account.id,
                iban = account.iban,
                hints = listOf(account.institution, account.name).map { it.trim().lowercase() }.filter { it.length >= 3 },
            )
        }
        if (accounts.size >= 2) {
            val candidates = all.filter { it.transferPeerId == null }.map {
                TransferCandidate(it.id, it.accountId, it.bookingDate, it.amountCents, it.counterparty, it.purpose)
            }
            val byId = all.associateBy { it.id }
            for ((outId, inId) in TransferDetector.detect(accounts, candidates)) {
                for ((id, peer) in listOf(outId to inId, inId to outId)) {
                    val tx = changed[id] ?: byId[id] ?: continue
                    changed[id] = tx.copy(
                        transferPeerId = peer,
                        categoryId = if (tx.categoryLocked) tx.categoryId else transferCategory ?: tx.categoryId,
                    )
                }
            }
        }
        if (changed.isNotEmpty()) db.transactions().updateAll(changed.values.toList())
    }

    /**
     * Merkt sich eine von Hand gewählte Kategorie für diesen Händler und wendet sie auf dessen
     * übrige, nicht von Hand zugeordnete Umsätze an.
     */
    suspend fun learnCategory(counterparty: String, categoryId: Long) = db.withTransaction {
        val key = Categorizer.merchantKey(counterparty)
        if (key.length < 3) return@withTransaction
        db.categories().deleteUserRule(key)
        db.categories().insertRules(listOf(CategoryRuleEntity(pattern = key, categoryId = categoryId, userDefined = true)))
        val affected = db.transactions().all().filter {
            !it.categoryLocked && it.categoryId != categoryId && it.transferPeerId == null &&
                Categorizer.merchantKey(it.counterparty) == key
        }
        if (affected.isNotEmpty()) db.transactions().updateAll(affected.map { it.copy(categoryId = categoryId) })
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

fun TransactionEntity.toTx(): Tx = Tx(
    id = id,
    accountId = accountId,
    date = bookingDate,
    amountCents = amountCents,
    counterparty = counterparty,
    purpose = purpose,
    categoryId = categoryId,
    isTransfer = transferPeerId != null,
)

data class ImportSummary(val inserted: Int, val confirmed: Int, val duplicates: Int)
