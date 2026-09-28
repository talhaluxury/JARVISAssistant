package com.jarvis.assistant.remote

import android.content.Context

/**
 * A stable, per-install pairing code for this phone.
 *
 * Set to a fixed, user-chosen code instead of a random one, so it's easy to
 * remember and type. The server always compares codes in uppercase, so this
 * is stored/returned uppercase to avoid any case-mismatch confusion between
 * what's shown on Phone 1 and what needs to be typed on Phone 2.
 */
object DeviceIdentity {
    private const val FIXED_CODE = "TALHA1231"

    fun get(context: Context): String = FIXED_CODE
}
