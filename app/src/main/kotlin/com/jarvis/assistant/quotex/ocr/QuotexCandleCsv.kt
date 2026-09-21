package com.jarvis.assistant.quotex.ocr

import com.jarvis.assistant.quotex.domain.Candle

data class CandleImport(
    val byAsset: Map<String, List<Candle>>,
    val rejectedLines: List<String>,
    val duplicates: Int
) {
    val total: Int get() = byAsset.values.sumOf { it.size }
}

/** Backup / test-mode format: `Asset,OpenTimeMs,Open,High,Low,Close` (Asset optional -> 5 columns). */
object QuotexCandleCsv {
    const val HEADER = "Asset,OpenTimeMs,Open,High,Low,Close"

    fun toCsv(byAsset: Map<String, List<Candle>>): String = buildString {
        appendLine(HEADER)
        for ((asset, candles) in byAsset) {
            for (c in candles) appendLine("$asset,${c.openTimeMs},${c.open},${c.high},${c.low},${c.close}")
        }
    }

    fun parse(text: String, defaultAsset: String = "IMPORTED"): CandleImport {
        val accepted = LinkedHashMap<String, LinkedHashMap<Long, Candle>>()
        val rejected = ArrayList<String>()
        var duplicates = 0
        val splitter = Regex("[,;\\t]")
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val t = line.split(splitter).map { it.trim() }
            val hasAsset = t.size >= 6
            val first = t[0].lowercase()
            if (first == "asset" || first == "opentimems" || first == "time") continue
            val offset = if (hasAsset) 1 else 0
            if (t.size < offset + 5) {
                rejected.add(line)
                continue
            }
            val asset = if (hasAsset) t[0].uppercase() else defaultAsset
            val time = t[offset].toLongOrNull()
            val o = t[offset + 1].toDoubleOrNull()
            val h = t[offset + 2].toDoubleOrNull()
            val l = t[offset + 3].toDoubleOrNull()
            val c = t[offset + 4].toDoubleOrNull()
            if (time == null || o == null || h == null || l == null || c == null || !valid(o, h, l, c)) {
                rejected.add(line)
                continue
            }
            val map = accepted.getOrPut(asset) { LinkedHashMap() }
            if (map.containsKey(time)) {
                duplicates++
                continue
            }
            map[time] = Candle(time, o, h, l, c)
        }
        val result = LinkedHashMap<String, List<Candle>>()
        for ((asset, map) in accepted) result[asset] = map.values.sortedBy { it.openTimeMs }
        return CandleImport(result, rejected, duplicates)
    }

    private fun valid(o: Double, h: Double, l: Double, c: Double): Boolean =
        o > 0 && h > 0 && l > 0 && c > 0 && h >= maxOf(o, c) && l <= minOf(o, c)
}
