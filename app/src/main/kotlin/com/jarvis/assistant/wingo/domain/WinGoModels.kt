package com.jarvis.assistant.wingo.domain

import java.time.LocalDate
import java.util.Locale

/** WinGo Big/Small rule: 0-4 = SMALL, 5-9 = BIG. */
enum class BigSmall {
    BIG, SMALL;

    val letter: Char get() = if (this == BIG) 'B' else 'S'
    val opposite: BigSmall get() = if (this == BIG) SMALL else BIG

    companion object {
        fun fromNumber(number: Int): BigSmall {
            require(number in 0..9) { "WinGo numbers are 0-9, got $number" }
            return if (number >= 5) BIG else SMALL
        }

        fun parse(text: String?): BigSmall? = when (text?.trim()?.uppercase()) {
            "BIG", "B" -> BIG
            "SMALL", "S" -> SMALL
            else -> null
        }
    }
}

/** Deterministic WinGo colour rule (0 = red+violet, 5 = green+violet, odd = green, even = red). */
object WinGoColors {
    fun forNumber(number: Int): String = when (number) {
        0 -> "RED+VIOLET"
        5 -> "GREEN+VIOLET"
        1, 3, 7, 9 -> "GREEN"
        2, 4, 6, 8 -> "RED"
        else -> throw IllegalArgumentException("WinGo numbers are 0-9, got $number")
    }

    /** Splits "Red + Violet" / "GREEN/PURPLE" style text into a normalised set of colour words. */
    fun parts(text: String): Set<String> =
        text.uppercase(Locale.ROOT)
            .split('+', '/', ',', '&', ' ')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { if (it == "PURPLE") "VIOLET" else it }
            .toSet()
}

/** One finished round that has passed validation. */
data class RoundResult(
    val period: String,
    val number: Int,
    val timestamp: Long,
    val sourceConfidence: Float = 1f
) {
    init {
        require(number in 0..9) { "WinGo numbers are 0-9, got $number" }
    }

    val bigSmall: BigSmall get() = BigSmall.fromNumber(number)
    val color: String get() = WinGoColors.forNumber(number)
}

object PeriodFormat {
    const val DEFAULT_LENGTH = 17

    /** yyyyMMdd + 5-digit game code + 4-digit sequence, e.g. 20260920100051299. */
    fun isValid(period: String, length: Int = DEFAULT_LENGTH): Boolean {
        if (period.length != length) return false
        if (!period.all { it in '0'..'9' }) return false
        return try {
            val year = period.substring(0, 4).toInt()
            val month = period.substring(4, 6).toInt()
            val day = period.substring(6, 8).toInt()
            LocalDate.of(year, month, day)
            year in 2020..2100
        } catch (e: Exception) {
            false
        }
    }

    /** The following period (sequence + 1). Returns null when [period] is not numeric. */
    fun next(period: String): String? {
        val value = period.toLongOrNull() ?: return null
        return (value + 1).toString().padStart(period.length, '0')
    }

    /** yyyyMMdd prefix, used to avoid counting a day rollover as a data gap. */
    fun dayOf(period: String): String = period.take(8)
}

enum class ConfidenceLevel { VERY_LOW, LOW, MEDIUM, HIGH }

/** What the analyzer tells the user. WAIT means "no usable signal"; it is never a bet instruction. */
enum class Signal {
    WAIT, LOW, MEDIUM, HIGH;

    companion object {
        fun from(level: ConfidenceLevel): Signal = when (level) {
            ConfidenceLevel.VERY_LOW -> WAIT
            ConfidenceLevel.LOW -> LOW
            ConfidenceLevel.MEDIUM -> MEDIUM
            ConfidenceLevel.HIGH -> HIGH
        }
    }
}

/**
 * All tunables in one place. Confidence thresholds are configurable as required, but the
 * defaults (55 / 60 / 70) are only labels for how lopsided the models' estimates are -
 * they are NOT a promise of accuracy. [requireVerifiedEdge] keeps signals at WAIT until the
 * walk-forward record shows a statistically significant edge over a coin flip.
 */
