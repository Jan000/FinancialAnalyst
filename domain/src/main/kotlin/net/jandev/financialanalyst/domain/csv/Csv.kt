package net.jandev.financialanalyst.domain.csv

/** Minimaler CSV-Leser: Anführungszeichen, verdoppelte Anführungszeichen, Zeilenumbrüche in Feldern. */
object Csv {

    /** Wählt das Trennzeichen, das in den ersten Zeilen am häufigsten außerhalb von Anführungszeichen vorkommt. */
    fun detectDelimiter(text: String): Char {
        val candidates = charArrayOf(';', ',', '\t', '|')
        val counts = IntArray(candidates.size)
        var inQuotes = false
        var lines = 0
        for (c in text) {
            if (c == '"') inQuotes = !inQuotes
            if (inQuotes) continue
            if (c == '\n') {
                lines++
                if (lines >= 30) break
            }
            val index = candidates.indexOf(c)
            if (index >= 0) counts[index]++
        }
        var best = 0
        for (i in candidates.indices) if (counts[i] > counts[best]) best = i
        return candidates[best]
    }

    fun parse(text: String, delimiter: Char = detectDelimiter(text)): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        val input = text.removePrefix("﻿")
        while (i < input.length) {
            val c = input[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < input.length && input[i + 1] == '"' -> {
                        field.append('"')
                        i++
                    }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == delimiter -> {
                    row.add(field.toString().trim())
                    field.setLength(0)
                }
                c == '\n' || c == '\r' -> {
                    if (c == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                    row.add(field.toString().trim())
                    field.setLength(0)
                    rows.add(row)
                    row = ArrayList()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString().trim())
            rows.add(row)
        }
        return rows
    }
}
