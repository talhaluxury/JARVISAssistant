package com.jarvis.assistant.core.observability

import java.util.concurrent.CopyOnWriteArrayList

class AuditLog(private val maxEntries: Int = 500) {
    data class Entry(val time: Long, val category: String, val message: String, val success: Boolean)
    private val entries = CopyOnWriteArrayList<Entry>()
    fun record(category: String, message: String, success: Boolean = true) {
        entries.add(Entry(System.currentTimeMillis(), category, message.take(1000), success))
        while (entries.size > maxEntries) entries.removeAt(0)
    }
    fun snapshot(): List<Entry> = entries.toList()
}
