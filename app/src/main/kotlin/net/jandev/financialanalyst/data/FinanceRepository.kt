package net.jandev.financialanalyst.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.data.db.AppDatabase
import net.jandev.financialanalyst.data.db.BankSessionEntity
import net.jandev.financialanalyst.data.db.BudgetEntity
import net.jandev.financialanalyst.data.db.SavingsGoalEntity
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.CategoryRuleEntity
import net.jandev.financialanalyst.data.db.LoanEntity
import net.jandev.financialanalyst.data.db.LoanExtraPaymentEntity
import net.jandev.financialanalyst.data.db.LoanRateChangeEntity
import net.jandev.financialanalyst.data.db.RecurringEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.AccountType
import net.jandev.financialanalyst.domain.CategoryKind
import net.jandev.financialanalyst.domain.CategoryRule
import net.jandev.financialanalyst.domain.Categorizer
import net.jandev.financialanalyst.domain.DefaultCategories
import net.jandev.financialanalyst.domain.OwnAccount
import net.jandev.financialanalyst.domain.PayPalText
import net.jandev.financialanalyst.domain.TransferCandidate
import net.jandev.financialanalyst.domain.TransferDetector
import net.jandev.financialanalyst.domain.Tx
import net.jandev.financialanalyst.domain.ExistingTx
import net.jandev.financialanalyst.domain.IncomingTx
import net.jandev.financialanalyst.domain.Reconciler
import net.jandev.financialanalyst.domain.Reconciliation
import net.jandev.financialanalyst.domain.RecurringDetector
import net.jandev.financialanalyst.domain.TxSource
import net.jandev.financialanalyst.domain.TxStatus
import net.jandev.financialanalyst.domain.csv.ImportedTx
import java.time.LocalDate

