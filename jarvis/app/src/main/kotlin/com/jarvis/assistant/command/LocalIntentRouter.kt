package com.jarvis.assistant.command

/**
 * A small, fast, offline keyword/phrase matcher for the handful of commands where correct
 * routing matters more than anything else: scroll, back, home, recents, stop, "search here",
 * "tap the first one", and opening a named app. It runs BEFORE the AI on every heard phrase.
 *
 * Why this exists: the AI round-trip is (a) slow — bad for "scroll down" needing to feel
 * instant — and (b) an extra place routing could go wrong. For this whitelist of commands,
 * matching locally guarantees they always act on the CURRENT foreground app and never open
 * the JARVIS activity, works even with no network/API key configured, and understands
 * English, Urdu, Roman Urdu, Hindi, Punjabi, and Arabic phrasing without needing exact matches.
 *
 * This is intentionally a small, closed whitelist, not a general NLU replacement — anything
 * that isn't a confident match here still falls through to the AI + CommandEngine pipeline.
 */
object LocalIntentRouter {

    fun match(rawText: String): JarvisCommand? {
        val text = normalize(rawText)
        if (text.isBlank()) return null

        if (containsAny(text, STOP_WORDS)) return JarvisCommand.StopAction

        if (containsAny(text, ACTIVATE_HUD_WORDS)) return JarvisCommand.ActivateHud
        if (containsAny(text, STANDBY_HUD_WORDS)) return JarvisCommand.StandbyHud
        if (containsAny(text, SYSTEM_STATUS_WORDS)) return JarvisCommand.ShowSystemStatus
        if (containsAny(text, BATTERY_WORDS)) return JarvisCommand.ShowBattery
        if (containsAny(text, NETWORK_WORDS)) return JarvisCommand.ShowNetwork
        if (containsAny(text, NOTIFICATION_HUD_WORDS)) return JarvisCommand.ShowNotificationsHud
        if (containsAny(text, FULL_HUD_WORDS)) return JarvisCommand.FullHud
        if (containsAny(text, MINIMAL_HUD_WORDS)) return JarvisCommand.MinimalHud
        if (containsAny(text, POWER_SAVING_WORDS)) return JarvisCommand.PowerSavingHud

        if (containsAny(text, SCROLL_DOWN_WORDS)) return JarvisCommand.ScrollDown
        if (containsAny(text, SCROLL_UP_WORDS)) return JarvisCommand.ScrollUp

        if (containsAny(text, HOME_WORDS)) return JarvisCommand.GoHome
        if (containsAny(text, BACK_WORDS)) return JarvisCommand.GoBack
        if (containsAny(text, RECENTS_WORDS)) return JarvisCommand.OpenRecentApps

        if (containsAny(text, FIRST_RESULT_WORDS)) return JarvisCommand.TapFirstResult

        // Explicitly opening JARVIS itself — the one time the activity SHOULD open.
        if (containsAny(text, OPEN_JARVIS_WORDS)) return JarvisCommand.OpenApp("JARVIS")

        val trimmed = text.trim()
        extractSearchQuery(trimmed)?.let { query -> return JarvisCommand.SearchCurrentApp(query) }

        extractAppToOpen(trimmed)?.let { app -> return JarvisCommand.OpenApp(app) }

        return null
    }

    /** Recognizes a yes/no confirmation phrase across supported languages, or null if unclear. */
    fun parseConfirmation(rawText: String): Boolean? {
        val text = normalize(rawText)
        if (containsAny(text, YES_WORDS)) return true
        if (containsAny(text, NO_WORDS)) return false
        return null
    }

    private fun normalize(text: String): String =
        " " + text.lowercase().trim().replace(Regex("[.,!?،؟]"), "") + " "

    private fun containsAny(text: String, words: List<String>): Boolean =
        words.any { text.contains(" $it ") || text.startsWith("$it ") || text.endsWith(" $it") || text.trim() == it }

