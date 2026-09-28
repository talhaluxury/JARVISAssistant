package com.jarvis.assistant.wingo

import android.content.Context
import com.jarvis.assistant.wingo.domain.NormalizedRegion
import com.jarvis.assistant.wingo.domain.WinGoConfig

/** Small SharedPreferences store for WinGo Analyzer settings (nothing sensitive is kept here). */
class WinGoSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("wingo_settings", Context.MODE_PRIVATE)

    /** Step 8 of setup: predictions are only produced once the user turns live analysis on. */
    var liveAnalysisEnabled: Boolean
        get() = prefs.getBoolean(KEY_LIVE, false)
        set(value) { prefs.edit().putBoolean(KEY_LIVE, value).apply() }

    var requireVerifiedEdge: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_EDGE, true)
        set(value) { prefs.edit().putBoolean(KEY_REQUIRE_EDGE, value).apply() }

    var lowThreshold: Float
        get() = prefs.getFloat(KEY_LOW, 0.55f)
        set(value) { prefs.edit().putFloat(KEY_LOW, value).apply() }

    var mediumThreshold: Float
        get() = prefs.getFloat(KEY_MEDIUM, 0.60f)
        set(value) { prefs.edit().putFloat(KEY_MEDIUM, value).apply() }

    var highThreshold: Float
        get() = prefs.getFloat(KEY_HIGH, 0.70f)
        set(value) { prefs.edit().putFloat(KEY_HIGH, value).apply() }

    /** History region chosen/adjusted by the user (step 5). null = auto-detect. */
    var manualRegion: NormalizedRegion?
        get() {
            if (!prefs.contains(KEY_REGION_TOP)) return null
            return try {
                NormalizedRegion(
                    prefs.getFloat(KEY_REGION_LEFT, 0f), prefs.getFloat(KEY_REGION_TOP, 0f),
                    prefs.getFloat(KEY_REGION_RIGHT, 1f), prefs.getFloat(KEY_REGION_BOTTOM, 1f)
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        set(value) {
            val editor = prefs.edit()
            if (value == null) {
                editor.remove(KEY_REGION_LEFT).remove(KEY_REGION_TOP).remove(KEY_REGION_RIGHT).remove(KEY_REGION_BOTTOM)
            } else {
                editor.putFloat(KEY_REGION_LEFT, value.left).putFloat(KEY_REGION_TOP, value.top)
                    .putFloat(KEY_REGION_RIGHT, value.right).putFloat(KEY_REGION_BOTTOM, value.bottom)
            }
            editor.apply()
        }

    /** Invalid stored thresholds fall back to the defaults instead of crashing the service. */
    fun config(): WinGoConfig = try {
        WinGoConfig(
            lowThreshold = lowThreshold.toDouble(),
            mediumThreshold = mediumThreshold.toDouble(),
            highThreshold = highThreshold.toDouble(),
            requireVerifiedEdge = requireVerifiedEdge
        )
    } catch (e: IllegalArgumentException) {
        WinGoConfig(requireVerifiedEdge = requireVerifiedEdge)
    }

    private companion object {
        const val KEY_LIVE = "live_analysis"
        const val KEY_REQUIRE_EDGE = "require_verified_edge"
        const val KEY_LOW = "threshold_low"
        const val KEY_MEDIUM = "threshold_medium"
        const val KEY_HIGH = "threshold_high"
        const val KEY_REGION_LEFT = "region_left"
        const val KEY_REGION_TOP = "region_top"
        const val KEY_REGION_RIGHT = "region_right"
        const val KEY_REGION_BOTTOM = "region_bottom"
    }
}
