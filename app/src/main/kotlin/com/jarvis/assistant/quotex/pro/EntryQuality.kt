package com.jarvis.assistant.quotex.pro

import com.jarvis.assistant.trading.QuotexDecision

enum class EntryQuality { EARLY, FORMING, CONFIRMED, OPTIMAL, LATE, INVALID }

data class EntryAssessment(val quality: EntryQuality, val extensionAtr: Double, val reason: String) {
    /** Only CONFIRMED and OPTIMAL entries may stay CALL/PUT. */
    val tradable: Boolean get() = quality == EntryQuality.CONFIRMED || quality == EntryQuality.OPTIMAL
}

/**
 * Timing of a setup, separate from its strength: a high setup score on a move that already ran several ATRs is LATE
 * and the final decision becomes WAIT.
 *
 * @param moveStartPrice where the move began (structure-break level or last swing)
 * @param invalidationPrice if price closes beyond this against the setup, the setup is dead (null = not defined)
 * @param confirmations / [requiredConfirmations] independent confirmations seen vs. needed
 */
object EntryQualityEvaluator {

    fun evaluate(
        callSide: Boolean,
        lastClose: Double,
        moveStartPrice: Double,
        invalidationPrice: Double?,
        atr: Double,
        confirmations: Int,
        requiredConfirmations: Int
    ): EntryAssessment {
        if (atr.isNaN() || atr <= 0.0 || lastClose.isNaN() || moveStartPrice.isNaN()) {
            return EntryAssessment(EntryQuality.INVALID, 0.0, "ATR or price unavailable")
        }
        val signed = if (callSide) lastClose - moveStartPrice else moveStartPrice - lastClose
        val ext = signed / atr

        if (invalidationPrice != null) {
            val broken = if (callSide) lastClose <= invalidationPrice else lastClose >= invalidationPrice
            if (broken) return EntryAssessment(EntryQuality.INVALID, ext, "price closed beyond the invalidation level")
        } else if (ext < -1.0) {
            return EntryAssessment(EntryQuality.INVALID, ext, "price moved more than 1 ATR against the setup")
        }

        val need = requiredConfirmations.coerceAtLeast(1)
        return when {
            confirmations * 2 < need -> EntryAssessment(EntryQuality.EARLY, ext, "only $confirmations/$need confirmations")
            confirmations < need -> EntryAssessment(EntryQuality.FORMING, ext, "$confirmations/$need confirmations")
            ext > 2.0 -> EntryAssessment(EntryQuality.LATE, ext, "move already extended %.1f ATR".format(ext))
            ext <= 1.0 -> EntryAssessment(EntryQuality.OPTIMAL, ext, "confirmed, only %.1f ATR from the move start".format(ext))
            else -> EntryAssessment(EntryQuality.CONFIRMED, ext, "confirmed, %.1f ATR from the move start".format(ext))
        }
    }

    /** A strong score never overrides bad timing. */
    fun gate(decision: QuotexDecision, assessment: EntryAssessment): QuotexDecision =
        if (decision == QuotexDecision.CALL || decision == QuotexDecision.PUT) {
            if (assessment.tradable) decision else QuotexDecision.WAIT
        } else decision
}
