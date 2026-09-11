package com.jarvis.assistant.core.security

/** Removes obvious secrets from text before it enters an external AI context. */
object ContextSanitizer {
    private val patterns = listOf(
        Regex("(?i)bearer\\s+[A-Za-z0-9._-]+"),
        Regex("(?i)(api[_-]?key|token|secret|password)\\s*[:=]\\s*[^\\s,;]+"),
        Regex("sk-[A-Za-z0-9_-]{10,}"),
        Regex("AIza[0-9A-Za-z_-]{20,}")
    )
    fun sanitize(input: String, maxChars: Int = 12000): String {
        var out = input
        patterns.forEach { out = it.replace(out) { "[REDACTED]" } }
        return out.take(maxChars)
    }
}
