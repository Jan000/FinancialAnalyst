package net.jandev.financialanalyst.domain

data class CategoryRule(
    /** Kleingeschriebener Teilstring. */
    val pattern: String,
    val categoryId: Long,
    val kind: CategoryKind,
    /** Aus einer Korrektur des Nutzers gelernt; schlägt die mitgelieferten Regeln. */
    val userDefined: Boolean = false,
)

/** Ordnet Umsätze anhand von Schlagwörtern in Gegenpartei und Verwendungszweck einer Kategorie zu. */
class Categorizer(rules: List<CategoryRule>) {
    // Nutzerregeln zuerst, sonst gewinnt das längste (spezifischste) Schlagwort.
    private val ordered = rules
        .filter { it.pattern.isNotBlank() }
        .sortedWith(compareByDescending<CategoryRule> { it.userDefined }.thenByDescending { it.pattern.length })

    fun categorize(counterparty: String, purpose: String, amountCents: Long): Long? {
        val text = normalize("$counterparty $purpose")
        val match = ordered.firstOrNull { rule ->
            // Einnahme-Kategorien passen nur auf Geldeingänge; eine Erstattung vom Supermarkt
            // darf dagegen in „Lebensmittel" landen und mindert dort die Ausgaben.
            (rule.kind != CategoryKind.INCOME || amountCents > 0) && text.contains(rule.pattern)
        }
        return match?.categoryId
    }

    companion object {
        /** Kleinbuchstaben, einfache Leerzeichen, mit Leerzeichen an den Rändern, damit Muster wie "o2 " am Wortende greifen. */
        fun normalize(text: String): String =
            " " + text.lowercase().replace(Regex("\\s+"), " ").trim() + " "

        /**
         * Schlüssel, unter dem ein Händler wiedererkannt wird: ohne Ziffernfolgen, Filialnummern und Rechtsformen,
         * z. B. "REWE Markt GmbH 4711" → "rewe markt".
         */
        fun merchantKey(counterparty: String): String =
            counterparty.lowercase()
                .replace(Regex("[*/#,.;:()\\[\\]_]+"), " ")
                .replace(Regex("\\b\\d[\\d-]*\\b"), " ")
                .replace(Regex("\\b(gmbh|ag|kg|ohg|ug|se|e\\.?k|co|mbh|inc|ltd|sarl|bv|ab|gbr|ev|sagt danke|haftungsbeschraenkt)\\b"), " ")
                .replace("&", " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
