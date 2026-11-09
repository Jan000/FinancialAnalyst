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
}
