package com.jarvis.assistant.demotrade

import com.jarvis.assistant.quotex.domain.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure indicator maths. Every function returns an array the same length as its input; positions where the indicator is
 * not defined yet hold NaN (we never invent a value for missing history). An indicator at index i only uses data <= i.
 *
 * Volume: Quotex shows no real traded volume on the chart, so the screen reader cannot read any. The only activity measure
 * JARVIS can observe is TICK ACTIVITY (how many price readings built a candle, see Candle.ticks). VWAP and average volume are
 * therefore computed on that tick-activity proxy and are labelled as such. Candles without a tick count (read from the chart
 * image or loaded from storage) get the median tick count of the window, so they neither dominate nor vanish.
 */
object TA {
    private fun nans(n: Int) = DoubleArray(n) { Double.NaN }

    fun sma(v: DoubleArray, p: Int): DoubleArray {
        val out = nans(v.size)
        if (p <= 0 || v.size < p) return out
        var sum = 0.0
        for (i in v.indices) {
            sum += v[i]
            if (i >= p) sum -= v[i - p]
            if (i >= p - 1) out[i] = sum / p
        }
        return out
    }

    fun ema(v: DoubleArray, p: Int): DoubleArray {
        val out = nans(v.size)
        var start = -1
        for (i in v.indices) { if (!v[i].isNaN()) { start = i; break } }
        if (start < 0 || p <= 0 || v.size - start < p) return out
        val k = 2.0 / (p + 1.0)
        var sum = 0.0
        for (i in start until start + p) sum += v[i]
        var prev = sum / p
        out[start + p - 1] = prev
        for (i in start + p until v.size) {
            prev = v[i] * k + prev * (1.0 - k)
            out[i] = prev
        }
        return out
    }