data class WinGoConfig(
    val lowThreshold: Double = 0.55,
    val mediumThreshold: Double = 0.60,
    val highThreshold: Double = 0.70,
    val minHistoryForSignal: Int = 100,
    val modelHistoryCap: Int = 2000,
    val requireVerifiedEdge: Boolean = true,
    val edgeMinSamples: Int = 200,
    val edgeZThreshold: Double = 2.33,
    val edgeLookback: Int = 1000,
    val minWeightedAgreement: Double = 0.5,
    val periodLength: Int = PeriodFormat.DEFAULT_LENGTH,
    val minOcrConfidence: Float = 0.70f,
    val requireBigSmallLabel: Boolean = true,
    val confirmations: Int = 2,
    val sampleIntervalMs: Long = 1000L,
    val searchIntervalMs: Long = 3000L,
    val forcedRefreshMs: Long = 20_000L,
    val missesBeforePause: Int = 4,
    /**
     * While monitoring runs continuously (the previous verified round was very recent), the live period
     * should advance by only a few numbers each round. A much bigger jump almost always means one digit of
     * the period was misread (the coloured 0/5 digits are the hardest for OCR) rather than a real skip, so
     * it is treated as an uncertain reading instead of being trusted.
     */
    val maxPlausibleLiveJump: Long = 40L,
    /** How recent the previous verified round must be for the jump check above to apply. */
    val liveJumpContinuityMs: Long = 10 * 60_000L
) {
    init {
        require(lowThreshold in 0.5..1.0) { "lowThreshold must be within 0.5..1.0" }
        require(mediumThreshold > lowThreshold) { "mediumThreshold must be above lowThreshold" }
        require(highThreshold > mediumThreshold && highThreshold <= 1.0) { "highThreshold must be above mediumThreshold" }
    }

    fun levelFor(confidence: Double): ConfidenceLevel = when {
        confidence >= highThreshold -> ConfidenceLevel.HIGH
        confidence >= mediumThreshold -> ConfidenceLevel.MEDIUM
        confidence >= lowThreshold -> ConfidenceLevel.LOW
        else -> ConfidenceLevel.VERY_LOW
    }
}

/** Screen area (as fractions of the frame) that holds the game-history table. */
data class NormalizedRegion(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom) {
            "Region must satisfy 0 <= left < right <= 1 and 0 <= top < bottom <= 1"
        }
    }
}

object Fmt {
    fun pct(fraction: Double, digits: Int = 0): String = String.format(Locale.US, "%.${digits}f%%", fraction * 100.0)
    fun num(value: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", value)
}


/** A run of consecutive periods that are missing between two stored rounds of the same day. */
data class PeriodGap(val firstMissing: String, val lastMissing: String, val count: Int)

object PeriodGaps {
    /** Gaps between neighbouring stored periods (ascending input). Different days are never compared. */
    fun find(periodsAscending: List<String>): List<PeriodGap> {
        val out = ArrayList<PeriodGap>()
        for (i in 1 until periodsAscending.size) {
            val a = periodsAscending[i - 1]
            val b = periodsAscending[i]
            if (a.length != b.length || PeriodFormat.dayOf(a) != PeriodFormat.dayOf(b)) continue
            val av = a.toLongOrNull() ?: continue
            val bv = b.toLongOrNull() ?: continue
            val missing = bv - av - 1
            if (missing < 1) continue
            out.add(
                PeriodGap(
                    (av + 1).toString().padStart(a.length, '0'),
                    (bv - 1).toString().padStart(a.length, '0'),
                    missing.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                )
            )
        }
        return out
    }

    /**
     * Roughly which history page shows [gapLast], if [newest] is the top row of page 1. The game adds a
     * row every round, so pages shift by one row each round - treat the answer as "around page N".
     */
    fun pageHint(newest: String, gapLast: String, rowsPerPage: Int = 10): Int? {
        val n = newest.toLongOrNull() ?: return null
        val g = gapLast.toLongOrNull() ?: return null
        if (g > n || rowsPerPage <= 0) return null
        return ((n - g) / rowsPerPage).toInt() + 1
    }
}
