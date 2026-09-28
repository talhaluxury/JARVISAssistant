package com.jarvis.assistant.agent

import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.LocalIntentRouter

/**
 * #39 INTENT ENGINE / #37 CONTEXT-AWARE INTELLIGENCE (follow-up resolution) / #49 CONFIDENCE
 *
 * Turns raw heard/typed text (English, Urdu, Roman Urdu, or a natural mix) into a structured
 * [ParsedIntent] before anything is planned or executed. This does NOT replace the AI — for
 * anything beyond the closed set of fast local commands, JARVIS still calls the model for real
 * understanding — but it gives every downstream layer (ContextManager, TaskEngine, the
 * clarification flow) one consistent, typed shape to reason about instead of raw strings, and
 * it is what lets low-confidence/ambiguous input trigger a clarifying question instead of a
 * guess with real consequences.
 */
object IntentEngine {

    private val FOLLOW_UP_REFERENCE_WORDS = listOf(
        "iska", "isko", "iski", "uska", "usko", "ye", "yeh", "wo", "woh", "it", "that", "this", "iske"
    )
    private val RETRY_WORDS = listOf("phir se karo", "dobara karo", "try again", "retry", "once more", "wapis karo")
    private val PAUSE_WORDS = listOf("pause", "ruko zara", "wait karo", "hold on")
    private val RESUME_WORDS = listOf("resume", "continue karo", "jari rakho", "wapis shuru karo")
    private val CANCELLATION_WORDS = listOf("cancel", "stop", "bas", "ruk jao", "band karo")
    private val CONFIRMATION_YES = listOf("haan", "han", "yes", "ok", "okay", "theek hai", "kar do")
    private val CONFIRMATION_NO = listOf("nahi", "nahin", "no", "nope", "mat karo")
    private val TROUBLESHOOT_WORDS = listOf("ye kyun nahi hua", "why didn't that work", "kyun nahi hua", "isme masla kya hai", "what went wrong")
    private val DIAGNOSTIC_WORDS = listOf("run jarvis diagnostic", "jarvis diagnostic", "system diagnostic chalao", "run diagnostic", "self check", "diagnostic chalao")
    private val MEMORY_QUERY_WORDS = listOf("what do you remember about me", "mujhe kya yaad hai", "yaad kya hai", "what do you know about me", "mere baare mein kya yaad hai")
    private val MEMORY_FORGET_WORDS = listOf("forget that", "bhool jao", "ye bhool jao", "delete my saved preferences", "delete that memory")
    private val BATTERY_WORDS = listOf("battery kitni hai", "battery status", "battery kya hai", "how much battery")
    private val NETWORK_WORDS = listOf("network status", "internet chal raha hai", "wifi status", "am i online")

