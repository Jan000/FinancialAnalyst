package net.jandev.financialanalyst.data.db

import net.jandev.financialanalyst.domain.Tx
import net.jandev.financialanalyst.domain.RuleCondition
import net.jandev.financialanalyst.domain.MatchRules
import kotlin.math.abs
import net.jandev.financialanalyst.domain.RecurringDetector
import net.jandev.financialanalyst.data.toTx
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
import net.jandev.financialanalyst.domain.RecurringPlan
import net.jandev.financialanalyst.domain.ScheduleChange
import net.jandev.financialanalyst.domain.RuleField
import net.jandev.financialanalyst.domain.RuleOp
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
    /** Gruppe (Projekt, Vorhaben), zu der der Umsatz gehört. */
    val groupId: Long? = null,
)

/**
 * Position innerhalb eines Umsatzes, etwa ein Einzelkauf in der Monatsabrechnung einer Kreditkarte.
 * Positionen ändern keinen Kontostand; in Auswertung, Budgets und Gruppen treten sie an die Stelle ihres Umsatzes,
 * der Rest bleibt beim Umsatz.
 */
@Entity(
    tableName = "tx_splits",
    foreignKeys = [
        ForeignKey(entity = TransactionEntity::class, parentColumns = ["id"], childColumns = ["parentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("parentId")],
)
data class SplitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val parentId: Long,
    val date: LocalDate,
    /** Gleiches Vorzeichen wie der Umsatz. */
    val amountCents: Long,
    val description: String = "",
    val categoryId: Long? = null,
    val groupId: Long? = null,
    /** Aus diesem wiederkehrenden Posten (Spar-Abo) automatisch angelegt. */
    val recurringId: Long? = null,
)

/** Frei gewählte Zusammenfassung von Umsätzen, etwa alle Bestellungen und Erstattungen eines Projekts. */
@Entity(tableName = "tx_groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Position in der festen Farbpalette. */
    val colorIndex: Int = 0,
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

/** Regel des Nutzers: treffen alle ihre Bedingungen zu, bekommt ein Umsatz die Kategorie. */
@Entity(tableName = "auto_rules")
data class AutoRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Frei gewählter Name; leer = die Bedingungen dienen als Beschriftung. */
    val name: String = "",
    val categoryId: Long,
    val enabled: Boolean = true,
)

@Entity(
    tableName = "auto_rule_conditions",
    foreignKeys = [
        ForeignKey(entity = AutoRuleEntity::class, parentColumns = ["id"], childColumns = ["ruleId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("ruleId")],
)
data class AutoRuleConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val field: RuleField,
    val op: RuleOp,
    val value: String,
)

@Entity(tableName = "recurring")
data class RecurringEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Bezeichnung wie im Kontoauszug; aus ihr entsteht bei von Hand angelegten Posten der [matchKey]. */
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
    /** Frei gewählter Anzeigename; dient nur der Anzeige und nie der Zuordnung von Zahlungen. */
    val alias: String = "",
    /**
     * Gesetzt: der Posten wird nicht direkt vom Konto bezahlt, sondern steckt als Position in einer Sammelabrechnung
     * (etwa ein Amazon-Spar-Abo in der Kreditkartenabrechnung). Der Wert ist der Schlüssel dieser Abrechnungen.
     */
    val parentKey: String = "",
    /** Gruppe, in die die automatisch angelegten Positionen kommen. */
    val groupId: Long? = null,
    /**
     * Gesetzt: fester Zahlungsplan laut Vertrag ab diesem ersten Termin. Ausgebliebene Beiträge bleiben dann offen,
     * bis eine Buchung sie bezahlt; [nextDue] ist der älteste offene Termin.
     */
    val firstDue: LocalDate? = null,
    /** Abweichungen einzelner Termine, kodiert mit [ScheduleChange.encode]. */
    val scheduleChanges: String = "",
    /**
     * Eigene Regeln, welche Buchungen zum Posten gehören, kodiert mit [MatchRules.encode]. Leer: automatisch über
     * [matchKey] und einen zum Betrag passenden Spielraum.
     */
    val matchRules: String = "",
) {
    val isContract: Boolean get() = firstDue != null

    /** Planwerte für Budget und Prognose. */
    fun plan(): RecurringPlan =
        RecurringPlan(displayName, amountCents, interval, nextDue, firstDue, ScheduleChange.decode(scheduleChanges))

    /** Steckt in einer Sammelabrechnung und belastet kein Konto direkt. */
    val isPosition: Boolean get() = parentKey.isNotBlank()

    /** Was die Oberfläche zeigt: der Anzeigename, sonst die Bezeichnung. */
    val displayName: String get() = alias.ifBlank { name }

    /** Beträge, um die herum Buchungen zum Posten gehören: der aktuelle und der vor der letzten Änderung. */
    private val referenceAmounts: List<Long> get() = listOfNotNull(amountCents, previousAmountCents).filter { it != 0L }

    val hasOwnRules: Boolean get() = matchRules.isNotBlank()

    /**
     * Ob eine Buchung mit dem Schlüssel des Postens auch vom Betrag her zu ihm gehört. Verkauft ein Anbieter mehrere
     * Produkte (Domain für 24 €, Zusatzpaket für 3 €), trennt der Betrag sie; kleine Preisänderungen passen weiter.
     * Ein Vertrag hat seine Beträge im Zahlungsplan und nimmt jede Buchung seines Schlüssels.
     */
    fun covers(cents: Long): Boolean {
        if (isContract || isPosition) return true
        if (cents == 0L || (cents < 0) != (amountCents < 0)) return false
        return referenceAmounts.any { ref -> abs(abs(cents) - abs(ref)) <= amountTolerance(ref) }
    }

    /** Kleinster und größter Betrag (ohne Vorzeichen), den [covers] noch annimmt. */
    fun coveredRange(): LongRange =
        referenceAmounts.minOf { abs(it) - amountTolerance(it) }.coerceAtLeast(1)..referenceAmounts.maxOf { abs(it) + amountTolerance(it) }
}

/** Spielraum um den Betrag eines Dauerpostens: 30 %, mindestens 1 €. */
private fun amountTolerance(cents: Long): Long = maxOf(abs(cents) * 3 / 10, 100)

/**
 * Entscheidet, ob eine Buchung zu einem Dauerposten gehört: nach seinen eigenen Regeln, sonst automatisch über den
 * gleichen Schlüssel und einen passenden Betrag ([RecurringEntity.covers]).
 */
class RecurringMatcher(val item: RecurringEntity) {
    val rules: List<List<RuleCondition>> = MatchRules.decode(item.matchRules)

    fun matches(tx: Tx): Boolean =
        if (rules.isNotEmpty()) MatchRules.matches(rules, tx.counterparty, tx.purpose, tx.amountCents, tx.accountId)
        else item.matchKey.isNotBlank() && RecurringDetector.matchKey(tx) == item.matchKey && item.covers(tx.amountCents)

    fun matches(tx: TransactionEntity): Boolean = matches(tx.toTx())
}

/** Ordnet Buchungen ihrem Dauerposten zu; Posten mit eigenen Regeln gehen vor. */
class RecurringIndex(items: List<RecurringEntity>) {
    private val custom = items.filter { it.hasOwnRules && !it.isPosition }.map(::RecurringMatcher).filter { it.rules.isNotEmpty() }
    private val byKey = items.filter { !it.hasOwnRules && it.matchKey.isNotBlank() }.groupBy { it.matchKey }

    fun find(tx: Tx): RecurringEntity? {
        custom.firstOrNull { it.matches(tx) }?.let { return it.item }
        if (byKey.isEmpty()) return null
        return byKey[RecurringDetector.matchKey(tx)]?.firstOrNull { it.covers(tx.amountCents) }
    }

    fun find(tx: TransactionEntity): RecurringEntity? = if (custom.isEmpty() && byKey.isEmpty()) null else find(tx.toTx())
}

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
    /**
     * Schlüssel des Zahlungsempfängers ([net.jandev.financialanalyst.domain.Categorizer.learnKey]); ist er gesetzt,
     * zählen für die Vergangenheit die tatsächlichen Buchungen an ihn statt der geplanten Rate.
     */
    val matchKey: String? = null,
)

/** Ab [fromDate] gilt für den Kredit eine andere Monatsrate. */
@Entity(
    tableName = "loan_rate_changes",
    foreignKeys = [
        ForeignKey(entity = LoanEntity::class, parentColumns = ["id"], childColumns = ["loanId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("loanId")],
)
data class LoanRateChangeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loanId: Long,
    val fromDate: LocalDate,
    val monthlyPaymentCents: Long,
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