    private fun rsiValue(avgGain: Double, avgLoss: Double): Double =
        if (avgLoss == 0.0) (if (avgGain == 0.0) 50.0 else 100.0) else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)

    fun rsi(c: DoubleArray, p: Int = 14): DoubleArray {
        val out = nans(c.size)
        if (c.size <= p) return out
        var gain = 0.0
        var loss = 0.0
        for (i in 1..p) {
            val d = c[i] - c[i - 1]
            if (d >= 0.0) gain += d else loss -= d
        }
        var ag = gain / p
        var al = loss / p
        out[p] = rsiValue(ag, al)
        for (i in p + 1 until c.size) {
            val d = c[i] - c[i - 1]
            val g = if (d > 0.0) d else 0.0
            val l = if (d < 0.0) -d else 0.0
            ag = (ag * (p - 1) + g) / p
            al = (al * (p - 1) + l) / p
            out[i] = rsiValue(ag, al)
        }
        return out
    }

    class Macd(val line: DoubleArray, val signal: DoubleArray, val hist: DoubleArray)

    fun macd(c: DoubleArray, fast: Int = 12, slow: Int = 26, sig: Int = 9): Macd {
        val f = ema(c, fast)
        val s = ema(c, slow)
        val line = DoubleArray(c.size) { if (f[it].isNaN() || s[it].isNaN()) Double.NaN else f[it] - s[it] }
        val signal = ema(line, sig)
        val hist = DoubleArray(c.size) { if (line[it].isNaN() || signal[it].isNaN()) Double.NaN else line[it] - signal[it] }
        return Macd(line, signal, hist)
    }

    class Bands(val upper: DoubleArray, val mid: DoubleArray, val lower: DoubleArray)

    fun bollinger(c: DoubleArray, p: Int = 20, k: Double = 2.0): Bands {
        val mid = sma(c, p)
        val up = nans(c.size)
        val lo = nans(c.size)
        for (i in p - 1 until c.size) {
            if (mid[i].isNaN()) continue
            var sq = 0.0
            for (j in i - p + 1..i) { val d = c[j] - mid[i]; sq += d * d }
            val sd = sqrt(sq / p)
            up[i] = mid[i] + k * sd
            lo[i] = mid[i] - k * sd
        }
        return Bands(up, mid, lo)
    }

    fun trueRange(h: DoubleArray, l: DoubleArray, c: DoubleArray, i: Int): Double =
        if (i == 0) h[0] - l[0] else max(h[i] - l[i], max(abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])))

    fun atr(h: DoubleArray, l: DoubleArray, c: DoubleArray, p: Int = 14): DoubleArray {
        val out = nans(c.size)
        if (c.size <= p) return out
        var a = 0.0
        for (i in 1..p) a += trueRange(h, l, c, i)
        a /= p
        out[p] = a
        for (i in p + 1 until c.size) {
            a = (a * (p - 1) + trueRange(h, l, c, i)) / p
            out[i] = a
        }
        return out
    }

    class Adx(val adx: DoubleArray, val plusDi: DoubleArray, val minusDi: DoubleArray)

    fun adx(h: DoubleArray, l: DoubleArray, c: DoubleArray, p: Int = 14): Adx {
        val n = c.size
        val adxOut = nans(n)
        val pdiOut = nans(n)
        val mdiOut = nans(n)
        if (n <= 2 * p) return Adx(adxOut, pdiOut, mdiOut)
        var trS = 0.0
        var pS = 0.0
        var mS = 0.0
        for (i in 1..p) {
            val up = h[i] - h[i - 1]
            val dn = l[i - 1] - l[i]
            trS += trueRange(h, l, c, i)
            pS += if (up > dn && up > 0.0) up else 0.0
            mS += if (dn > up && dn > 0.0) dn else 0.0
        }
        val dx = nans(n)
        fun record(i: Int) {
            val pdi = if (trS == 0.0) 0.0 else 100.0 * pS / trS
            val mdi = if (trS == 0.0) 0.0 else 100.0 * mS / trS
            pdiOut[i] = pdi
            mdiOut[i] = mdi
            val sum = pdi + mdi
            dx[i] = if (sum == 0.0) 0.0 else 100.0 * abs(pdi - mdi) / sum
        }
        record(p)
        for (i in p + 1 until n) {
            val up = h[i] - h[i - 1]
            val dn = l[i - 1] - l[i]
            trS = trS - trS / p + trueRange(h, l, c, i)
            pS = pS - pS / p + (if (up > dn && up > 0.0) up else 0.0)
            mS = mS - mS / p + (if (dn > up && dn > 0.0) dn else 0.0)
            record(i)
        }
        var seed = 0.0
        for (i in p until 2 * p) seed += dx[i]
        var a = seed / p
        adxOut[2 * p - 1] = a
        for (i in 2 * p until n) {
            a = (a * (p - 1) + dx[i]) / p
            adxOut[i] = a
        }
        return Adx(adxOut, pdiOut, mdiOut)
    }

    class Stoch(val k: DoubleArray, val d: DoubleArray)

    fun stochastic(h: DoubleArray, l: DoubleArray, c: DoubleArray, kp: Int = 14, dp: Int = 3): Stoch {
        val n = c.size
        val k = nans(n)
        val d = nans(n)
        for (i in kp - 1 until n) {
            var hh = h[i]
            var ll = l[i]
            for (j in i - kp + 1..i) { hh = max(hh, h[j]); ll = min(ll, l[j]) }
            k[i] = if (hh - ll == 0.0) 50.0 else (c[i] - ll) / (hh - ll) * 100.0
        }
        for (i in kp - 1 + dp - 1 until n) {
            var sum = 0.0
            for (j in i - dp + 1..i) sum += k[j]
            d[i] = sum / dp
        }
        return Stoch(k, d)
    }

    fun momentum(v: DoubleArray, n: Int = 10): DoubleArray {
        val out = nans(v.size)
        for (i in n until v.size) out[i] = v[i] - v[i - n]
        return out
    }

    fun roc(v: DoubleArray, n: Int = 10): DoubleArray {
        val out = nans(v.size)
        for (i in n until v.size) if (v[i - n] != 0.0) out[i] = (v[i] / v[i - n] - 1.0) * 100.0
        return out
    }

    data class Swing(val index: Int, val price: Double, val isHigh: Boolean)

    /** A swing needs [k] candles on both sides, so the newest k candles can never be a (look-ahead) swing. */
    fun swings(h: DoubleArray, l: DoubleArray, k: Int = 3): List<Swing> {
        val out = ArrayList<Swing>()
        for (i in k until h.size - k) {
            var isHigh = true
            var isLow = true
            for (j in i - k..i + k) {
                if (j == i) continue
                if (h[j] >= h[i]) isHigh = false
                if (l[j] <= l[i]) isLow = false
            }
            if (isHigh) out.add(Swing(i, h[i], true))
            if (isLow) out.add(Swing(i, l[i], false))
        }
        return out
    }

    data class Level(val price: Double, val touches: Int)

    /** Group swing prices that lie within [tolerance] of each other into support/resistance levels. */
    fun levels(swings: List<Swing>, tolerance: Double): List<Level> {
        if (swings.isEmpty() || tolerance <= 0.0) return emptyList()
        val sorted = swings.map { it.price }.sorted()
        val out = ArrayList<Level>()
        var group = ArrayList<Double>()
        group.add(sorted[0])
        for (i in 1 until sorted.size) {
            if (sorted[i] - group.last() <= tolerance) {
                group.add(sorted[i])
            } else {
                out.add(Level(group.average(), group.size))
                group = ArrayList()
                group.add(sorted[i])
            }
        }
        out.add(Level(group.average(), group.size))
        return out
    }

    /**
     * Per-candle weights for volume-style maths: the measured tick count, or the window's median tick count when a candle has
     * none, or 1.0 for every candle when no candle in the window has a tick count at all.
     */
    fun activityWeights(candles: List<Candle>): DoubleArray {
        val known = candles.filter { it.ticks > 0 }.map { it.ticks.toDouble() }
        if (known.isEmpty()) return DoubleArray(candles.size) { 1.0 }
        val fill = median(known)
        return DoubleArray(candles.size) { if (candles[it].ticks > 0) candles[it].ticks.toDouble() else fill }
    }

    /** Rolling VWAP over the last [period] candles using typical price (H+L+C)/3 and [weights]. */
    fun vwap(h: DoubleArray, l: DoubleArray, c: DoubleArray, weights: DoubleArray, period: Int = 60): DoubleArray {
        val n = c.size
        val out = nans(n)
        if (period <= 0 || n < period || weights.size != n) return out
        for (i in period - 1 until n) {
            var pv = 0.0
            var v = 0.0
            for (j in i - period + 1..i) {
                val typical = (h[j] + l[j] + c[j]) / 3.0
                pv += typical * weights[j]
                v += weights[j]
            }
            if (v > 0.0) out[i] = pv / v
        }
        return out
    }

    /** Weight of each candle divided by the average weight of the [period] candles before it. */
    fun activityRatio(weights: DoubleArray, period: Int = 20): DoubleArray {
        val n = weights.size
        val out = nans(n)
        for (i in period until n) {
            var sum = 0.0
            for (j in i - period until i) sum += weights[j]
            val avg = sum / period
            if (avg > 0.0) out[i] = weights[i] / avg
        }
        return out
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2.0
    }
}