    /**
     * @param lastCommand the previous turn's resolved command/topic (for "iska" / "uska" style
     *   follow-ups), or null if there was none — never used for anything except reference
     *   resolution, and never applied when the current text doesn't clearly need it.
     */
    fun classify(rawText: String, lastCommand: JarvisCommand?, hasPendingTask: Boolean): ParsedIntent {
        val text = rawText.trim()
        val lower = text.lowercase()

        containedAny(lower, CANCELLATION_WORDS)?.let {
            return ParsedIntent(IntentCategory.CANCELLATION, confidence = IntentConfidence.HIGH, rawText = text)
        }
        if (hasPendingTask) {
            containedAny(lower, PAUSE_WORDS)?.let { return ParsedIntent(IntentCategory.PAUSE, confidence = IntentConfidence.HIGH, rawText = text) }
            containedAny(lower, RESUME_WORDS)?.let { return ParsedIntent(IntentCategory.RESUME, confidence = IntentConfidence.HIGH, rawText = text) }
        }
        containedAny(lower, RETRY_WORDS)?.let { return ParsedIntent(IntentCategory.RETRY, confidence = IntentConfidence.HIGH, rawText = text) }
        containedAny(lower, DIAGNOSTIC_WORDS)?.let { return ParsedIntent(IntentCategory.SYSTEM_DIAGNOSTIC, confidence = IntentConfidence.HIGH, rawText = text) }
        containedAny(lower, MEMORY_QUERY_WORDS)?.let { return ParsedIntent(IntentCategory.MEMORY_QUERY, confidence = IntentConfidence.HIGH, rawText = text) }
        containedAny(lower, MEMORY_FORGET_WORDS)?.let { return ParsedIntent(IntentCategory.MEMORY_FORGET, confidence = IntentConfidence.HIGH, rawText = text) }
        containedAny(lower, BATTERY_WORDS)?.let { return ParsedIntent(IntentCategory.BATTERY_STATUS, confidence = IntentConfidence.HIGH, rawText = text) }
        containedAny(lower, NETWORK_WORDS)?.let { return ParsedIntent(IntentCategory.NETWORK_STATUS, confidence = IntentConfidence.HIGH, rawText = text) }
        containedAny(lower, TROUBLESHOOT_WORDS)?.let {
            return ParsedIntent(IntentCategory.CLARIFICATION, confidence = IntentConfidence.MEDIUM, rawText = text)
        }
        containedAny(lower, CONFIRMATION_YES)?.let { return ParsedIntent(IntentCategory.CONFIRMATION, mapOf("value" to "true"), IntentConfidence.HIGH, text) }
        containedAny(lower, CONFIRMATION_NO)?.let { return ParsedIntent(IntentCategory.CONFIRMATION, mapOf("value" to "false"), IntentConfidence.HIGH, text) }

        // Fast local commands (scroll/back/home/search-here/open app/etc.) already have a
        // reliable multilingual matcher — reuse it rather than duplicating word lists, and
        // promote its result into the structured shape.
        LocalIntentRouter.match(rawText)?.let { command -> return fromLocalCommand(command, text) }

        // A short reference-only follow-up ("iska result batao") with a previous command to
        // resolve against — merge that context in, flagged as resolved-from-context so the
        // caller can choose to lower the confidence rather than blindly trusting it.
        if (lastCommand != null && FOLLOW_UP_REFERENCE_WORDS.any { " $lower ".contains(" $it ") }) {
            return ParsedIntent(
                category = IntentCategory.FOLLOW_UP,
                slots = mapOf("referring_to" to lastCommand.describeShort(), "text" to text),
                confidence = IntentConfidence.MEDIUM,
                rawText = text,
                resolvedFromContext = true
            )
        }

        // A single ambiguous word ("that app", "open that") without enough to safely guess —
        // low confidence so the caller asks rather than acting on a guess with consequences.
        if (Regex("""\b(that app|us app|wo app|voh app)\b""").containsMatchIn(lower)) {
            return ParsedIntent(IntentCategory.OPEN_APP, mapOf("target" to "?"), IntentConfidence.LOW, text)
        }

        return if (lower.endsWith("?") || lower.startsWith("what") || lower.startsWith("who") ||
            lower.startsWith("kya") || lower.startsWith("kaun") || lower.startsWith("kitna") || lower.startsWith("kitni")
        ) {
            ParsedIntent(IntentCategory.INFORMATION_REQUEST, confidence = IntentConfidence.MEDIUM, rawText = text)
        } else {
            ParsedIntent(IntentCategory.CONVERSATION, confidence = IntentConfidence.MEDIUM, rawText = text)
        }
    }

    private fun fromLocalCommand(command: JarvisCommand, text: String): ParsedIntent = when (command) {
        is JarvisCommand.OpenApp -> ParsedIntent(IntentCategory.OPEN_APP, mapOf("target" to command.target), IntentConfidence.HIGH, text)
        is JarvisCommand.SearchCurrentApp -> ParsedIntent(IntentCategory.SEARCH, mapOf("query" to command.query), IntentConfidence.HIGH, text)
        JarvisCommand.ScrollDown, JarvisCommand.ScrollUp -> ParsedIntent(IntentCategory.SCROLL, confidence = IntentConfidence.HIGH, rawText = text)
        JarvisCommand.GoBack, JarvisCommand.GoHome, JarvisCommand.OpenRecentApps -> ParsedIntent(IntentCategory.NAVIGATE, confidence = IntentConfidence.HIGH, rawText = text)
        JarvisCommand.TapFirstResult -> ParsedIntent(IntentCategory.TAP, confidence = IntentConfidence.HIGH, rawText = text)
        JarvisCommand.StopAction -> ParsedIntent(IntentCategory.CANCELLATION, confidence = IntentConfidence.HIGH, rawText = text)
        JarvisCommand.ShowBattery -> ParsedIntent(IntentCategory.BATTERY_STATUS, confidence = IntentConfidence.HIGH, rawText = text)
        JarvisCommand.ShowNetwork -> ParsedIntent(IntentCategory.NETWORK_STATUS, confidence = IntentConfidence.HIGH, rawText = text)
        JarvisCommand.ShowSystemStatus -> ParsedIntent(IntentCategory.DEVICE_STATUS, confidence = IntentConfidence.HIGH, rawText = text)
        else -> ParsedIntent(IntentCategory.UNKNOWN, confidence = IntentConfidence.MEDIUM, rawText = text)
    }

    private fun containedAny(lower: String, phrases: List<String>): String? =
        phrases.firstOrNull { lower.contains(it) }

    private fun JarvisCommand.describeShort(): String = when (this) {
        is JarvisCommand.OpenApp -> "opening ${target}"
        is JarvisCommand.SearchCurrentApp -> "searching for ${query}"
        is JarvisCommand.OpenMaps -> "the maps search"
        is JarvisCommand.OpenBrowser -> "the browser page"
        else -> this::class.simpleName ?: "the previous action"
    }
}