/** Zentrale Zugriffsschicht auf Konten, Umsätze und Kategorien. */
class FinanceRepository(
    private val db: AppDatabase,
    /** Namen der Kontoinhaber verbundener Konten; Überweisungen an sie sind Umbuchungen. */
    private val ownNames: () -> Set<String> = { emptySet() },
) {

    val accounts: Flow<List<AccountWithBalance>> = db.accounts().observeWithBalance()
    val transactions: Flow<List<TransactionEntity>> = db.transactions().observeAll()
    val categories: Flow<List<CategoryEntity>> = db.categories().observeAll()

    /** Legt beim ersten Start die mitgelieferten Kategorien und Zuordnungsregeln an. */
    suspend fun seedDefaults() {
        db.withTransaction {
            if (db.categories().count() > 0) {
                // Schon eingerichtet: nur Schlagwörter nachtragen, die mit einem Update dazugekommen sind.
                val known = db.categories().rules().filter { !it.userDefined }.map { it.categoryId to it.pattern }.toSet()
                val idByKey = db.categories().all().mapNotNull { c -> c.builtinKey?.let { it to c.id } }.toMap()
                val missing = DefaultCategories.all.flatMap { template ->
                    val id = idByKey[template.key] ?: return@flatMap emptyList()
                    template.keywords.filter { (id to it) !in known }.map { CategoryRuleEntity(pattern = it, categoryId = id) }
                }
                if (missing.isNotEmpty()) db.categories().insertRules(missing)
                return@withTransaction
            }
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
        val changed = HashMap<Long, TransactionEntity>()
        val all = db.transactions().all().map { tx ->
            // PayPal-Abbuchungen nennen den Händler nur im Verwendungszweck: als Gegenpartei übernehmen.
            val merchant = PayPalText.merchant(tx.counterparty, tx.purpose) ?: return@map tx
            tx.copy(counterparty = merchant).also { changed[it.id] = it }
        }

        for (tx in all) {
            if (tx.categoryId != null || tx.categoryLocked) continue
            val category = categorizer.categorize(tx.counterparty, tx.purpose, tx.amountCents, tx.accountId) ?: continue
            changed[tx.id] = tx.copy(categoryId = category)
        }

        val accountRows = db.accounts().all()
        val accounts = accountRows.map { account ->
            OwnAccount(
                id = account.id,
                iban = account.iban,
                hints = listOf(account.institution, account.name).map { it.trim().lowercase() }.filter { it.length >= 3 },
                group = account.sessionId,
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
        // Käufe über PayPal stehen dort mit Händler und auf dem Bankkonto noch einmal als Abbuchung:
        // die Bankseite wird zur Umbuchung, sonst zählte jeder Kauf doppelt.
        val wallets = accountRows.filter { it.type == AccountType.PAYPAL || it.institution.contains("paypal", ignoreCase = true) }
            .associate { it.id to "paypal" }
        if (wallets.isNotEmpty()) {
            val current = all.map { changed[it.id] ?: it }
            val taken = current.filter { it.accountId !in wallets }.mapNotNull { it.transferPeerId }.toSet()
            val candidates = current.filter { it.transferPeerId == null }.map {
                TransferCandidate(it.id, it.accountId, it.bookingDate, it.amountCents, it.counterparty, it.purpose)
            }
            val byId = current.associateBy { it.id }
            for ((bankId, walletId) in TransferDetector.detectWalletFunding(wallets, candidates, taken)) {
                val tx = byId[bankId] ?: continue
                // Bewusst einseitig: der Umsatz auf dem Zahlungskonto bleibt die eigentliche Ausgabe.
                changed[bankId] = tx.copy(
                    transferPeerId = walletId,
                    categoryId = if (tx.categoryLocked) tx.categoryId else transferCategory ?: tx.categoryId,
                )
            }
        }
        // Ohne passendes Gegenstück trotzdem Umbuchung: Überweisungen an den eigenen Namen (auf ein Konto,
        // das die App nicht kennt) und Auszahlungen des PayPal-Guthabens.
        val holders = ownNames().map { Categorizer.merchantKey(it) }.filter { it.length >= 5 }.toSet()
        if (transferCategory != null) {
            for (original in all) {
                val tx = changed[original.id] ?: original
                if (tx.transferPeerId != null || tx.categoryLocked || tx.categoryId == transferCategory) continue
                val own = Categorizer.merchantKey(tx.counterparty) in holders
                val payout = wallets.isNotEmpty() && tx.accountId !in wallets &&
                    PayPalText.isPayout(tx.counterparty, tx.purpose, tx.amountCents)
                if (own || payout) changed[tx.id] = tx.copy(categoryId = transferCategory)
            }
        }
        if (changed.isNotEmpty()) db.transactions().updateAll(changed.values.toList())
    }

    /**
     * Merkt sich eine von Hand gewählte Kategorie für „dieselbe Zahlung" (Händler, sonst Verwendungszweck,
     * sonst Konto und Betrag): alle bisherigen gleichen Umsätze bekommen sie sofort, künftige beim Eingang,
     * und ein passender Dauerposten übernimmt sie ebenfalls.
     */
    suspend fun learnCategory(tx: TransactionEntity, categoryId: Long) =
        learnCategory(Categorizer.learnKey(tx.counterparty, tx.purpose, tx.accountId, tx.amountCents), categoryId, sign = null)

    /** [sign] beschränkt die Übernahme auf Ausgänge (−1) oder Eingänge (+1). */
    private suspend fun learnCategory(key: String, categoryId: Long, sign: Int?) = db.withTransaction {
        db.categories().deleteUserRule(key)
        db.categories().insertRules(listOf(CategoryRuleEntity(pattern = key, categoryId = categoryId, userDefined = true)))
        val affected = db.transactions().all().filter {
            !it.categoryLocked && it.categoryId != categoryId && it.transferPeerId == null &&
                (sign == null || (it.amountCents < 0) == (sign < 0)) &&
                Categorizer.learnKey(it.counterparty, it.purpose, it.accountId, it.amountCents) == key
        }
        if (affected.isNotEmpty()) db.transactions().updateAll(affected.map { it.copy(categoryId = categoryId) })
        val items = db.recurring().all().filter {
            it.categoryId != categoryId && it.matchKey.substringBeforeLast('|') == key &&
                (sign == null || it.matchKey.endsWith(if (sign < 0) "|-" else "|+"))
        }
        if (items.isNotEmpty()) db.recurring().updateAll(items.map { it.copy(categoryId = categoryId) })
    }

    val recurring: Flow<List<RecurringEntity>> = db.recurring().observeAll()
    val bankSessions: Flow<List<BankSessionEntity>> = db.bankSessions().observeAll()
    val budgets: Flow<List<BudgetEntity>> = db.planning().observeBudgets()
    val goals: Flow<List<SavingsGoalEntity>> = db.planning().observeGoals()

    suspend fun setBudget(budget: BudgetEntity) = db.planning().upsertBudget(budget)

    suspend fun removeBudget(categoryId: Long) = db.planning().deleteBudget(categoryId)

    suspend fun saveGoal(goal: SavingsGoalEntity) = db.planning().upsertGoal(goal)

    suspend fun deleteGoal(goal: SavingsGoalEntity) = db.planning().deleteGoal(goal)
    val loans: Flow<List<LoanEntity>> = db.loans().observeAll()
    val loanExtraPayments: Flow<List<LoanExtraPaymentEntity>> = db.loans().observeExtraPayments()

    suspend fun loan(id: Long): LoanEntity? = db.loans().byId(id)

    suspend fun saveLoan(loan: LoanEntity): Long {
        val id = db.loans().upsert(loan)
        return if (loan.id == 0L) id else loan.id
    }

    suspend fun deleteLoan(loan: LoanEntity) = db.loans().delete(loan)

    suspend fun addExtraPayment(payment: LoanExtraPaymentEntity) = db.loans().insertExtra(payment)

    suspend fun deleteExtraPayment(payment: LoanExtraPaymentEntity) = db.loans().deleteExtra(payment)

    val loanRateChanges: Flow<List<LoanRateChangeEntity>> = db.loans().observeRateChanges()

    suspend fun addRateChange(change: LoanRateChangeEntity) = db.loans().insertRateChange(change)

    suspend fun deleteRateChange(change: LoanRateChangeEntity) = db.loans().deleteRateChange(change)

    suspend fun transactionsOf(accountId: Long): List<TransactionEntity> = db.transactions().byAccount(accountId)

    suspend fun recurringItem(id: Long): RecurringEntity? = db.recurring().byId(id)

    /**
     * Speichert einen Dauerposten. Seine Kategorie gilt danach auch für alle bisherigen und künftigen
     * Umsätze dieser Zahlung.
     */
    suspend fun saveRecurring(item: RecurringEntity): Long = db.withTransaction {
        // Von Hand angelegte Posten bekommen den Schlüssel aus ihrem Namen, damit passende Umsätze sie fortschreiben.
        val keyed = if (item.detected) item else item.copy(matchKey = RecurringDetector.matchKey(item.name, item.amountCents))
        val id = db.recurring().upsert(keyed)
        val category = keyed.categoryId
        val key = keyed.matchKey.substringBeforeLast('|')
        if (category != null && key.isNotBlank()) {
            learnCategory(key, category, sign = if (keyed.matchKey.endsWith("|-")) -1 else 1)
        }
        id
    }

    suspend fun deleteRecurring(item: RecurringEntity) = db.withTransaction {
        if (item.detected) {
            // Erkannte Posten nur ausblenden, sonst schlägt die Erkennung sie beim nächsten Lauf wieder vor.
            db.recurring().upsert(item.copy(dismissed = true, active = false))
        } else {
            db.recurring().delete(item)
        }
    }

    /** Gleicht die Dauerposten mit der Umsatzhistorie ab: neue erkennen, Termine und Beträge fortschreiben. */
    suspend fun syncRecurring(today: LocalDate = LocalDate.now()) = db.withTransaction {
        val transferCategories = db.categories().all().filter { it.kind == CategoryKind.TRANSFER }.map { it.id }.toSet()
        val txs = db.transactions().all()
            .filter { it.transferPeerId == null && it.categoryId !in transferCategories }
            .map { it.toTx() }
        val detected = RecurringDetector.detect(txs, today).associateBy { it.matchKey }
        val existing = db.recurring().all()
        val known = existing.associateBy { it.matchKey }

        for ((key, found) in detected) {
            val old = known[key]
            when {
                old == null -> db.recurring().upsert(
                    RecurringEntity(
                        name = found.name,
                        matchKey = key,
                        amountCents = found.amountCents,
                        interval = found.interval,
                        nextDue = found.nextDue,
                        accountId = found.accountId,
                        categoryId = found.categoryId,
                        detected = true,
                        previousAmountCents = found.previousAmountCents,
                    ),
                )
                old.dismissed -> Unit
                else -> db.recurring().upsert(
                    old.copy(
                        amountCents = found.amountCents,
                        previousAmountCents = if (found.amountCents != old.amountCents) old.amountCents else old.previousAmountCents,
                        interval = if (old.detected) found.interval else old.interval,
                        nextDue = maxOf(found.nextDue, if (old.detected) found.nextDue else old.nextDue),
                        categoryId = old.categoryId ?: found.categoryId,
                        active = true,
                    ),
                )
            }
        }

        val latestByKey = txs.groupBy { RecurringDetector.matchKey(it) }
            .mapValues { entry -> entry.value.maxOf { it.date } }
        for (old in existing) {
            if (old.matchKey in detected || old.dismissed) continue
            if (old.detected && old.matchKey !in latestByKey) {
                // Kein einziger Umsatz trägt den Posten mehr (etwa weil die Buchungen als Umbuchung erkannt wurden).
                db.recurring().delete(old)
                continue
            }
            if (old.detected) {
                // Nicht mehr erkannt und deutlich überfällig: läuft offenbar nicht mehr.
                // Ebenso, wenn die letzten Buchungen inzwischen als Umbuchung zählen und der Rest lange zurückliegt.
                val latest = latestByKey.getValue(old.matchKey)
                val stale = old.nextDue.plusDays(45) < today || old.interval.next(latest).plusDays(10) < today
                if (old.active && stale) db.recurring().upsert(old.copy(active = false))
                continue
            }
            // Von Hand angelegt: Termin weiterschieben, sobald die passende Buchung da ist.
            val latest = latestByKey[old.matchKey] ?: continue
            var due = old.nextDue
            while (due <= latest.plusDays(5)) due = old.interval.next(due)
            if (due != old.nextDue) db.recurring().upsert(old.copy(nextDue = due))
        }
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
        if (source == TxSource.API || source == TxSource.IMPORT) {
            // Vorläufige Einträge aus Benachrichtigungen, die nach einer Woche keine Buchung bestätigt hat,
            // sind unter anderem Betrag gebucht worden (Trinkgeld, Fremdwährung) oder storniert: weg damit.
            val cutoff = LocalDate.now().minusDays(7)
            val stale = db.transactions().byAccount(accountId).filter {
                it.source == TxSource.NOTIFICATION && it.status == TxStatus.PENDING && it.bookingDate < cutoff
            }
            if (stale.isNotEmpty()) db.transactions().deleteByIds(stale.map { it.id })
        }
        if (balanceAfterCents != null) {
            val account = db.accounts().byId(accountId)
            if (account != null) {
                // Der genannte Stand umfasst nur fest gebuchte Umsätze; vorläufige zählen in der App zusätzlich.
                val sum = db.transactions().byAccount(accountId).filter { it.status == TxStatus.BOOKED }.sumOf { it.amountCents }
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
