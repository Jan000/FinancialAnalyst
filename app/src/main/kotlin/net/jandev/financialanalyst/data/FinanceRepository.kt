package net.jandev.financialanalyst.data

import net.jandev.financialanalyst.domain.MatchRules
import net.jandev.financialanalyst.data.db.RecurringMatcher
import net.jandev.financialanalyst.data.db.takesOver
import net.jandev.financialanalyst.data.db.RecurringIndex
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import net.jandev.financialanalyst.data.db.AccountEntity
import net.jandev.financialanalyst.data.db.AccountWithBalance
import net.jandev.financialanalyst.data.db.AppDatabase
import net.jandev.financialanalyst.data.db.AutoRuleConditionEntity
import net.jandev.financialanalyst.data.db.AutoRuleEntity
import net.jandev.financialanalyst.data.db.BankSessionEntity
import net.jandev.financialanalyst.data.db.BudgetEntity
import net.jandev.financialanalyst.data.db.SavingsGoalEntity
import net.jandev.financialanalyst.data.db.SplitEntity
import net.jandev.financialanalyst.data.db.CategoryEntity
import net.jandev.financialanalyst.data.db.CategoryRuleEntity
import net.jandev.financialanalyst.data.db.GroupEntity
import net.jandev.financialanalyst.data.db.LoanEntity
import net.jandev.financialanalyst.data.db.LoanExtraPaymentEntity
import net.jandev.financialanalyst.data.db.LoanRateChangeEntity
import net.jandev.financialanalyst.data.db.RecurringEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.domain.AccountType
import net.jandev.financialanalyst.domain.AutoRule
import net.jandev.financialanalyst.domain.RuleCondition
import net.jandev.financialanalyst.domain.RuleField
import net.jandev.financialanalyst.domain.RuleMatcher
import net.jandev.financialanalyst.domain.RuleOp
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
import net.jandev.financialanalyst.domain.ContractSchedule
import net.jandev.financialanalyst.domain.ScheduleChange
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

    /** Legt eine Kategorie an oder ändert sie; liefert die ID. */
    suspend fun saveCategory(category: CategoryEntity): Long =
        if (category.id == 0L) db.categories().insert(category) else category.id.also { db.categories().update(category) }

    /** Entfernt eine Kategorie; ihre Umsätze und Dauerposten stehen danach ohne Kategorie da und werden neu zugeordnet. */
    suspend fun deleteCategory(category: CategoryEntity) = db.withTransaction {
        db.transactions().clearCategory(category.id)
        db.recurring().clearCategory(category.id)
        db.splits().clearCategory(category.id)
        db.planning().deleteBudget(category.id)
        db.rules().deleteOfCategory(category.id)
        db.categories().deleteRulesOf(category.id)
        db.categories().delete(category)
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
            loadRules().filter { it.rule.enabled && it.rule.categoryId in kinds }
                .map { AutoRule(it.rule.id, it.rule.categoryId, it.conditions) },
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

    val rules: Flow<List<RuleWithConditions>> =
        combine(db.rules().observeRules(), db.rules().observeConditions()) { rules, conditions -> join(rules, conditions) }

    private fun join(rules: List<AutoRuleEntity>, conditions: List<AutoRuleConditionEntity>): List<RuleWithConditions> {
        val byRule = conditions.groupBy { it.ruleId }
        return rules.map { rule ->
            RuleWithConditions(rule, byRule[rule.id].orEmpty().map { RuleCondition(it.field, it.op, it.value) })
        }
    }

    private suspend fun loadRules(): List<RuleWithConditions> = join(db.rules().rules(), db.rules().conditions())

    suspend fun rule(id: Long): RuleWithConditions? = loadRules().firstOrNull { it.rule.id == id }

    /** Speichert eine Regel; eine ältere mit genau denselben Bedingungen wird ersetzt. Liefert die ID. */
    suspend fun saveRule(rule: AutoRuleEntity, conditions: List<RuleCondition>): Long = db.withTransaction {
        val same = loadRules().filter { it.rule.id != rule.id && it.conditions.toSet() == conditions.toSet() }.map { it.rule.id }
        if (same.isNotEmpty()) db.rules().deleteByIds(same)
        val inserted = db.rules().upsert(rule)
        val id = if (rule.id == 0L) inserted else rule.id
        db.rules().deleteConditions(id)
        db.rules().insertConditions(conditions.map { AutoRuleConditionEntity(ruleId = id, field = it.field, op = it.op, value = it.value) })
        id
    }

    suspend fun deleteRule(rule: AutoRuleEntity) = db.rules().delete(rule)

    /**
     * Setzt die Kategorie der genannten Umsätze; Dauerposten dieser Zahlungen übernehmen sie.
     * Umsätze in [keepOut] hat der Nutzer bewusst ausgenommen: ohne Kategorie bleiben sie von der Automatik verschont.
     */
    suspend fun applyCategory(transactionIds: Collection<Long>, categoryId: Long, keepOut: Collection<Long> = emptyList()) =
        db.withTransaction {
            val ids = transactionIds.toSet()
            val out = keepOut.toSet()
            val all = db.transactions().all()
            val changed = all.filter { it.id in ids && it.categoryId != categoryId }.map { it.copy(categoryId = categoryId) }
            val excluded = all.filter { it.id in out && it.categoryId == null && !it.categoryLocked }.map { it.copy(categoryLocked = true) }
            if (changed.isNotEmpty() || excluded.isNotEmpty()) db.transactions().updateAll(changed + excluded)
            val index = RecurringIndex(db.recurring().all().filter { it.categoryId != categoryId })
            val items = all.filter { it.id in ids }.mapNotNull { index.find(it) }.distinctBy { it.id }
            if (items.isNotEmpty()) db.recurring().updateAll(items.map { it.copy(categoryId = categoryId) })
        }

    val splits: Flow<List<SplitEntity>> = db.splits().observeAll()

    /** Umsätze für Auswertung, Budgets und Gruppen: aufgeteilte Umsätze durch ihre Positionen ersetzt. */
    val analysisTransactions: Flow<List<TransactionEntity>> = combine(transactions, splits) { t, s -> expandSplits(t, s) }

    suspend fun saveSplit(split: SplitEntity) = db.splits().upsert(split)

    suspend fun deleteSplit(split: SplitEntity) = db.splits().delete(split)

    /** Nimmt einen Umsatz oder eine Position (aus [analysisTransactions]) aus seiner Gruppe. */
    suspend fun removeFromGroup(tx: TransactionEntity) {
        if (tx.isSplitPart) db.splits().setGroup(-1_000_000_000L - tx.id, null) else setGroup(listOf(tx.id), null)
    }

    val groups: Flow<List<GroupEntity>> = db.groups().observeAll()

    /** Liefert die ID der gespeicherten Gruppe. */
    suspend fun saveGroup(group: GroupEntity): Long {
        val id = db.groups().upsert(group)
        return if (group.id == 0L) id else group.id
    }

    suspend fun deleteGroup(group: GroupEntity) = db.withTransaction {
        db.transactions().clearGroup(group.id)
        db.splits().clearGroup(group.id)
        db.groups().delete(group)
    }

    /** Legt Umsätze in eine Gruppe; `null` nimmt sie heraus. */
    suspend fun setGroup(transactionIds: List<Long>, groupId: Long?) {
        // In Blöcken, damit die Parameterzahl einer SQL-Anweisung nicht überschritten wird.
        transactionIds.chunked(500).forEach { db.transactions().setGroup(it, groupId) }
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

    suspend fun updateExtraPayment(payment: LoanExtraPaymentEntity) = db.loans().updateExtra(payment)

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
        // Von Hand angelegte Posten bekommen den Schlüssel aus ihrem Namen, damit passende Umsätze sie fortschreiben;
        // aus Buchungen angelegte bringen den Schlüssel dieser Buchungen schon mit.
        val withKey = when {
            // Positionen einer Sammelabrechnung haben keine eigenen Buchungen: ein Schlüssel, den kein Umsatz trägt.
            item.isPosition -> item.copy(matchKey = "pos:${item.parentKey}:${Categorizer.merchantKey(item.name)}")
            item.detected || item.matchKey.isNotBlank() -> item
            else -> item.copy(matchKey = RecurringDetector.matchKey(item.name, item.amountCents))
        }
        // Gibt es für diese Zahlung schon einen Posten (etwa einen erkannten), wird er übernommen statt verdoppelt –
        // aber nur, wenn er dieselben Buchungen meint: zwei Verträge beim selben Anbieter bleiben getrennt.
        val twin = if (withKey.id == 0L) db.recurring().all().firstOrNull { withKey.takesOver(it) } else null
        val merged = if (twin != null) withKey.copy(id = twin.id, detected = false, dismissed = false) else withKey
        // Vertrag: der nächste Termin ergibt sich aus Zahlungsplan und Buchungen, nicht aus der Eingabe.
        val keyed = if (merged.isContract) merged.copy(nextDue = contractNextDue(merged, db.transactions().all())) else merged
        val inserted = db.recurring().upsert(keyed)
        val id = if (keyed.id == 0L) inserted else keyed.id
        val category = keyed.categoryId
        val key = keyed.matchKey.substringBeforeLast('|')
        if (keyed.isPosition) {
            applySplitTemplates()
        } else if (category != null && keyed.hasOwnRules) {
            // Eigene Regeln: jede wird zu einer Kategorie-Regel; die bisherigen Regeln dieses Postens ersetzen sie.
            val own = MatchRules.decode(keyed.matchRules)
            val byKey = if (key.isNotBlank()) RuleMatcher.fromLearnKey(key, sign = if (keyed.matchKey.endsWith("|-")) -1 else 1) else emptyList()
            // Regeln anderer Posten mit eigenen Regeln (etwa ein zweiter Vertrag beim selben Anbieter) bleiben.
            val foreign = db.recurring().all().filter { it.id != id && it.hasOwnRules }
                .flatMap { MatchRules.decode(it.matchRules) }.map { it.toSet() }.toSet()
            val outdated = loadRules().filter {
                it.rule.categoryId == category && (it.rule.name == keyed.name || it.conditions.toSet() == byKey.toSet()) &&
                    own.none { rule -> rule.toSet() == it.conditions.toSet() } && it.conditions.toSet() !in foreign
            }
            if (outdated.isNotEmpty()) db.rules().deleteByIds(outdated.map { it.rule.id })
            own.forEach { saveRule(AutoRuleEntity(name = keyed.name, categoryId = category), it) }
            val all = db.transactions().all()
            val matcher = RecurringMatcher(keyed)
            applyCategory(all.filter { !it.categoryLocked && it.transferPeerId == null && it.categoryId != category && matcher.matches(it) }.map { it.id }, category)
            if (outdated.isNotEmpty()) {
                // Was nur eine alte Regel in diese Kategorie gebracht hat und nicht mehr dazugehört, wird neu zugeordnet.
                val reset = all.filter {
                    it.categoryId == category && !it.categoryLocked && it.transferPeerId == null && !matcher.matches(it) &&
                        outdated.any { rule -> RuleMatcher.matches(rule.conditions, it.counterparty, it.purpose, it.amountCents, it.accountId) }
                }
                if (reset.isNotEmpty()) {
                    db.transactions().updateAll(reset.map { it.copy(categoryId = null) })
                    enrich()
                }
            }
        } else if (category != null && key.isNotBlank()) {
            val byKey = RuleMatcher.fromLearnKey(key, sign = if (keyed.matchKey.endsWith("|-")) -1 else 1)
            val all = db.transactions().all()
            val sameKey = all.filter { RecurringDetector.matchKey(it.toTx()) == keyed.matchKey }
            // Andere Käufe beim selben Anbieter (etwa ein Zusatzpaket neben der Domain) behalten ihre eigene Kategorie:
            // die Regel gilt dann nur für Beträge, die zu diesem Posten passen.
            val others = sameKey.filter { !keyed.covers(it.amountCents) }
            val conditions = if (others.isEmpty()) byKey else {
                val range = keyed.coveredRange()
                byKey + RuleCondition(RuleField.AMOUNT, RuleOp.AT_LEAST, range.first.toString()) +
                    RuleCondition(RuleField.AMOUNT, RuleOp.AT_MOST, range.last.toString())
            }
            // Eine frühere Regel dieses Postens nur zum Anbieter (ohne Betrag) ersetzt die neue.
            val outdated = loadRules().filter {
                it.rule.categoryId == category && it.conditions.toSet() == byKey.toSet() && conditions != byKey
            }
            if (outdated.isNotEmpty()) db.rules().deleteByIds(outdated.map { it.rule.id })
            saveRule(AutoRuleEntity(name = keyed.name, categoryId = category), conditions)
            val affected = all.filter { !it.categoryLocked }.matching(conditions, category)
            applyCategory(affected.map { it.id }, category)
            if (outdated.isNotEmpty()) {
                // Was nur die alte Regel in diese Kategorie gebracht hat, wird neu zugeordnet.
                val reset = others.filter { it.categoryId == category && !it.categoryLocked && it.transferPeerId == null }
                if (reset.isNotEmpty()) {
                    db.transactions().updateAll(reset.map { it.copy(categoryId = null) })
                    enrich()
                }
            }
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

    /**
     * Trägt fällige Lieferungen wiederkehrender Positionen (Spar-Abos) in die Sammelabrechnung ein, mit der sie bezahlt
     * wurden: die erste Abrechnung am oder nach dem Liefertermin. Jede Lieferung nur einmal; was noch keine Abrechnung
     * hat, wartet als nächster Termin.
     */
    suspend fun applySplitTemplates() = db.withTransaction {
        val templates = db.recurring().all().filter { it.isPosition && it.active && !it.dismissed }
        if (templates.isEmpty()) return@withTransaction
        val transactions = db.transactions().all()
        val existing = db.splits().all()
        for (template in templates) {
            val settlements = transactions.filter { RecurringDetector.matchKey(it.toTx()) == template.parentKey }.sortedBy { it.bookingDate }
            var due = template.nextDue
            val created = ArrayList<SplitEntity>()
            while (created.size < 120) {
                val settlement = settlements.firstOrNull { it.bookingDate >= due } ?: break
                if (existing.none { it.recurringId == template.id && it.date == due }) {
                    created.add(
                        SplitEntity(
                            parentId = settlement.id,
                            date = due,
                            amountCents = template.amountCents,
                            description = template.displayName,
                            categoryId = template.categoryId,
                            groupId = template.groupId,
                            recurringId = template.id,
                        ),
                    )
                }
                due = template.interval.next(due)
            }
            if (created.isNotEmpty()) db.splits().insertAll(created)
            if (due != template.nextDue) db.recurring().upsert(template.copy(nextDue = due))
        }
    }

    /** Ältester offener Termin eines Vertrags, nachdem die passenden Buchungen den Terminen zugeordnet sind. */
    private fun contractNextDue(item: RecurringEntity, transactions: List<TransactionEntity>, today: LocalDate = LocalDate.now()): LocalDate =
        ContractSchedule.nextOpen(
            firstDue = item.firstDue ?: return item.nextDue,
            interval = item.interval,
            amountCents = item.amountCents,
            changes = ScheduleChange.decode(item.scheduleChanges),
            bookings = contractBookings(item, transactions),
            today = today,
        )

    /** Buchungen, die zu einem Vertrag gehören. */
    fun contractBookings(item: RecurringEntity, transactions: List<TransactionEntity>): List<Pair<LocalDate, Long>> {
        val matcher = RecurringMatcher(item)
        return transactions.filter(matcher::matches).map { it.bookingDate to it.amountCents }
    }

    /** Gleicht die Dauerposten mit der Umsatzhistorie ab: neue erkennen, Termine und Beträge fortschreiben. */
    suspend fun syncRecurring(today: LocalDate = LocalDate.now()) = db.withTransaction {
        val transferCategories = db.categories().all().filter { it.kind == CategoryKind.TRANSFER }.map { it.id }.toSet()
        val everything = db.transactions().all()
        val txs = everything
            .filter { it.transferPeerId == null && it.categoryId !in transferCategories }
            .map { it.toTx() }
        // Jüngste Buchung je Schlüssel, Umbuchungen eingeschlossen.
        val latestOfAnyKind = everything.groupBy { RecurringDetector.matchKey(it.toTx()) }
            .mapValues { entry -> entry.value.maxOf { it.bookingDate } }
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
                // Ein Vertrag folgt seinem Zahlungsplan, eigene Regeln bestimmen ihre Buchungen selbst.
                old.isContract || old.hasOwnRules -> Unit
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

        val byKey = txs.groupBy { RecurringDetector.matchKey(it) }
        val latestByKey = byKey.mapValues { entry -> entry.value.maxOf { it.date } }
        for (old in existing) {
            if (old.isContract) {
                val due = contractNextDue(old, everything, today)
                if (due != old.nextDue) db.recurring().upsert(old.copy(nextDue = due))
                continue
            }
            if (old.matchKey in detected || old.dismissed) continue
            if (old.detected && old.matchKey !in latestByKey) {
                // Kein einziger Umsatz trägt den Posten mehr (etwa weil die Buchungen als Umbuchung erkannt wurden).
                db.recurring().delete(old)
                continue
            }
            if (old.detected) {
                val latest = latestByKey.getValue(old.matchKey)
                if ((latestOfAnyKind[old.matchKey] ?: latest) > latest) {
                    // Die jüngsten Buchungen zählen inzwischen als Umbuchung (etwa die Bankseite einer PayPal-Zahlung):
                    // der Posten war nur der Schatten einer Zahlung, die auf dem anderen Konto schon geführt wird.
                    db.recurring().delete(old)
                    continue
                }
                // Nicht mehr erkannt und deutlich überfällig: läuft offenbar nicht mehr.
                val stale = old.nextDue.plusDays(45) < today || old.interval.next(latest).plusDays(10) < today
                if (old.active && stale) db.recurring().upsert(old.copy(active = false))
                continue
            }
            // Von Hand angelegt: Termin weiterschieben, sobald die passende Buchung da ist.
            val latest = if (old.hasOwnRules) {
                val matcher = RecurringMatcher(old)
                txs.filter(matcher::matches).maxOfOrNull { it.date }
            } else {
                byKey[old.matchKey]?.filter { old.covers(it.amountCents) }?.maxOfOrNull { it.date }
            } ?: continue
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

private const val SPLIT_PREFIX = "split:"

/**
 * Ersetzt jeden aufgeteilten Umsatz durch seine Positionen und den nicht aufgeteilten Rest. Positionen erscheinen als
 * Umsätze mit negativer ID; [editTargetId] führt von ihnen zum eigentlichen Umsatz. Die Summe je Konto bleibt gleich.
 */
fun expandSplits(transactions: List<TransactionEntity>, splits: List<SplitEntity>): List<TransactionEntity> {
    if (splits.isEmpty()) return transactions
    val byParent = splits.groupBy { it.parentId }
    return transactions.flatMap { tx ->
        val parts = byParent[tx.id] ?: return@flatMap listOf(tx)
        val rest = tx.amountCents - parts.sumOf { it.amountCents }
        parts.map { part ->
            TransactionEntity(
                id = -1_000_000_000L - part.id,
                accountId = tx.accountId,
                bookingDate = part.date,
                amountCents = part.amountCents,
                counterparty = part.description.ifBlank { tx.counterparty },
                purpose = "Position von ${tx.counterparty.ifBlank { tx.purpose }}",
                categoryId = part.categoryId,
                categoryLocked = true,
                externalId = SPLIT_PREFIX + tx.id,
                groupId = part.groupId,
            )
        } + listOfNotNull(tx.copy(amountCents = rest).takeIf { rest != 0L })
    }
}

/** Der Umsatz, den man zum Bearbeiten öffnet: bei einer Position der aufgeteilte Umsatz. */
val TransactionEntity.editTargetId: Long
    get() = if (id < 0 && externalId?.startsWith(SPLIT_PREFIX) == true) externalId.removePrefix(SPLIT_PREFIX).toLong() else id

val TransactionEntity.isSplitPart: Boolean get() = id < 0 && externalId?.startsWith(SPLIT_PREFIX) == true

data class RuleWithConditions(val rule: AutoRuleEntity, val conditions: List<RuleCondition>)

/** Umsätze, auf die die Bedingungen zutreffen und die noch nicht in [categoryId] liegen; erkannte Umbuchungen bleiben außen vor. */
fun List<TransactionEntity>.matching(conditions: List<RuleCondition>, categoryId: Long?): List<TransactionEntity> = filter {
    it.transferPeerId == null && it.categoryId != categoryId &&
        RuleMatcher.matches(conditions, it.counterparty, it.purpose, it.amountCents, it.accountId)
}

data class ImportSummary(val inserted: Int, val confirmed: Int, val duplicates: Int)
