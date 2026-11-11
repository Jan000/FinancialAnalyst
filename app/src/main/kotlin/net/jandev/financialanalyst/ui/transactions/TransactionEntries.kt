package net.jandev.financialanalyst.ui.transactions

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.jandev.financialanalyst.data.db.SplitEntity
import net.jandev.financialanalyst.data.db.TransactionEntity
import net.jandev.financialanalyst.data.toTx
import net.jandev.financialanalyst.domain.RecurringDetector
import net.jandev.financialanalyst.ui.common.LocalApp
import net.jandev.financialanalyst.ui.common.dayMonthLabel
import net.jandev.financialanalyst.ui.theme.LocalFinanceColors
import java.time.LocalDate

/** Namen, Farben und Zuordnungen, die eine Umsatzzeile zum Beschriften braucht. Überall dieselben, damit jede Liste gleich aussieht. */
class RowContext(
    val categoryNames: Map<Long, String>,
    val accountNames: Map<Long, String>,
    val categoryColors: Map<Long, Color>,
    val groupMarks: Map<Long, Pair<String, Color>>,
    val splitsByParent: Map<Long, List<SplitEntity>>,
    /** Anzeigename eines Fixkosten-Postens je Schlüssel seiner Buchungen. */
    val aliasByKey: Map<String, String>,
) {
    /** Wie die Zeile heißt: der Anzeigename des zugehörigen Dauerpostens, sonst Gegenpartei bzw. Verwendungszweck. */
    fun titleOf(tx: TransactionEntity): String? =
        if (tx.id <= 0 || aliasByKey.isEmpty()) null else aliasByKey[RecurringDetector.matchKey(tx.toTx())]
}

@Composable
fun rememberRowContext(): RowContext {
    val app = LocalApp.current
    val accounts by app.repository.accounts.collectAsStateWithLifecycle(initialValue = emptyList())
    val categories by app.repository.categories.collectAsStateWithLifecycle(initialValue = emptyList())
    val groups by app.repository.groups.collectAsStateWithLifecycle(initialValue = emptyList())
    val splits by app.repository.splits.collectAsStateWithLifecycle(initialValue = emptyList())
    val recurring by app.repository.recurring.collectAsStateWithLifecycle(initialValue = emptyList())
    val palette = LocalFinanceColors.current.chart
    return remember(accounts, categories, groups, splits, recurring, palette) {
        RowContext(
            categoryNames = categories.associate { it.id to it.name },
            accountNames = accounts.associate { it.account.id to it.account.name },
            categoryColors = categories.associate { it.id to palette[it.colorIndex.mod(palette.size)] },
            groupMarks = groups.associate { it.id to (it.name to palette[it.colorIndex.mod(palette.size)]) },
            splitsByParent = splits.groupBy { it.parentId },
            aliasByKey = recurring.filter { it.alias.isNotBlank() && !it.isPosition && !it.dismissed }.associate { it.matchKey to it.alias },
        )
    }
}

/** Wie eine Zeile entstanden ist: ein Umsatz, ein Kauf über ein Zahlungskonto oder eine Umbuchung. */
internal enum class PairKind { SINGLE, PAID_VIA, TRANSFER }

/**
 * Der Umsatz, für den eine Zeile steht: beim Kauf über PayPal der Kauf (er hat keinen Partner, die Bankseite zeigt
 * auf ihn), bei einer Umbuchung der Abgang.
 */
internal fun List<TransactionEntity>.mainOf(): TransactionEntity = when {
    size == 1 -> first()
    (this[0].amountCents < 0) == (this[1].amountCents < 0) -> firstOrNull { it.transferPeerId == null } ?: this[1]
    else -> minBy { it.amountCents }
}

internal fun List<TransactionEntity>.pairKind(): PairKind {
    if (size == 1) return PairKind.SINGLE
    return if ((this[0].amountCents < 0) == (this[1].amountCents < 0)) PairKind.PAID_VIA else PairKind.TRANSFER
}

/** Was eine Zeile zur Tagessumme beiträgt: beim Kauf über PayPal nur der Kauf, eine Umbuchung gleicht sich aus. */
internal fun List<TransactionEntity>.countedCents(): Long =
    if (pairKind() == PairKind.PAID_VIA) mainOf().amountCents else sumOf { it.amountCents }

/**
 * Fasst die zwei Seiten einer Umbuchung (auch Bankabbuchung und PayPal-Kauf) zu einer Zeile zusammen, neueste zuerst.
 * [extra] sind weitere Zeilen ohne Partner, etwa erwartete Buchungen.
 */
