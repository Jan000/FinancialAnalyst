package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchRulesTest {
    private val domain = listOf(
        RuleCondition(RuleField.COUNTERPARTY, RuleOp.CONTAINS, "ionos"),
        RuleCondition(RuleField.AMOUNT, RuleOp.AT_LEAST, "2000"),
        RuleCondition(RuleField.AMOUNT, RuleOp.AT_MOST, "3000"),
    )

    @Test
    fun conditionsOfOneRuleMustAllMatch() {
        val rules = listOf(domain)
        assertTrue(MatchRules.matches(rules, "IONOS SE", "", -2400, 1))
        assertFalse(MatchRules.matches(rules, "IONOS SE", "", -300, 1))
        assertFalse(MatchRules.matches(rules, "Strato AG", "", -2400, 1))
    }

    @Test
    fun anyRuleIsEnough() {
        val rules = listOf(
            listOf(RuleCondition(RuleField.COUNTERPARTY, RuleOp.CONTAINS, "vodafone")),
            listOf(RuleCondition(RuleField.PURPOSE, RuleOp.CONTAINS, "kabel deutschland")),
        )
        assertTrue(MatchRules.matches(rules, "Vodafone GmbH", "", -3999, 1))
        assertTrue(MatchRules.matches(rules, "Lastschrift", "Kabel Deutschland Kd-Nr 1", -3999, 1))
        assertFalse(MatchRules.matches(rules, "Telekom", "", -3999, 1))
        assertFalse(MatchRules.matches(emptyList(), "Vodafone GmbH", "", -3999, 1))
    }

    @Test
    fun encodingSurvivesSeparatorsInText() {
        val rules = listOf(
            domain,
            listOf(RuleCondition(RuleField.PURPOSE, RuleOp.CONTAINS, "a;b|c%d\ne"), RuleCondition(RuleField.DIRECTION, RuleOp.EQUALS, RuleMatcher.OUT)),
        )
        assertEquals(rules, MatchRules.decode(MatchRules.encode(rules)))
        assertEquals(emptyList<List<RuleCondition>>(), MatchRules.decode(""))
    }
}
