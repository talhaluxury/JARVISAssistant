package com.jarvis.assistant.remote

import android.content.Context
import java.security.SecureRandom

/**
 * A stable, per-install pairing code for this phone.
 *
 * Earlier versions asked the relay server to hand out a fresh random 6-digit
 * code on every connection. That meant the code shown on Phone 1 could go
 * stale the instant the WebSocket reconnected (server restart, brief network
 * drop, Render free-tier sleep/wake), while Phone 1 kept displaying the old
 * number and Phone 2 kept getting "target is offline".
 *
 * Instead we generate one code the first time the app runs, save it locally,
 * and reuse it forever. Phone 2 can bookmark a single link
 * (https://<relay>/?code=<this id>) that keeps working across reconnects,
 * server restarts, and app restarts.
 */
object DeviceIdentity {
    private const val PREFS = "jarvis_remote_identity"
    private const val KEY_ID = "device_id"

    // Excludes 0/O and 1/I to avoid ambiguity if the code is ever typed by hand.
    private val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    @Volatile private var cached: String? = null

    fun get(context: Context): String {
        cached?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var id = prefs.getString(KEY_ID, null)
        if (id.isNullOrBlank()) {
            id = generate()
            prefs.edit().putString(KEY_ID, id).apply()
        }
        cached = id
        return id
    }

    private fun generate(): String {
        val rnd = SecureRandom()
        return (1..8).map { CHARS[rnd.nextInt(CHARS.length)] }.joinToString("")
    }
}