    private fun extractSearchQuery(text: String): String? {
        for (pattern in SEARCH_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val query = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (query.isNotBlank()) return query
        }
        return null
    }

    private fun extractAppToOpen(text: String): String? {
        for (pattern in OPEN_APP_PATTERNS) {
            val match = pattern.find(text) ?: continue
            val app = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (app.isNotBlank() && app.length <= 30) return app
        }
        return null
    }

    // --- word lists (kept lowercase, space-normalized) -----------------------------------

    private val ACTIVATE_HUD_WORDS = listOf("activate hud", "hud activate", "hud on", "holographic interface activate", "jarvis activate hud")
    private val STANDBY_HUD_WORDS = listOf("standby", "hud standby", "stand by", "jarvis standby")
    private val SYSTEM_STATUS_WORDS = listOf("system status", "phone status", "system report", "show system status", "phone ki status", "mobile ki status")
    private val BATTERY_WORDS = listOf("show battery", "battery dikhao", "battery status", "battery kitni hai")
    private val NETWORK_WORDS = listOf("show network", "network status", "wifi status", "network dikhao")
    private val NOTIFICATION_HUD_WORDS = listOf("show notifications", "notifications dikhao", "notification status")
    private val FULL_HUD_WORDS = listOf("full hud", "full interface", "complete hud")
    private val MINIMAL_HUD_WORDS = listOf("minimal hud", "minimal mode")
    private val POWER_SAVING_WORDS = listOf("power saving", "power saving hud", "save battery")

    private val STOP_WORDS = listOf(
        "stop", "cancel", "bas", "ruk jao", "ruko", "cancel karo", "band karo", "خاموش", "رک جاؤ"
    )

    private val SCROLL_DOWN_WORDS = listOf(
        "scroll down", "neeche scroll karo", "neeche karo", "neeche jao", "scroll neeche"
    )

    private val SCROLL_UP_WORDS = listOf(
        "scroll up", "upar scroll karo", "upar karo", "upar jao", "scroll upar"
    )

    private val HOME_WORDS = listOf(
        "home jao", "go home", "home screen", "ghar jao"
    )

    private val BACK_WORDS = listOf(
        "back jao", "go back", "wapis jao", "peeche jao", "back karo"
    )

    private val RECENTS_WORDS = listOf(
        "recent apps kholo", "open recent apps", "recents kholo", "recent apps"
    )

    private val FIRST_RESULT_WORDS = listOf(
        "pehli video chalao", "pehla wala kholo", "pehli wali kholo", "upar wala kholo",
        "upar wala select karo", "neeche wala select karo", "play the first",
        "open the first", "select the first", "first video chalao", "pehli video play karo"
    )

    private val OPEN_JARVIS_WORDS = listOf(
        "open jarvis", "jarvis kholo", "jarvis settings kholo", "jarvis settings",
        "jarvis open karo"
    )

    private val YES_WORDS = listOf(
        "haan", "han", "ji haan", "ji han", "yes", "yeah", "yep", "kar do", "send karo",
        "send kar do", "ok", "okay", "theek hai", "haan kar do"
    )

    private val NO_WORDS = listOf(
        "nahi", "nahin", "no", "nope", "cancel", "mat karo", "rehne do"
    )

    // Captures free text after a "search X" / "X search karo" style phrase.
    private val SEARCH_PATTERNS = listOf(
        Regex("""search (?:karo|kar do|kijiye)? ?(?:for )?(.+)"""),
        Regex("""(.+?) (?:ko )?search karo"""),
        Regex("""(.+?) talash karo""")
    )

    // Captures the app name out of "X kholo" / "open X" / "X open karo" / "X chalao" style phrases.
    private val OPEN_APP_PATTERNS = listOf(
        Regex("""^open (.+)"""),
        Regex("""^(.+?) kholo$"""),
        Regex("""^(.+?) open karo$"""),
        Regex("""^(.+?) chalao$"""),
        Regex("""^(.+?) open kar do$""")
    )
}
