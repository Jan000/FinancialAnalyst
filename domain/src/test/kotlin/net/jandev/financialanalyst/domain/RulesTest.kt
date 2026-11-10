package net.jandev.financialanalyst.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RulesTest {
    private fun matches(vararg conditions: RuleCondition, counterparty: String = "", purpose: String = "", amount: Long = -1000, account: Long = 1) =
        RuleMatcher.matches(conditions.toList(), counterparty, purpose, amount, account)

    @Test
    fun equalsIgnoresBranchNumbersAndLegalForms() {
        val rule = RuleCondition(RuleField.COUNTERPARTY, RuleOp.EQUALS, "REWE Markt GmbH 4711")
        assertTrue(matches(rule, counterparty = "REWE Markt 0815"))
        assertFalse(matches(rule, counterparty = "REWE Markt to go"))
        assertFalse(matches(rule, purpose = "REWE Markt"))
    }

    @Test
    fun textOperators() {
        assertTrue(matches(RuleCondition(RuleField.PURPOSE, RuleOp.CONTAINS, "Miete"), purpose = "Dauerauftrag MIETE November"))
        assertTrue(matches(RuleCondition(RuleField.TEXT, RuleOp.CONTAINS, "miete"), counterparty = "Erika Muster", purpose = "Miete"))
        assertTrue(matches(RuleCondition(RuleField.COUNTERPARTY, RuleOp.STARTS_WITH, "amazon"), counterparty = "Amazon Payments"))
        assertFalse(matches(RuleCondition(RuleField.COUNTERPARTY, RuleOp.STARTS_WITH, "payments"), counterparty = "Amazon Payments"))
        assertTrue(matches(RuleCondition(RuleField.PURPOSE, RuleOp.NOT_CONTAINS, "prime"), counterparty = "Amazon", purpose = "Bestellung"))
        assertFalse(matches(RuleCondition(RuleField.PURPOSE, RuleOp.NOT_CONTAINS, "prime"), purpose = "Prime Video"))
    }

    @Test
    fun allConditionsMustHold() {
        val rent = arrayOf(
            RuleCondition(RuleField.COUNTERPARTY, RuleOp.EQUALS, "Erika Muster"),
            RuleCondition(RuleField.AMOUNT, RuleOp.AT_LEAST, "50000"),
            RuleCondition(RuleField.DIRECTION, RuleOp.EQUALS, RuleMatcher.OUT),
        )
        assertTrue(matches(*rent, counterparty = "Erika Muster", amount = -65000))
        assertFalse(matches(*rent, counterparty = "Erika Muster", amount = -2000))
        assertFalse(matches(*rent, counterparty = "Erika Muster", amount = 65000))
        assertFalse(matches(counterparty = "Erika Muster"))
    }

    @Test
    fun amountAndAccount() {
        assertTrue(matches(RuleCondition(RuleField.AMOUNT, RuleOp.EQUALS, "1000"), amount = 1000))
        assertTrue(matches(RuleCondition(RuleField.AMOUNT, RuleOp.AT_MOST, "1000"), amount = -999))
        assertFalse(matches(RuleCondition(RuleField.ACCOUNT, RuleOp.EQUALS, "2")))
        assertFalse(RuleMatcher.isValid(RuleCondition(RuleField.AMOUNT, RuleOp.EQUALS, "zehn")))
        assertFalse(RuleMatcher.isValid(RuleCondition(RuleField.AMOUNT, RuleOp.CONTAINS, "10")))
    }

    @Test
    fun suggestionFollowsTheAvailableText() {
        assertEquals(
            listOf(RuleCondition(RuleField.COUNTERPARTY, RuleOp.EQUALS, "Stadtwerke")),
            RuleMatcher.suggest(" Stadtwerke ", "Abschlag", 1, -5000),
        )
        assertEquals(RuleField.PURPOSE, RuleMatcher.suggest("", "Abschlag Strom", 1, -5000).single().field)
        // Ganz ohne Buchungstext: Konto, Betrag und Richtung.
        val bare = RuleMatcher.suggest("", "", 7, -5000)
        assertTrue(RuleMatcher.matches(bare, "", "", -5000, 7))
        assertFalse(RuleMatcher.matches(bare, "", "", 5000, 7))
        assertFalse(RuleMatcher.matches(bare, "", "", -5000, 8))
    }

    @Test
    fun learnedKeysBecomeConditions() {
        val byName = RuleMatcher.fromLearnKey("erika muster", sign = -1)
        assertTrue(RuleMatcher.matches(byName, "Erika Muster", "Miete", -65000, 1))
        assertFalse(RuleMatcher.matches(byName, "Erika Muster", "Danke", 2000, 1))
        // Der Zweck zählt nur, wenn kein Händler genannt ist.
        assertTrue(RuleMatcher.matches(RuleMatcher.fromLearnKey("abschlag strom", null), "", "Abschlag Strom 11", -100, 1))
        assertFalse(RuleMatcher.matches(RuleMatcher.fromLearnKey("abschlag strom", null), "Stadtwerke", "Abschlag Strom", -100, 1))
        val bare = RuleMatcher.fromLearnKey("=3:-4999", null)
        assertTrue(RuleMatcher.matches(bare, "", "", -4999, 3))
        assertFalse(RuleMatcher.matches(bare, "", "", -4999, 4))
    }

    @Test
    fun ownRulesBeatKeywordsAndTheMoreSpecificWins() {
        val categorizer = Categorizer(
            rules = listOf(CategoryRule("amazon", 1, CategoryKind.EXPENSE)),
            autoRules = listOf(
                AutoRule(1, 2, listOf(RuleCondition(RuleField.COUNTERPARTY, RuleOp.CONTAINS, "amazon"))),
                AutoRule(
                    2, 3,
                    listOf(
                        RuleCondition(RuleField.COUNTERPARTY, RuleOp.CONTAINS, "amazon"),
                        RuleCondition(RuleField.AMOUNT, RuleOp.EQUALS, "899"),
                    ),
                ),
            ),
        )
        assertEquals(2L, categorizer.categorize("Amazon Payments", "", -2500))
        assertEquals(3L, categorizer.categorize("Amazon Payments", "", -899))
        assertNull(categorizer.categorize("Bäckerei", "", -300))
    }
}
