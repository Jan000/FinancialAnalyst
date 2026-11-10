package net.jandev.financialanalyst.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        BudgetEntity::class,
        SavingsGoalEntity::class,
        BankSessionEntity::class,
    ],
    version = 2,
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

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
