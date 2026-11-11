package net.jandev.financialanalyst.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.jandev.financialanalyst.domain.RuleMatcher
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun dateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun epochDayToDate(day: Long?): LocalDate? = day?.let(LocalDate::ofEpochDay)
}

@Database(
    entities = [
        AccountEntity::class,
        TransactionEntity::class,
        CategoryEntity::class,
        CategoryRuleEntity::class,
        RecurringEntity::class,
        LoanEntity::class,
        LoanExtraPaymentEntity::class,
        LoanRateChangeEntity::class,
        GroupEntity::class,
        AutoRuleEntity::class,
        AutoRuleConditionEntity::class,
        BudgetEntity::class,
        SavingsGoalEntity::class,
        BankSessionEntity::class,
        SplitEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun transactions(): TransactionDao
    abstract fun categories(): CategoryDao
    abstract fun recurring(): RecurringDao
    abstract fun loans(): LoanDao
    abstract fun planning(): PlanningDao
    abstract fun bankSessions(): BankSessionDao
    abstract fun groups(): GroupDao
    abstract fun splits(): SplitDao
    abstract fun rules(): RuleDao

    companion object {
        const val FILE_NAME = "finance.db"

        /** Kredite: Raten je Zeitraum und Verknüpfung mit den tatsächlichen Buchungen. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `loans` ADD COLUMN `matchKey` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `loan_rate_changes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`loanId` INTEGER NOT NULL, `fromDate` INTEGER NOT NULL, `monthlyPaymentCents` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`loanId`) REFERENCES `loans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_rate_changes_loanId` ON `loan_rate_changes` (`loanId`)")
            }
        }

        /** Gruppen: Umsätze zu einem Vorhaben zusammenfassen. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `groupId` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tx_groups` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `colorIndex` INTEGER NOT NULL, `note` TEXT NOT NULL)",
                )
            }
        }

        /** Positionen innerhalb eines Umsatzes und wiederkehrende Positionen (Spar-Abos). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tx_splits` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `parentId` INTEGER NOT NULL, " +
                        "`date` INTEGER NOT NULL, `amountCents` INTEGER NOT NULL, `description` TEXT NOT NULL, `categoryId` INTEGER, " +
                        "`groupId` INTEGER, `recurringId` INTEGER, FOREIGN KEY(`parentId`) REFERENCES `transactions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tx_splits_parentId` ON `tx_splits` (`parentId`)")
                db.execSQL("ALTER TABLE `recurring` ADD COLUMN `parentKey` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `recurring` ADD COLUMN `groupId` INTEGER")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `recurring` ADD COLUMN `alias` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Regeln mit mehreren Bedingungen; bisher gelernte Zuordnungen werden zu solchen Regeln. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `auto_rules` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `categoryId` INTEGER NOT NULL, `enabled` INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `auto_rule_conditions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`ruleId` INTEGER NOT NULL, `field` TEXT NOT NULL, `op` TEXT NOT NULL, `value` TEXT NOT NULL, " +
                        "FOREIGN KEY(`ruleId`) REFERENCES `auto_rules`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_auto_rule_conditions_ruleId` ON `auto_rule_conditions` (`ruleId`)")
                val learned = ArrayList<Pair<String, Long>>()
                db.query("SELECT pattern, categoryId FROM category_rules WHERE userDefined = 1 ORDER BY id").use { cursor ->
                    while (cursor.moveToNext()) learned.add(cursor.getString(0) to cursor.getLong(1))
                }
                for ((pattern, categoryId) in learned) {
                    val rule = ContentValues().apply {
                        put("name", "")
                        put("categoryId", categoryId)
                        put("enabled", 1)
                    }
                    val ruleId = db.insert("auto_rules", SQLiteDatabase.CONFLICT_ABORT, rule)
                    for (condition in RuleMatcher.fromLearnKey(pattern, sign = null)) {
                        val row = ContentValues().apply {
                            put("ruleId", ruleId)
                            put("field", condition.field.name)
                            put("op", condition.op.name)
                            put("value", condition.value)
                        }
                        db.insert("auto_rule_conditions", SQLiteDatabase.CONFLICT_ABORT, row)
                    }
                }
                db.execSQL("DELETE FROM category_rules WHERE userDefined = 1")
            }
        }

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                .build()
    }
}
