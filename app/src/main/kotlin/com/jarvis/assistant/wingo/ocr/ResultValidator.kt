package com.jarvis.assistant.wingo.ocr

import com.jarvis.assistant.wingo.domain.BigSmall
import com.jarvis.assistant.wingo.domain.PeriodFormat
import com.jarvis.assistant.wingo.domain.RoundResult
import com.jarvis.assistant.wingo.domain.WinGoColors
import com.jarvis.assistant.wingo.domain.WinGoConfig
import kotlin.math.abs

/**
 * OCR is never trusted blindly. A row only becomes a [RoundResult] if every check passes:
 * valid period format, number 0-9, Big/Small label agreeing with the number, colour (if read)
 * consistent with the number, sufficient OCR confidence and a consistent period sequence.
 */
class ResultValidator(private val config: WinGoConfig = WinGoConfig()) {

    fun validateRow(row: ParsedRow, timestamp: Long): RowVerdict {
        if (row.ambiguous) return uncertain(row, "Ambiguous reading (several candidate digits or labels)")
        val error = fieldError(row.period, row.number, row.bigSmall, row.color, config.requireBigSmallLabel, config.periodLength)
        if (error != null) {
            val isMissingLabel = error == MISSING_LABEL
            return if (isMissingLabel) uncertain(row, error) else RowVerdict(row, RowStatus.REJECTED, error, null)
        }
        if (row.confidence < config.minOcrConfidence) {
            return uncertain(row, "Low OCR confidence (${row.confidence})")
        }
        val period = row.period ?: return RowVerdict(row, RowStatus.REJECTED, "Missing period", null)
        val number = row.number ?: return RowVerdict(row, RowStatus.REJECTED, "Missing number", null)
        val result = RoundResult(period, number, timestamp, row.confidence)
        return RowVerdict(row, RowStatus.TRUSTED, null, result)
    }

    /**
     * Validates all rows of one frame. Duplicate periods are dropped; conflicting duplicates become
     * uncertain; rows whose period does not chain (+/-1) to any neighbouring row are uncertain.
     */
    fun validateBatch(rows: List<ParsedRow>, timestamp: Long): List<RowVerdict> {
        val verdicts = rows.map { validateRow(it, timestamp) }.toMutableList()

        // 1) duplicate periods
        val byPeriod = HashMap<String, MutableList<Int>>()
        verdicts.forEachIndexed { index, v ->
            val r = v.result
            if (v.status == RowStatus.TRUSTED && r != null) byPeriod.getOrPut(r.period) { mutableListOf() }.add(index)
        }
        for ((_, indexes) in byPeriod) {
            if (indexes.size < 2) continue
            val numbers = indexes.mapNotNull { verdicts[it].result?.number }.toSet()
            if (numbers.size > 1) {
                for (i in indexes) verdicts[i] = uncertain(verdicts[i].row, "Conflicting readings for the same period")
            } else {
                for (i in indexes.drop(1)) verdicts[i] = RowVerdict(verdicts[i].row, RowStatus.REJECTED, "Duplicate period ignored", null)
            }
        }

        // 2) sequence chain: a row must sit next to a row whose period differs by exactly 1
        val trustedIndexes = verdicts.indices.filter { verdicts[it].status == RowStatus.TRUSTED }
        if (trustedIndexes.size >= 2) {
            val periods = trustedIndexes.map { verdicts[it].result!!.period.toLong() }
            for ((position, index) in trustedIndexes.withIndex()) {
                val here = periods[position]
                val prevOk = position > 0 && abs(here - periods[position - 1]) == 1L
                val nextOk = position < periods.size - 1 && abs(here - periods[position + 1]) == 1L
                if (!prevOk && !nextOk) verdicts[index] = uncertain(verdicts[index].row, "Period sequence break")
            }
        }
        return verdicts
    }

    private fun uncertain(row: ParsedRow, reason: String) = RowVerdict(row, RowStatus.UNCERTAIN, reason, null)

    companion object {
        const val MISSING_LABEL = "Big/Small label not readable"

        /** Shared field rules for OCR rows and CSV imports. Returns an error message, or null when valid. */
        fun fieldError(
            period: String?,
            number: Int?,
            label: BigSmall?,
            colorText: String?,
            requireLabel: Boolean,
            periodLength: Int
        ): String? {
            if (period == null || !PeriodFormat.isValid(period, periodLength)) return "Invalid period format"
            if (number == null || number !in 0..9) return "Number must be 0-9"
            if (label == null) {
                if (requireLabel) return MISSING_LABEL
            } else if (label != BigSmall.fromNumber(number)) {
                return "Big/Small label disagrees with number"
            }
            if (!colorText.isNullOrBlank()) {
                val read = WinGoColors.parts(colorText)
                val expected = WinGoColors.parts(WinGoColors.forNumber(number))
                if (!expected.containsAll(read)) return "Colour disagrees with number"
            }
            return null
        }
    }
}
