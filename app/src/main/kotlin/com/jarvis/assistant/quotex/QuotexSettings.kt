package com.jarvis.assistant.quotex

import android.content.Context
import com.jarvis.assistant.quotex.domain.QuotexConfig

class QuotexSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("quotex_settings", Context.MODE_PRIVATE)

    var liveAnalysisEnabled: Boolean
        get() = prefs.getBoolean("live", false)
        set(value) { prefs.edit().putBoolean("live", value).apply() }

    var requireVerifiedEdge: Boolean
        get() = prefs.getBoolean("require_edge", true)
        set(value) { prefs.edit().putBoolean("require_edge", value).apply() }

    var candleSeconds: Int
        get() = prefs.getInt("candle_seconds", 15)
        set(value) { prefs.edit().putInt("candle_seconds", value).apply() }

    var expiryCandles: Int
        get() = prefs.getInt("expiry_candles", 4)
        set(value) { prefs.edit().putInt("expiry_candles", value).apply() }

    /** Fraction paid on a win, e.g. 0.85 - copy it from the payout % shown next to the asset. */
    var payout: Float
        get() = prefs.getFloat("payout", 0.85f)
        set(value) { prefs.edit().putFloat("payout", value).apply() }

    /** Vertical slice of the screen that contains the asset name and the price axis. */
    var regionTop: Float
        get() = prefs.getFloat("region_top", 0.06f)
        set(value) { prefs.edit().putFloat("region_top", value).apply() }

    var regionBottom: Float
        get() = prefs.getFloat("region_bottom", 0.85f)
        set(value) { prefs.edit().putFloat("region_bottom", value).apply() }

    var lastAsset: String?
        get() = prefs.getString("last_asset", null)
        set(value) { prefs.edit().putString("last_asset", value).apply() }

    fun config(): QuotexConfig = try {
        QuotexConfig(
            candleSeconds = candleSeconds, expiryCandles = expiryCandles, payout = payout.toDouble(),
            requireVerifiedEdge = requireVerifiedEdge
        )
    } catch (e: IllegalArgumentException) {
        QuotexConfig(requireVerifiedEdge = requireVerifiedEdge)
    }
}
