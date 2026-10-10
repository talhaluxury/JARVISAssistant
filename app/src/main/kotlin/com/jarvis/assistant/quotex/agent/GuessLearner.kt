package com.jarvis.assistant.quotex.agent

import com.jarvis.assistant.quotex.domain.Candle
import java.io.File
import kotlin.math.exp

/** Where the learner keeps its numbers between app starts. */
interface LearnerStore {
    fun load(): String?
    fun save(text: String)
}

class InMemoryLearnerStore : LearnerStore {
    private var text: String? = null
    override fun load(): String? = text
    override fun save(text: String) { this.text = text }
}

class FileLearnerStore(private val file: File) : LearnerStore {
    override fun load(): String? = try { if (file.exists()) file.readText() else null } catch (e: Exception) { null }
    override fun save(text: String) { try { file.writeText(text) } catch (e: Exception) { /* best effort */ } }
}

/** What the learner has measured about itself so far. Hit rates are in percent, null while the window is empty. */
data class LearnerStats(
    val samples: Int,
    val windowSize: Int,
    val learnerHitPct: Double?,
    val engineHitPct: Double?
)

/**
 * Online logistic regression over the quick-guess votes. It starts from the hand-made vote weights (a small prior)
 * and after every closed candle moves each signal's weight towards what actually happened, so signals that keep
 * being wrong on THIS asset and timeframe lose influence and useful ones gain it.
 *
 * Honest scoring: before a candle is learned from, the learner's call for it is compared with the real colour
 * (prequential, so it is always out-of-sample). [stats] shows that hit rate next to the plain vote engine's, so the
 * user can see whether learning helps at all. It may well not: short candles are close to random.
 */
class GuessLearner(private val store: LearnerStore = InMemoryLearnerStore()) {
    private val weights = HashMap<String, Double>()
    private var bias = 0.0
    private var samples = 0
    /** Newest candle already learned from, per asset and timeframe, so nothing is counted twice. */
    private val trainedUpTo = HashMap<String, Long>()
    private val learnerBits = ArrayDeque<Boolean>()
    private val engineBits = ArrayDeque<Boolean>()

    init { load() }

    @Synchronized
    fun predictUp(features: Map<String, Double>): Double {
        var z = bias
        for ((k, x) in features) z += (weights[k] ?: PRIOR) * x
        return 1.0 / (1.0 + exp(-z))
    }

    /** Learns from one closed candle. Candles at or before the last trained one (or flat ones) are ignored. */
    @Synchronized
    fun learn(features: Map<String, Double>, engineUp: Boolean, candleUp: Boolean, openMs: Long, scope: String) {
        if (openMs <= (trainedUpTo[scope] ?: -1L) || features.isEmpty()) return
        trainedUpTo[scope] = openMs
        val p = predictUp(features)
        push(learnerBits, (p >= 0.5) == candleUp)
        push(engineBits, engineUp == candleUp)
        val err = (if (candleUp) 1.0 else 0.0) - p
        for ((k, x) in features) {
            val w = weights[k] ?: PRIOR
            weights[k] = (w + LEARNING_RATE * err * x - DECAY * (w - PRIOR)).coerceIn(-MAX_W, MAX_W)
        }
        bias = (bias + BIAS_RATE * err).coerceIn(-MAX_BIAS, MAX_BIAS)
        samples++
    }

    /**
     * Replays stored candles through the vote engine and learns from each result, so a fresh install or a new asset
     * is not blank. Only candles newer than the last one already learned from are used, so nothing counts twice.
     */
    @Synchronized
    fun trainOnHistory(candles: List<Candle>, scope: String, maxCandles: Int = 800) {
        val start = maxOf(QuickGuessEngine.MIN_CANDLES, candles.size - maxCandles)
        for (i in start until candles.size) {
            val c = candles[i]
            if (c.openTimeMs <= (trainedUpTo[scope] ?: -1L) || c.close == c.open) continue
            val g = QuickGuessEngine.guess(candles.subList(0, i), c.openTimeMs) ?: continue
            learn(g.features, g.up, c.close > c.open, c.openTimeMs, scope)
        }
        save()
    }

    @Synchronized
    fun stats(): LearnerStats = LearnerStats(
        samples = samples,
        windowSize = learnerBits.size,
        learnerHitPct = pct(learnerBits),
        engineHitPct = pct(engineBits)
    )

    @Synchronized
    fun save() {
        val sb = StringBuilder()
        sb.appendLine("v1")
        for ((k, t) in trainedUpTo) sb.appendLine("t|$k|$t")
        sb.appendLine("samples=$samples")
        sb.appendLine("bias=$bias")
        sb.appendLine("learner=" + learnerBits.joinToString("") { if (it) "1" else "0" })
        sb.appendLine("engine=" + engineBits.joinToString("") { if (it) "1" else "0" })
        for ((k, w) in weights) sb.appendLine("w|$k|$w")
        store.save(sb.toString())
    }

    private fun load() {
        val text = store.load() ?: return
        try {
            val lines = text.lines()
            if (lines.firstOrNull() != "v1") return
            for (line in lines.drop(1)) {
                when {
                    line.startsWith("t|") -> {
                        val parts = line.split('|')
                        if (parts.size == 3) trainedUpTo[parts[1]] = parts[2].toLong()
                    }
                    line.startsWith("samples=") -> samples = line.substringAfter('=').toInt()
                    line.startsWith("bias=") -> bias = line.substringAfter('=').toDouble()
                    line.startsWith("learner=") -> line.substringAfter('=').forEach { learnerBits.addLast(it == '1') }
                    line.startsWith("engine=") -> line.substringAfter('=').forEach { engineBits.addLast(it == '1') }
                    line.startsWith("w|") -> {
                        val parts = line.split('|')
                        if (parts.size == 3) weights[parts[1]] = parts[2].toDouble()
                    }
                }
            }
        } catch (e: Exception) {
            weights.clear(); bias = 0.0; samples = 0; trainedUpTo.clear()
            learnerBits.clear(); engineBits.clear()
        }
    }

    private fun push(q: ArrayDeque<Boolean>, hit: Boolean) {
        q.addLast(hit)
        while (q.size > WINDOW) q.removeFirst()
    }

    private fun pct(q: ArrayDeque<Boolean>): Double? =
        if (q.isEmpty()) null else 100.0 * q.count { it } / q.size

    companion object {
        /** Starting weight of every signal: the hand-made votes, scaled so a score of 7 starts near 67% not 99%. */
        const val PRIOR = 0.1
        private const val LEARNING_RATE = 0.02
        private const val DECAY = 0.001
        private const val BIAS_RATE = 0.005
        private const val MAX_W = 1.5
        private const val MAX_BIAS = 0.5
        private const val WINDOW = 200
        /** Below this many learned candles the learner's opinion is only shown, never used to hold back an alert. */
        const val MIN_TRUSTED_SAMPLES = 100
    }
}
