package net.jandev.financialanalyst.domain

import kotlin.math.abs

enum class RuleField { COUNTERPARTY, PURPOSE, TEXT, AMOUNT, DIRECTION, ACCOUNT }

enum class RuleOp { CONTAINS, EQUALS, STARTS_WITH, NOT_CONTAINS, AT_LEAST, AT_MOST }

/**
 * Eine Bedingung einer Regel. [value] ist bei Textfeldern der gesuchte Text, bei [RuleField.AMOUNT] der Betrag
 * in Cent ohne Vorzeichen, bei [RuleField.DIRECTION] [RuleMatcher.OUT] oder [RuleMatcher.IN] und bei
 * [RuleField.ACCOUNT] die ID des Kontos.
 */
data class RuleCondition(val field: RuleField, val op: RuleOp, val value: String)

/** Regel des Nutzers: treffen alle Bedingungen zu, bekommt der Umsatz die Kategorie. */
data class AutoRule(val id: Long, val categoryId: Long, val conditions: List<RuleCondition>)

object RuleMatcher {
    const val OUT = "OUT"
    const val IN = "IN"

    private val textOps = listOf(RuleOp.CONTAINS, RuleOp.EQUALS, RuleOp.STARTS_WITH, RuleOp.NOT_CONTAINS)

    fun opsFor(field: RuleField): List<RuleOp> = when (field) {
        RuleField.COUNTERPARTY, RuleField.PURPOSE, RuleField.TEXT -> textOps
        RuleField.AMOUNT -> listOf(RuleOp.EQUALS, RuleOp.AT_LEAST, RuleOp.AT_MOST)
        RuleField.DIRECTION, RuleField.ACCOUNT -> listOf(RuleOp.EQUALS)
    }

    fun isValid(condition: RuleCondition): Boolean = condition.op in opsFor(condition.field) && when (condition.field) {
        RuleField.COUNTERPARTY, RuleField.PURPOSE, RuleField.TEXT -> condition.value.isNotBlank()
        RuleField.AMOUNT, RuleField.ACCOUNT -> condition.value.toLongOrNull() != null
        RuleField.DIRECTION -> condition.value == OUT || condition.value == IN
    }

    /** Eine Regel ohne Bedingungen trifft nie zu. */
    fun matches(conditions: List<RuleCondition>, counterparty: String, purpose: String, amountCents: Long, accountId: Long): Boolean =
        conditions.isNotEmpty() && conditions.all { matches(it, counterparty, purpose, amountCents, accountId) }

    private fun matches(c: RuleCondition, counterparty: String, purpose: String, amountCents: Long, accountId: Long): Boolean =
        when (c.field) {
            RuleField.COUNTERPARTY -> text(c, counterparty, Categorizer.merchantKey(counterparty))
            RuleField.PURPOSE -> text(c, purpose, Categorizer.merchantKey(purpose))
            // „Ist gleich" meint hier dieselbe Zahlung: den Händler und nur ohne Händler den Verwendungszweck.
            RuleField.TEXT -> text(
                c,
                "$counterparty $purpose",
                Categorizer.merchantKey(counterparty).takeIf { it.length >= 3 } ?: Categorizer.merchantKey(purpose),
                startsAlso = purpose,
            )
            RuleField.AMOUNT -> {
                val wanted = c.value.toLongOrNull()
                val actual = abs(amountCents)
                when {
                    wanted == null -> false
                    c.op == RuleOp.AT_LEAST -> actual >= wanted
                    c.op == RuleOp.AT_MOST -> actual <= wanted
                    else -> actual == wanted
                }
            }
            RuleField.DIRECTION -> if (c.value == IN) amountCents > 0 else amountCents < 0
            RuleField.ACCOUNT -> c.value.toLongOrNull() == accountId
        }

    /**
     * „Ist gleich" vergleicht ohne Ziffernfolgen, Filialnummern und Rechtsformen ([Categorizer.merchantKey]),
     * damit „REWE Markt GmbH 4711" und „REWE Markt 0815" dieselbe Zahlung sind.
     */
    private fun text(c: RuleCondition, raw: String, key: String, startsAlso: String? = null): Boolean {
        val needle = c.value.trim().lowercase()
        if (needle.isEmpty()) return false
        return when (c.op) {
            RuleOp.EQUALS -> {
                val wanted = Categorizer.merchantKey(c.value)
                if (wanted.isEmpty()) raw.trim().lowercase() == needle else key == wanted
            }
            RuleOp.STARTS_WITH -> raw.trim().lowercase().startsWith(needle) ||
                startsAlso?.trim()?.lowercase()?.startsWith(needle) == true
            RuleOp.NOT_CONTAINS -> !Categorizer.normalize(raw).contains(needle)
            else -> Categorizer.normalize(raw).contains(needle)
        }
    }

    /** Vorschlag für „dieselbe Zahlung": Händler, sonst Verwendungszweck, ganz ohne Text Konto und Betrag. */
    fun suggest(counterparty: String, purpose: String, accountId: Long, amountCents: Long): List<RuleCondition> = when {
        Categorizer.merchantKey(counterparty).length >= 3 ->
            listOf(RuleCondition(RuleField.COUNTERPARTY, RuleOp.EQUALS, counterparty.trim()))
        Categorizer.merchantKey(purpose).length >= 3 ->
            listOf(RuleCondition(RuleField.PURPOSE, RuleOp.EQUALS, purpose.trim()))
        else -> listOf(RuleCondition(RuleField.ACCOUNT, RuleOp.EQUALS, accountId.toString())) + sameAmount(amountCents)
    }

    fun sameAmount(amountCents: Long): List<RuleCondition> = listOf(
        RuleCondition(RuleField.AMOUNT, RuleOp.EQUALS, abs(amountCents).toString()),
        RuleCondition(RuleField.DIRECTION, RuleOp.EQUALS, if (amountCents > 0) IN else OUT),
    )

    /** Bedingungen zu einem Schlüssel aus [Categorizer.learnKey]; [sign] beschränkt auf Ausgänge (−1) oder Eingänge (+1). */
    fun fromLearnKey(key: String, sign: Int?): List<RuleCondition> {
        val exact = Regex("=(\\d+):(-?\\d+)").matchEntire(key)
        if (exact != null) {
            val amount = exact.groupValues[2].toLong()
            return listOf(RuleCondition(RuleField.ACCOUNT, RuleOp.EQUALS, exact.groupValues[1])) + sameAmount(amount)
        }
        val direction = sign?.let { RuleCondition(RuleField.DIRECTION, RuleOp.EQUALS, if (it < 0) OUT else IN) }
        return listOfNotNull(RuleCondition(RuleField.TEXT, RuleOp.EQUALS, key), direction)
    }
}
