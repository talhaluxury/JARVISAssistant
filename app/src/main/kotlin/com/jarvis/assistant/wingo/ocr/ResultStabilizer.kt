package com.jarvis.assistant.wingo.ocr

import com.jarvis.assistant.wingo.domain.RoundResult

/**
 * A result must be read identically in [required] separate samples before it is confirmed. This
 * catches one-off misreads caused by animations or partially drawn frames.
 */
class ResultStabilizer(private val required: Int = 2, private val maxTracked: Int = 200) {

    private class Seen(var number: Int, var count: Int, var confirmed: Boolean, var lastSeen: Long)

    private val seen = LinkedHashMap<String, Seen>()
    private var offers = 0L

    /** Returns only the results that just became confirmed, in the order they were offered. */
    fun offer(results: List<RoundResult>): List<RoundResult> {
        offers++
        val confirmedNow = ArrayList<RoundResult>()
        for (r in results) {
            val entry = seen[r.period]
            if (entry == null) {
                val confirmed = required <= 1
                seen[r.period] = Seen(r.number, 1, confirmed, offers)
                if (confirmed) confirmedNow.add(r)
            } else if (entry.confirmed) {
                entry.lastSeen = offers
            } else if (entry.number == r.number) {
                entry.count++
                entry.lastSeen = offers
                if (entry.count >= required) {
                    entry.confirmed = true
                    confirmedNow.add(r)
                }
            } else {
                // Different reading of the same period: start over with the new one.
                entry.number = r.number
                entry.count = 1
                entry.lastSeen = offers
            }
        }
        val stale = seen.filter { !it.value.confirmed && offers - it.value.lastSeen >= 3 }.keys
        for (key in stale) seen.remove(key)
        while (seen.size > maxTracked) {
            val oldest = seen.keys.first()
            seen.remove(oldest)
        }
        return confirmedNow
    }

    fun hasPending(): Boolean = seen.values.any { !it.confirmed }

    fun clear() {
        seen.clear()
    }
}
