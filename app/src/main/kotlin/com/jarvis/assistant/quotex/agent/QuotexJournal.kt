package com.jarvis.assistant.quotex.agent

import java.io.File

enum class JournalOutcome { PENDING, WIN, LOSS, DRAW, INVALIDATED }

/** Section 27: everything needed to reproduce and later review one setup. */
data class JournalEntry(
    val id: String,
    val timestampMs: Long,
    val asset: String,
    val timeframeSeconds: Int,
    val regime: String,
    val strategies: List<String>,
    val direction: String,
    val quality: String,
    val confidenceBucket: String,
    val conditionsMet: Int,
    val conditionsTotal: Int,
    val featuresSummary: String,
    val entryPrice: Double,
    val expiryMs: Long,
    val strategyVersion: String,
    val analysisVersion: String,
    val indicatorSettings: String,
    val session: String,
    val reasonText: String,
    val outcome: JournalOutcome = JournalOutcome.PENDING,
    val exitPrice: Double? = null,
    val screenshotRef: String? = null
)

data class JournalSummary(val total: Int, val wins: Int, val losses: Int, val draws: Int, val pending: Int) {
    val resolved: Int get() = wins + losses
    val hitRate: Double? get() = if (resolved == 0) null else wins.toDouble() / resolved
}

interface JournalStore {
    fun loadAll(): List<JournalEntry>
    fun saveAll(entries: List<JournalEntry>)
}

class InMemoryJournalStore : JournalStore {
    private var data: List<JournalEntry> = emptyList()
    override fun loadAll(): List<JournalEntry> = data
    override fun saveAll(entries: List<JournalEntry>) { data = entries.toList() }
}

/** One entry per line, tab-separated, with tabs/newlines/backslashes escaped. Plain text so it survives app upgrades. */
class FileJournalStore(private val file: File) : JournalStore {
    override fun loadAll(): List<JournalEntry> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull { JournalCodec.decode(it) }
    }

    override fun saveAll(entries: List<JournalEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(entries.joinToString("\n") { JournalCodec.encode(it) })
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }
}

object JournalCodec {
    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "")

    private fun unesc(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    else -> sb.append(s[i + 1])
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    fun encode(e: JournalEntry): String = listOf(
        e.id, e.timestampMs.toString(), e.asset, e.timeframeSeconds.toString(), e.regime, e.strategies.joinToString("|"),
        e.direction, e.quality, e.confidenceBucket, e.conditionsMet.toString(), e.conditionsTotal.toString(),
        e.featuresSummary, e.entryPrice.toString(), e.expiryMs.toString(), e.strategyVersion, e.analysisVersion,
        e.indicatorSettings, e.session, e.reasonText, e.outcome.name, e.exitPrice?.toString() ?: "", e.screenshotRef ?: ""
    ).joinToString("\t") { esc(it) }

    fun decode(line: String): JournalEntry? {
        if (line.isBlank()) return null
        // Split on tabs that are not part of an escape sequence: escaped tabs are written as backslash-t, never a raw tab.
        val f = line.split("\t").map { unesc(it) }
        if (f.size < 22) return null
        return try {
            JournalEntry(
                id = f[0], timestampMs = f[1].toLong(), asset = f[2], timeframeSeconds = f[3].toInt(), regime = f[4],
                strategies = if (f[5].isEmpty()) emptyList() else f[5].split("|"), direction = f[6], quality = f[7],
                confidenceBucket = f[8], conditionsMet = f[9].toInt(), conditionsTotal = f[10].toInt(),
                featuresSummary = f[11], entryPrice = f[12].toDouble(), expiryMs = f[13].toLong(),
                strategyVersion = f[14], analysisVersion = f[15], indicatorSettings = f[16], session = f[17],
                reasonText = f[18], outcome = JournalOutcome.valueOf(f[19]),
                exitPrice = f[20].toDoubleOrNull(), screenshotRef = f[21].ifEmpty { null }
            )
        } catch (ex: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Append-only in spirit (section 35): outcomes are written once, when a setup resolves. Nothing here
 * rewrites a resolved entry, so past results cannot be silently improved.
 */
class QuotexJournal(private val store: JournalStore = InMemoryJournalStore(), private val maxEntries: Int = 5000) {
    private val entries = ArrayList<JournalEntry>(store.loadAll())

    @Synchronized
    fun record(entry: JournalEntry) {
        if (entries.any { it.id == entry.id }) return
        entries.add(entry)
        while (entries.size > maxEntries) entries.removeAt(0)
        store.saveAll(entries)
    }

    /** Sets the outcome of a PENDING entry. A resolved entry is never changed again. Returns false if nothing changed. */
    @Synchronized
    fun resolve(id: String, outcome: JournalOutcome, exitPrice: Double?): Boolean {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0 || entries[idx].outcome != JournalOutcome.PENDING) return false
        entries[idx] = entries[idx].copy(outcome = outcome, exitPrice = exitPrice)
        store.saveAll(entries)
        return true
    }

    @Synchronized
    fun last(n: Int): List<JournalEntry> = entries.takeLast(n).reversed()

    @Synchronized
    fun pending(): List<JournalEntry> = entries.filter { it.outcome == JournalOutcome.PENDING }

    @Synchronized
    fun since(startMs: Long): List<JournalEntry> = entries.filter { it.timestampMs >= startMs }

    @Synchronized
    fun summary(list: List<JournalEntry> = entries.toList()): JournalSummary = JournalSummary(
        total = list.size,
        wins = list.count { it.outcome == JournalOutcome.WIN },
        losses = list.count { it.outcome == JournalOutcome.LOSS },
        draws = list.count { it.outcome == JournalOutcome.DRAW },
        pending = list.count { it.outcome == JournalOutcome.PENDING }
    )

    @Synchronized
    fun find(id: String): JournalEntry? = entries.firstOrNull { it.id == id }

    /** Plain-language account of a finished setup, built only from what was stored when it was raised. */
    fun whyFailed(entry: JournalEntry): String {
        if (entry.outcome == JournalOutcome.PENDING) return "This setup has not resolved yet."
        if (entry.outcome == JournalOutcome.WIN) return "This setup won; there is nothing to explain as a failure."
        val missing = entry.conditionsTotal - entry.conditionsMet
        return buildString {
            append("${entry.direction} setup on ${entry.asset} (${entry.regime}, ${entry.session}) ended ${entry.outcome.name}. ")
            append("${entry.conditionsMet} of ${entry.conditionsTotal} conditions were met")
            if (missing > 0) append(", so $missing were not")
            append(". Strategies: ${entry.strategies.joinToString(", ")}. ")
            append("Features at entry: ${entry.featuresSummary}. ")
            append("A single loss does not by itself show the strategy is broken; check it against its record across many setups.")
        }
    }
}
