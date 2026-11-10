package net.jandev.financialanalyst.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

private const val BALANCE_QUERY = """
    SELECT a.*, a.openingBalanceCents + COALESCE(
        (SELECT SUM(t.amountCents) FROM transactions t WHERE t.accountId = a.id), 0
    ) AS balanceCents
    FROM accounts a
"""

@Dao
interface AccountDao {
    @Query("$BALANCE_QUERY WHERE a.archived = 0 ORDER BY a.name COLLATE NOCASE")
    fun observeWithBalance(): Flow<List<AccountWithBalance>>

    @Query("$BALANCE_QUERY WHERE a.archived = 0 ORDER BY a.name COLLATE NOCASE")
    suspend fun allWithBalance(): List<AccountWithBalance>

    @Query("SELECT * FROM accounts ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun byId(id: Long): AccountEntity?

    @Query("$BALANCE_QUERY WHERE a.id = :id")
    suspend fun withBalance(id: Long): AccountWithBalance?

    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Update
    suspend fun update(account: AccountEntity)

    @Delete
    suspend fun delete(account: AccountEntity)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY bookingDate DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY bookingDate DESC, id DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY bookingDate, id")
    suspend fun byAccount(accountId: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun byId(id: Long): TransactionEntity?

    @Query("SELECT externalId FROM transactions WHERE accountId = :accountId AND externalId IS NOT NULL")
    suspend fun externalIds(accountId: Long): List<String>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId AND bookingDate BETWEEN :from AND :to")
    suspend fun inRange(accountId: Long, from: LocalDate, to: LocalDate): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(transaction: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(transactions: List<TransactionEntity>): List<Long>

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Update
    suspend fun updateAll(transactions: List<TransactionEntity>)

    @Delete
    suspend fun delete(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE transactions SET transferPeerId = NULL WHERE transferPeerId = :id")
    suspend fun clearTransferPeer(id: Long)

    @Query("UPDATE transactions SET recurringId = NULL WHERE recurringId = :recurringId")
    suspend fun clearRecurring(recurringId: Long)

    @Query("UPDATE transactions SET groupId = :groupId WHERE id IN (:ids)")
    suspend fun setGroup(ids: List<Long>, groupId: Long?)

    @Query("UPDATE transactions SET groupId = NULL WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: Long)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM tx_groups ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<GroupEntity>>

    @Upsert
    suspend fun upsert(group: GroupEntity): Long

    @Delete
    suspend fun delete(group: GroupEntity)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY kind, name COLLATE NOCASE")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY kind, name COLLATE NOCASE")
    suspend fun all(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Query("SELECT * FROM category_rules")
    suspend fun rules(): List<CategoryRuleEntity>

    @Insert
    suspend fun insertRules(rules: List<CategoryRuleEntity>)

}

@Dao
interface RuleDao {
    @Query("SELECT * FROM auto_rules ORDER BY id DESC")
    fun observeRules(): Flow<List<AutoRuleEntity>>

    @Query("SELECT * FROM auto_rule_conditions ORDER BY id")
    fun observeConditions(): Flow<List<AutoRuleConditionEntity>>

    @Query("SELECT * FROM auto_rules")
    suspend fun rules(): List<AutoRuleEntity>

    @Query("SELECT * FROM auto_rule_conditions ORDER BY id")
    suspend fun conditions(): List<AutoRuleConditionEntity>

    @Query("SELECT * FROM auto_rules WHERE id = :id")
    suspend fun byId(id: Long): AutoRuleEntity?

    @Upsert
    suspend fun upsert(rule: AutoRuleEntity): Long

    @Delete
    suspend fun delete(rule: AutoRuleEntity)

    @Query("DELETE FROM auto_rules WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Insert
    suspend fun insertConditions(conditions: List<AutoRuleConditionEntity>)

    @Query("DELETE FROM auto_rule_conditions WHERE ruleId = :ruleId")
    suspend fun deleteConditions(ruleId: Long)
}

@Dao
interface RecurringDao {
    @Query("SELECT * FROM recurring ORDER BY nextDue")
    fun observeAll(): Flow<List<RecurringEntity>>

    @Query("SELECT * FROM recurring")
    suspend fun all(): List<RecurringEntity>

    @Query("SELECT * FROM recurring WHERE id = :id")
    suspend fun byId(id: Long): RecurringEntity?

    @Upsert
    suspend fun upsert(item: RecurringEntity): Long

    @Update
    suspend fun updateAll(items: List<RecurringEntity>)

    @Delete
    suspend fun delete(item: RecurringEntity)
}

@Dao
interface LoanDao {
    @Query("SELECT * FROM loans ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<LoanEntity>>

    @Query("SELECT * FROM loans WHERE id = :id")
    suspend fun byId(id: Long): LoanEntity?

    @Upsert
    suspend fun upsert(loan: LoanEntity): Long

    @Delete
    suspend fun delete(loan: LoanEntity)

    @Query("SELECT * FROM loan_extra_payments ORDER BY date")
    fun observeExtraPayments(): Flow<List<LoanExtraPaymentEntity>>

    @Insert
    suspend fun insertExtra(payment: LoanExtraPaymentEntity): Long

    @Delete
    suspend fun deleteExtra(payment: LoanExtraPaymentEntity)

    @Query("SELECT * FROM loan_rate_changes ORDER BY fromDate")
    fun observeRateChanges(): Flow<List<LoanRateChangeEntity>>

    @Insert
    suspend fun insertRateChange(change: LoanRateChangeEntity): Long

    @Delete
    suspend fun deleteRateChange(change: LoanRateChangeEntity)
}

@Dao
interface PlanningDao {
    @Query("SELECT * FROM budgets")
    fun observeBudgets(): Flow<List<BudgetEntity>>

    @Upsert
    suspend fun upsertBudget(budget: BudgetEntity)

    @Query("DELETE FROM budgets WHERE categoryId = :categoryId")
    suspend fun deleteBudget(categoryId: Long)

    @Query("SELECT * FROM savings_goals ORDER BY name COLLATE NOCASE")
    fun observeGoals(): Flow<List<SavingsGoalEntity>>

    @Upsert
    suspend fun upsertGoal(goal: SavingsGoalEntity): Long

    @Delete
    suspend fun deleteGoal(goal: SavingsGoalEntity)
}

@Dao
interface BankSessionDao {
    @Query("SELECT * FROM bank_sessions ORDER BY bankName")
    fun observeAll(): Flow<List<BankSessionEntity>>

    @Query("SELECT * FROM bank_sessions")
    suspend fun all(): List<BankSessionEntity>

    @Query("SELECT * FROM bank_sessions WHERE id = :id")
    suspend fun byId(id: Long): BankSessionEntity?

    @Insert
    suspend fun insert(session: BankSessionEntity): Long

    @Delete
    suspend fun delete(session: BankSessionEntity)
}