/** All indicators for one candle window, computed once and shared by regime detection, patterns, strategies and scoring. */
class IndicatorSet(val candles: List<Candle>) {
    val n: Int = candles.size
    val open = DoubleArray(n) { candles[it].open }
    val high = DoubleArray(n) { candles[it].high }
    val low = DoubleArray(n) { candles[it].low }
    val close = DoubleArray(n) { candles[it].close }

    val ema9 = TA.ema(close, 9)
    val ema21 = TA.ema(close, 21)
    val ema50 = TA.ema(close, 50)
    val ema200 = TA.ema(close, 200)
    val sma20 = TA.sma(close, 20)
    val rsi14 = TA.rsi(close, 14)
    val macd = TA.macd(close)
    val bb = TA.bollinger(close)
    val atr14 = TA.atr(high, low, close, 14)
    val adx14 = TA.adx(high, low, close, 14)
    val stoch = TA.stochastic(high, low, close, 14, 3)
    val mom10 = TA.momentum(close, 10)
    val roc10 = TA.roc(close, 10)

    /** True when at least 20 candles of the window carry a measured tick count (otherwise activity maths is not trusted). */
    val hasActivityData: Boolean = candles.count { it.ticks > 0 } >= 20
    val activityWeights: DoubleArray = TA.activityWeights(candles)
    val vwap: DoubleArray = TA.vwap(high, low, close, activityWeights, 60)
    val activityRatio: DoubleArray = TA.activityRatio(activityWeights, 20)

    val swings: List<TA.Swing> by lazy { TA.swings(high, low, 3) }

    fun at(a: DoubleArray, back: Int = 0): Double {
        val i = n - 1 - back
        return if (i >= 0 && i < a.size) a[i] else Double.NaN
    }

    val price: Double get() = close[n - 1]
    val atrNow: Double get() = at(atr14)

    /** Median ATR of the last [lookback] candles: the "normal" volatility the current ATR is compared with. */
    fun atrBaseline(lookback: Int = 50): Double {
        val vals = ArrayList<Double>()
        for (i in max(0, n - lookback) until n) if (!atr14[i].isNaN()) vals.add(atr14[i])
        return TA.median(vals)
    }

    fun atrRatio(): Double {
        val base = atrBaseline()
        val now = atrNow
        return if (base.isNaN() || base <= 0.0 || now.isNaN()) Double.NaN else now / base
    }

    /** Tick-activity of the newest candle vs the previous 20 (NaN when the window has no measured tick counts). */
    fun activityRatioNow(): Double = if (hasActivityData) at(activityRatio) else Double.NaN

    fun levels(): List<TA.Level> = TA.levels(swings, if (atrNow.isNaN()) 0.0 else atrNow * 0.3)

    /** Values at the last candle for the journal and for the AI prompt. NaN (unavailable) entries are left out. */
    fun snapshot(): Map<String, Double> {
        val m = LinkedHashMap<String, Double>()
        fun put(name: String, v: Double) { if (!v.isNaN() && !v.isInfinite()) m[name] = v }
        put("price", price)
        put("ema9", at(ema9)); put("ema21", at(ema21)); put("ema50", at(ema50)); put("ema200", at(ema200))
        put("sma20", at(sma20)); put("rsi14", at(rsi14))
        put("macd", at(macd.line)); put("macd_signal", at(macd.signal)); put("macd_hist", at(macd.hist))
        put("bb_upper", at(bb.upper)); put("bb_lower", at(bb.lower))
        put("atr14", atrNow); put("adx14", at(adx14.adx))
        put("plus_di", at(adx14.plusDi)); put("minus_di", at(adx14.minusDi))
        put("stoch_k", at(stoch.k)); put("stoch_d", at(stoch.d))
        put("momentum10", at(mom10)); put("roc10_pct", at(roc10))
        put("vwap", at(vwap))
        put("activity_ratio", activityRatioNow())
        return m
    }
}
