package net.jandev.financialanalyst.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import net.jandev.financialanalyst.domain.AccountType
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.ConnectorType
import net.jandev.financialanalyst.domain.RecurrenceInterval
import net.jandev.financialanalyst.domain.TxSource
import net.jandev.financialanalyst.domain.TxStatus
import java.time.LocalDate

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val institution: String = "",
    val type: AccountType = AccountType.GIRO,
    val iban: String = "",
    /**
     * Stand vor dem ersten erfassten Umsatz. Der aktuelle Kontostand ist immer
     * `openingBalanceCents + Summe aller Umsätze`; so lässt sich auch jeder frühere Tagessaldo rekonstruieren.
     */
    val openingBalanceCents: Long = 0,
    /** Eingeräumter Dispo als positiver Betrag; 0 = keiner. */
    val overdraftLimitCents: Long = 0,
    /** Sollzins p. a. in Prozent, z. B. 11.75. */
    val overdraftRatePercent: Double = 0.0,
    /** Zählt für „verfügbar heute / Rest des Monats". */
    val includeInBudget: Boolean = true,
    val connector: ConnectorType = ConnectorType.MANUAL,
    /** Konto-ID beim Bankabruf. */
    val externalId: String? = null,
    /** Über Sitzungen hinweg stabile Kennung des Bankkontos; die externe ID ändert sich bei jeder neuen Freigabe. */
    val externalHash: String? = null,
    val sessionId: Long? = null,
    val lastSync: Long? = null,
    val archived: Boolean = false,
)

data class AccountWithBalance(
    @Embedded val account: AccountEntity,
    val balanceCents: Long,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("accountId"),
        Index("bookingDate"),
        Index("categoryId"),
        Index(value = ["accountId", "externalId"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    val bookingDate: LocalDate,
    val valueDate: LocalDate? = null,
    /** Negativ = Ausgabe, positiv = Einnahme. */
    val amountCents: Long,
    val counterparty: String = "",
    val purpose: String = "",
    val categoryId: Long? = null,
    /** Vom Nutzer gesetzte Kategorie; die Automatik überschreibt sie nicht. */
    val categoryLocked: Boolean = false,
    val status: TxStatus = TxStatus.BOOKED,
    val source: TxSource = TxSource.MANUAL,
    /** ID der Bank bzw. Fingerabdruck der Importzeile, verhindert Dubletten. */
    val externalId: String? = null,
    /** Gegenbuchung auf einem eigenen Konto, wenn dieser Umsatz eine Umbuchung ist. */
    val transferPeerId: Long? = null,
    val recurringId: Long? = null,
    val note: String = "",
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    /** Schlüssel der mitgelieferten Vorlage; `null` bei eigenen Kategorien. */
    val builtinKey: String? = null,
    val colorIndex: Int = 0,
)

@Entity(tableName = "category_rules", indices = [Index("categoryId")])
data class CategoryRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Kleingeschriebener Teilstring, gesucht in Gegenpartei und Verwendungszweck. */
    val pattern: String,
    val categoryId: Long,
    /** Aus einer Nutzerkorrektur gelernt; hat Vorrang vor den mitgelieferten Regeln. */
    val userDefined: Boolean = false,
)

@Entity(tableName = "recurring")
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Normalisierte Gegenpartei, über die neue Umsätze dem Posten zugeordnet werden. */
    val matchKey: String = "",
    /** Negativ = Ausgabe, positiv = Einnahme. */
    val amountCents: Long,
    val interval: RecurrenceInterval = RecurrenceInterval.MONTHLY,
    val nextDue: LocalDate,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val detected: Boolean = false,
    val active: Boolean = true,
    /** Vom Nutzer verworfener Vorschlag; wird nicht erneut vorgeschlagen. */
    val dismissed: Boolean = false,
    /** Betrag vor der letzten Änderung, für den Preiserhöhungs-Hinweis. */
    val previousAmountCents: Long? = null,
    val contractEnd: LocalDate? = null,
    val noticeDays: Int? = null,
    val note: String = "",
)

@Entity(tableName = "loans")
data class LoanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val principalCents: Long,
    /** Sollzins p. a. in Prozent. */
    val ratePercent: Double,
    val monthlyPaymentCents: Long,
    /** Datum der ersten Rate. */
    val firstPayment: LocalDate,
    val note: String = "",
)

@Entity(
    tableName = "loan_extra_payments",
    foreignKeys = [
        ForeignKey(entity = LoanEntity::class, parentColumns = ["id"], childColumns = ["loanId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("loanId")],
)
data class LoanExtraPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loanId: Long,
    val date: LocalDate,
    val amountCents: Long,
)

@Entity(tableName = "budgets")
data class BudgetEntity(
    @PrimaryKey val categoryId: Long,
    val monthlyLimitCents: Long,
)

@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetCents: Long,
    val savedCents: Long = 0,
    val targetDate: LocalDate? = null,
)

/** Eine bei Enable Banking freigegebene Bankverbindung. */
@Entity(tableName = "bank_sessions")
data class BankSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val bankName: String,
    val country: String,
    @ColumnInfo(name = "validUntil") val validUntilEpochSeconds: Long,
    val createdAt: Long,
)
