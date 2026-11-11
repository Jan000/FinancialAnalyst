package net.jandev.financialanalyst.domain

import java.time.LocalDate
import java.time.YearMonth

/**
 * Bereich, in den Fixkosten fallen: fasst verwandte Kategorien zusammen, damit die Übersicht mit wenigen Gruppen
 * auskommt. [cancellable] sagt, ob sich Posten dieses Bereichs typischerweise kündigen lassen; [switchable], ob
 * man eher den Anbieter wechselt.
 */
enum class CostArea(val label: String, val cancellable: Boolean, val switchable: Boolean) {
    HOUSING("Wohnen & Energie", cancellable = false, switchable = true),
    MOBILITY("Mobilität", cancellable = true, switchable = false),
    INSURANCE("Versicherungen", cancellable = false, switchable = true),
    LOANS("Kredite & Gebühren", cancellable = false, switchable = false),
    DIGITAL("Abos & Digitales", cancellable = true, switchable = false),
    LIVING("Leben & Alltag", cancellable = true, switchable = false),
    SAVING("Sparen", cancellable = false, switchable = false),
    OTHER("Sonstiges", cancellable = true, switchable = false),
    ;

    companion object {
        private val byKey = mapOf(
            "housing" to HOUSING, "utilities" to HOUSING,
            "car" to MOBILITY, "transit" to MOBILITY,
            "insurance" to INSURANCE,
            "loans" to LOANS, "fees" to LOANS,
            "subscriptions" to DIGITAL, "telecom" to DIGITAL, "education" to DIGITAL,
            "groceries" to LIVING, "restaurant" to LIVING, "shopping" to LIVING, "health" to LIVING,
            "leisure" to LIVING, "cash" to LIVING,
            "saving" to SAVING,
        )

        /** Eigene Kategorien ordnen sich über Stichwörter im Namen ein. */
        private val byName = listOf(
            Regex("server|hosting|domain|cloud|software|app|digital|internet|handy|telefon|stream", RegexOption.IGNORE_CASE) to DIGITAL,
            Regex("miete|wohn|strom|gas|energie|wasser|heiz", RegexOption.IGNORE_CASE) to HOUSING,
            Regex("auto|kfz|tank|bahn|bus|fahr|reise", RegexOption.IGNORE_CASE) to MOBILITY,
            Regex("versicher", RegexOption.IGNORE_CASE) to INSURANCE,
            Regex("kredit|darlehen|zins|steuer|gebühr", RegexOption.IGNORE_CASE) to LOANS,
            Regex("spar|invest|depot|rente", RegexOption.IGNORE_CASE) to SAVING,
            Regex("katze|hund|tier|rauch|sport|fitness|hobby|essen|drogerie|kind", RegexOption.IGNORE_CASE) to LIVING,
        )

        fun of(builtinKey: String?, categoryName: String?): CostArea {
            builtinKey?.let { key -> byKey[key]?.let { return it } }
            val name = categoryName ?: return OTHER
            return byName.firstOrNull { it.first.containsMatchIn(name) }?.second ?: OTHER
        }
    }
}

/**
 * Art eines Dienstes, an der sich doppelte Abos erkennen lassen (zwei Cloud-Speicher, drei Videodienste).
 * Erkannt über Stichwörter in Bezeichnung und Anzeigename.
 */
enum class ServiceFamily(val label: String, private val pattern: Regex) {
    CLOUD("Cloud-Speicher", Regex("google one|onedrive|icloud|dropbox|magenta ?cloud|pcloud|cloud-?speicher|speicherplatz|\\bspeicher\\b")),
    VIDEO("Video-Streaming", Regex("netflix|disney|prime video|amazon prime|\\bwow\\b|sky (ticket|deutschland|abo)|dazn|paramount|rtl\\+|\\bjoyn\\b|apple tv|waipu|zattoo|crunchyroll")),
    MUSIC("Musik-Streaming", Regex("spotify|apple music|deezer|tidal|youtube premium|youtube music|amazon music")),
    AI("KI-Dienste", Regex("anthropic|claude|openai|chatgpt|perplexity|gemini advanced|copilot pro|midjourney")),
    DOMAINS("Domains & Hosting", Regex("strato|ionos|hetzner|netcup|all-inkl|godaddy|namecheap|\\bdomains?\\b|hosting|webspace")),
    MOBILE("Mobilfunk", Regex("fraenk|congstar|vodafone|\\bo2\\b|aldi talk|freenet|\\bblau\\b|mobilfunk|handyvertrag|simyo|winsim")),
    FITNESS("Fitness", Regex("mcfit|fitx|urban sports|fitness|\\bgym\\b|clever fit|john reed")),
    NEWS("Zeitungen & Magazine", Regex("spiegel\\+?|zeit online|faz\\b|süddeutsche|bild\\+|readly|zeitung|magazin")),
    ;

    companion object {
        fun of(vararg texts: String): ServiceFamily? {
            val text = texts.joinToString(" ").lowercase()
            return entries.firstOrNull { it.pattern.containsMatchIn(text) }
        }
    }
}

/** Monatssummen von Zahlungen: ein Wert je Monat ab [from], [count] Monate; Beträge ohne Vorzeichen. */
object FixedCostCalendar {
    fun monthlyTotals(payments: List<Pair<LocalDate, Long>>, from: YearMonth, count: Int): List<Long> {
        val totals = LongArray(count)
        for ((date, cents) in payments) {
            val index = monthIndex(from, YearMonth.from(date))
            if (index in 0 until count) totals[index] += kotlin.math.abs(cents)
        }
        return totals.toList()
    }

    fun monthIndex(from: YearMonth, month: YearMonth): Int = ((month.year - from.year) * 12 + month.monthValue - from.monthValue)
}