fun pairEntries(transactions: List<TransactionEntity>, extra: List<TransactionEntity> = emptyList()): List<List<TransactionEntity>> {
    val byId = transactions.associateBy { it.id }
    val partner = HashMap<Long, Long>()
    for (tx in transactions) {
        val peer = tx.transferPeerId ?: continue
        if (peer !in byId || tx.id in partner || peer in partner) continue
        partner[tx.id] = peer
        partner[peer] = tx.id
    }
    val placed = HashSet<Long>()
    val entries = ArrayList<List<TransactionEntity>>()
    for (tx in (extra + transactions).sortedByDescending { it.bookingDate }) {
        if (!placed.add(tx.id)) continue
        val other = partner[tx.id]?.let(byId::get)
        if (other == null) {
            entries.add(listOf(tx))
        } else {
            placed.add(other.id)
            entries.add(listOf(tx, other).sortedBy { it.amountCents })
        }
    }
    return entries
}

/**
 * Eine Zeile der Umsatzliste samt Positionen. [day] ist der Tag der Überschrift, unter der die Zeile steht; ohne
 * Überschrift (`null`) steht das Datum in der Zeile.
 */
@Composable
fun TransactionEntry(
    entry: List<TransactionEntity>,
    day: LocalDate?,
    ctx: RowContext,
    onClick: (TransactionEntity) -> Unit,
    onLongClick: ((TransactionEntity) -> Unit)? = null,
    dimmed: Boolean = false,
    horizontalPadding: Dp = 16.dp,
) {
    val main = entry.mainOf()
    val isPlanned = main.id < 0
    val other = entry.firstOrNull { it.id != main.id }
    val kind = entry.pairKind()
    // Weicht der Tag einer Seite von der Überschrift ab, steht ihr Datum dabei.
    val ownDate = main.bookingDate.dayMonthLabel().takeIf { main.bookingDate != day }
    val otherDate = other?.bookingDate?.takeIf { it != day && it != main.bookingDate }?.dayMonthLabel()
    val category = ctx.categoryNames[main.categoryId] ?: "Ohne Kategorie"
    val subtitle = when (kind) {
        PairKind.SINGLE -> listOfNotNull(
            // Erwartete Zeilen: „Geplant", bei offenen Vertragsbeiträgen seit wann sie offen sind.
            (main.note.ifBlank { "Geplant" }).takeIf { isPlanned },
            ctx.splitsByParent[main.id]?.size?.let { if (it == 1) "1 Position" else "$it Positionen" },
            ownDate,
            category,
            ctx.accountNames[main.accountId],
        )
        // Kauf über PayPal & Co.: es zählt der Kauf; die Abbuchung beim Konto ist nur die Finanzierung.
        PairKind.PAID_VIA -> listOfNotNull(
            ownDate,
            category,
            "${ctx.accountNames[main.accountId]} über ${ctx.accountNames[other!!.accountId]}" + (otherDate?.let { " ($it)" } ?: ""),
        )
        PairKind.TRANSFER -> listOfNotNull(
            ownDate,
            "${ctx.accountNames[main.accountId]} → ${ctx.accountNames[other!!.accountId]}",
            otherDate?.let { "Eingang $it" },
        )
    }.joinToString(" · ")
    TransactionRow(
        tx = main,
        title = if (kind == PairKind.TRANSFER) "Umbuchung" else ctx.titleOf(main),
        subtitle = subtitle,
        onClick = { onClick(main) },
        onLongClick = if (onLongClick == null || isPlanned || kind == PairKind.TRANSFER) null else ({ onLongClick(main) }),
        horizontalPadding = horizontalPadding,
        dimmed = dimmed,
        categoryColor = ctx.categoryColors[main.categoryId] ?: MaterialTheme.colorScheme.outlineVariant,
        group = ctx.groupMarks[main.groupId],
        // Eine Umbuchung ändert das Vermögen nicht: Betrag ohne Vorzeichen und Farbe.
        neutralAmount = kind == PairKind.TRANSFER,
    )
    ctx.splitsByParent[main.id]?.forEach { split ->
        SplitRow(
            split = split,
            categoryName = ctx.categoryNames[split.categoryId] ?: "Ohne Kategorie",
            categoryColor = ctx.categoryColors[split.categoryId] ?: MaterialTheme.colorScheme.outlineVariant,
            group = ctx.groupMarks[split.groupId],
            onClick = { onClick(main) },
        )
    }
}
