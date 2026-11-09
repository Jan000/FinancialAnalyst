package net.jandev.financialanalyst.domain

import kotlin.math.abs

/** Geldbeträge werden überall als ganze Cent (`Long`) geführt; hier liegen Formatierung und Parsen. */
object Money {

    /** `123456` → `1.234,56 €`, `-5` → `-0,05 €`. */
    fun format(cents: Long, withSign: Boolean = false, currency: String = "€"): String {
        val absolute = abs(cents)
        val euros = (absolute / 100).toString().reversed().chunked(3).joinToString(".").reversed()
        val rest = (absolute % 100).toString().padStart(2, '0')
        val sign = when {
            cents < 0 -> "-"
            withSign && cents > 0 -> "+"
            else -> ""
        }
        return "$sign$euros,$rest $currency"
    }

    /**
     * Liest Beträge in deutscher (`1.234,56`) und englischer (`1,234.56`) Schreibweise,
     * mit optionalem Vorzeichen, Währungszeichen und Leerzeichen. `null`, wenn nichts Lesbares drinsteht.
     */
    fun parse(text: String): Long? {
        var s = text.trim()
            .replace("€", "").replace("EUR", "", ignoreCase = true)
            .replace(" ", "").replace(" ", "")
            .replace("−", "-").replace("–", "-")
        if (s.isEmpty()) return null
        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) {
            negative = true
            s = s.substring(1, s.length - 1)
        }
        if (s.endsWith("-")) {
            negative = !negative
            s = s.dropLast(1)
        }
        if (s.startsWith("-")) {
            negative = !negative
            s = s.drop(1)
        } else if (s.startsWith("+")) {
            s = s.drop(1)
        }
        if (s.isEmpty() || s.any { !it.isDigit() && it != ',' && it != '.' }) return null

        val lastComma = s.lastIndexOf(',')
        val lastDot = s.lastIndexOf('.')
        val decimalIndex = when {
            lastComma >= 0 && lastDot >= 0 -> maxOf(lastComma, lastDot)
            lastComma >= 0 -> if (isDecimalSeparator(s, lastComma, ',')) lastComma else -1
            lastDot >= 0 -> if (isDecimalSeparator(s, lastDot, '.')) lastDot else -1
            else -> -1
        }
        val wholePart: String
        val fractionPart: String
        if (decimalIndex >= 0) {
            wholePart = s.substring(0, decimalIndex).filter { it.isDigit() }
            fractionPart = s.substring(decimalIndex + 1)
        } else {
            wholePart = s.filter { it.isDigit() }
            fractionPart = ""
        }
        if (fractionPart.any { !it.isDigit() }) return null
        if (wholePart.isEmpty() && fractionPart.isEmpty()) return null
        if (wholePart.length > 15) return null
        val fraction = (fractionPart + "00").take(2)
        // Dritte Nachkommastelle kaufmännisch runden.
        val roundUp = fractionPart.length > 2 && fractionPart[2] >= '5'
        val cents = (wholePart.ifEmpty { "0" }).toLong() * 100 + fraction.toLong() + if (roundUp) 1 else 0
        return if (negative) -cents else cents
    }

    /** Ein einzelnes Trennzeichen ist ein Tausenderpunkt, wenn exakt drei Ziffern folgen und es mehrfach vorkommt oder Ziffern davorstehen. */
    private fun isDecimalSeparator(s: String, index: Int, separator: Char): Boolean {
        val occurrences = s.count { it == separator }
        if (occurrences > 1) return false
        val digitsAfter = s.length - index - 1
        // "1.234" lesen wir als Tausendertrennung, "1,234" ebenso; "12,5" und "0.99" als Dezimalzahl.
        if (s.substring(0, index) == "0") return true
        return !(digitsAfter == 3 && index in 1..3)
    }
}
