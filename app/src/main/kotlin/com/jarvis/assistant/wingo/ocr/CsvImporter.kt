package com.jarvis.assistant.wingo.ocr

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.PeriodFormat
import com.jarvis.assistant.wingo.domain.RoundResult

data class ImportResult(
    val results: List<RoundResult>,
    val rejectedLines: List<String>,
    val duplicates: Int
)

/** Backup format: identical to what [CsvImporter] reads, so an export can always be restored. */
object CsvExporter {
    fun toCsv(results: List<RoundResult>): String = buildString {
        appendLine("Period,Number,BigSmall,Color")
        for (r in results) appendLine("${r.period},${r.number},${r.bigSmall.name},${r.color}")
    }
}

/** Offline TEST MODE import: `Period,Number,BigSmall,Color` (BigSmall and Color optional). */
object CsvImporter {

    fun parse(text: String, periodLength: Int = PeriodFormat.DEFAULT_LENGTH, now: Long = System.currentTimeMillis()): ImportResult {
        val accepted = LinkedHashMap<String, RoundResult>()
        val rejected = ArrayList<String>()
        var duplicates = 0
        val splitter = Regex("[,;\\t]")

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val tokens = line.split(splitter).map { it.trim() }
            val first = tokens[0]
            if (first.isEmpty() || !first[0].isDigit()) {
                // Header (e.g. "Period,Number,BigSmall,Color") or junk.
                if (!first.lowercase().startsWith("period")) rejected.add(line)
                continue
            }
            val number = tokens.getOrNull(1)?.toIntOrNull()
            val labelText = tokens.getOrNull(2)
            val label = if (labelText.isNullOrBlank()) null else BigSmall.parse(labelText)
            if (!labelText.isNullOrBlank() && label == null) {
                rejected.add(line)
                continue
            }
            val error = ResultValidator.fieldError(first, number, label, tokens.getOrNull(3), false, periodLength)
            if (error != null || number == null) {
                rejected.add(line)
                continue
            }
            val existing = accepted[first]
            if (existing != null) {
                duplicates++
                continue
            }
            accepted[first] = RoundResult(first, number, now)
        }
        return ImportResult(accepted.values.sortedBy { it.period }, rejected, duplicates)
    }
}
