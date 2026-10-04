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

    /** Use candles read from the chart image to correct sampled highs/lows. Safe to turn off if a build misreads. */
    var useChartCandles: Boolean
        get() = prefs.getBoolean("use_chart_candles", true)
        set(value) { prefs.edit().putBoolean("use_chart_candles", value).apply() }

    /**
     * Lets JARVIS scroll the Quotex chart back in time (a horizontal drag inside the chart, nothing else) while it
     * loads history. On by default; with it off, "Load chart history" asks YOU to drag the chart instead.
     */
    var autoChartPan: Boolean
        get() = prefs.getBoolean("auto_chart_pan", false)
        set(value) { prefs.edit().putBoolean("auto_chart_pan", value).apply() }

    /** Starts "load chart history" by itself whenever the chart is readable but too few candles are known. */
    var autoLoadHistory: Boolean
        get() = prefs.getBoolean("auto_load_history", true)
        set(value) { prefs.edit().putBoolean("auto_load_history", value).apply() }

    var candleSeconds: Int
        get() = prefs.getInt("candle_seconds", 60)
        set(value) { prefs.edit().putInt("candle_seconds", value).apply() }

    var expiryCandles: Int
        get() = prefs.getInt("expiry_candles", 4)
        set(value) { prefs.edit().putInt("expiry_candles", value).apply() }

    /** Virtual stake per paper trade (play money, only used for the paper profit/loss display). */
    var paperStake: Float
        get() = prefs.getFloat("paper_stake", 10f)
        set(value) { prefs.edit().putFloat("paper_stake", value.coerceIn(1f, 100000f)).apply() }

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

    /** User-declared hypothetical stake per call, for the risk engine only - JARVIS never places a real trade. */
    var stakePerTrade: Float
        get() = prefs.getFloat("stake_per_trade", 1f)
        set(value) { prefs.edit().putFloat("stake_per_trade", value).apply() }

    var dailyLossLimit: Float
        get() = prefs.getFloat("daily_loss_limit", 5f)
        set(value) { prefs.edit().putFloat("daily_loss_limit", value).apply() }

    var maxConsecutiveLosses: Int
        get() = prefs.getInt("max_consecutive_losses", 3)
        set(value) { prefs.edit().putInt("max_consecutive_losses", value).apply() }

    var maxTradesPerDay: Int
        get() = prefs.getInt("max_trades_day", 30)
        set(value) { prefs.edit().putInt("max_trades_day", value).apply() }

    var maxTradesPerHour: Int
        get() = prefs.getInt("max_trades_hour", 10)
        set(value) { prefs.edit().putInt("max_trades_hour", value).apply() }

    var maxDailyExposure: Float
        get() = prefs.getFloat("max_daily_exposure", 50f)
        set(value) { prefs.edit().putFloat("max_daily_exposure", value).apply() }

    /** Setups scoring below this (0-100 setup strength, not a probability) are shown as WATCH, never as a setup. */
    var minSetupScore: Int
        get() = prefs.getInt("min_setup_score", 60)
        set(value) { prefs.edit().putInt("min_setup_score", value.coerceIn(0, 100)).apply() }

    /** Below this data-quality score (0-100) the analysis is paused with NO SIGNAL. */
    var minDataQualityScore: Int
        get() = prefs.getInt("min_data_quality", 60)
        set(value) { prefs.edit().putInt("min_data_quality", value.coerceIn(0, 100)).apply() }

    /** Verbose analysis logging (never logs keys, tokens or personal data). */
    var debugLogging: Boolean
        get() = prefs.getBoolean("debug_logging", false)
        set(value) { prefs.edit().putBoolean("debug_logging", value).apply() }

    /** Comma-separated strategy names the user switched off (indicators feed these strategies, so this also switches indicators off). */
    var disabledStrategies: String
        get() = prefs.getString("disabled_strategies", "") ?: ""
        set(value) { prefs.edit().putString("disabled_strategies", value).apply() }

    /** 0 = automatic (derived from the entry timeframe). */
    var mtfMiddleSeconds: Int
        get() = prefs.getInt("mtf_middle", 0)
        set(value) { prefs.edit().putInt("mtf_middle", value).apply() }

    var mtfHigherSeconds: Int
        get() = prefs.getInt("mtf_higher", 0)
        set(value) { prefs.edit().putInt("mtf_higher", value).apply() }

    /** When on, HIGH (not only EXTREME) volatility means NO TRADE. */
    var blockHighVolatility: Boolean
        get() = prefs.getBoolean("block_high_vol", false)
        set(value) { prefs.edit().putBoolean("block_high_vol", value).apply() }

    /** Only when the user says they maintain a calendar does an empty list mean "no events"; otherwise news is UNAVAILABLE. */
    var newsCalendarEnabled: Boolean
        get() = prefs.getBoolean("news_calendar_enabled", false)
        set(value) { prefs.edit().putBoolean("news_calendar_enabled", value).apply() }

    var newsEvents: String
        get() = prefs.getString("news_events", "") ?: ""
        set(value) { prefs.edit().putString("news_events", value).apply() }

    var newsHighWindowMin: Int
        get() = prefs.getInt("news_high_window_min", 15)
        set(value) { prefs.edit().putInt("news_high_window_min", value.coerceIn(1, 120)).apply() }

    var labRules: String
        get() = prefs.getString("lab_rules", "") ?: ""
        set(value) { prefs.edit().putString("lab_rules", value).apply() }

    var labPaperStats: String
        get() = prefs.getString("lab_paper_stats", "") ?: ""
        set(value) { prefs.edit().putString("lab_paper_stats", value).apply() }

    fun riskConfig(): com.jarvis.assistant.quotex.risk.RiskConfig = try {
        com.jarvis.assistant.quotex.risk.RiskConfig(stakePerTrade.toDouble(), dailyLossLimit.toDouble(), maxConsecutiveLosses, maxTradesPerDay, maxTradesPerHour, maxDailyExposure.toDouble())
    } catch (e: IllegalArgumentException) {
        com.jarvis.assistant.quotex.risk.RiskConfig()
    }

    fun config(): QuotexConfig = try {
        QuotexConfig(
            candleSeconds = candleSeconds, expiryCandles = expiryCandles, payout = payout.toDouble(),
            requireVerifiedEdge = requireVerifiedEdge
        )
    } catch (e: IllegalArgumentException) {
        QuotexConfig(requireVerifiedEdge = requireVerifiedEdge)
    }
}
