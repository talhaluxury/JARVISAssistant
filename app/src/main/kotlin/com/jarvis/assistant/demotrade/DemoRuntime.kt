package com.jarvis.assistant.demotrade

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.data.local.db.JarvisDatabase
import com.jarvis.assistant.quotex.domain.Candle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/** What the existing Quotex screen reader feeds into the demo engine. Implementations must not block. */
interface DemoFeed {
    fun onTick(price: Double)
    fun onCandleClosed(candles: List<Candle>, candleMs: Long, asset: String, price: Double?)
}

/** Every notification says DEMO TRADE so it can never look like a real-money order confirmation. */
class DemoNotifier(context: Context) {
    private val ctx = context.applicationContext
    private var nextId = 7000

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Demo trading (paper)", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun canNotify(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun handle(event: DemoEvent, s: DemoSettings) {
        if (!s.notificationsOn) return
        val (title, text) = describe(event) ?: return
        post(title, text, s.soundOn)
    }

    private fun money(v: Double): String = String.format(Locale.US, "%+.2f", v)

    private fun describe(e: DemoEvent): Pair<String, String>? = when (e) {
        is DemoEvent.StrongSignal -> Pair("DEMO TRADE - strong signal", "${e.signal.direction} ${e.signal.asset}: model confidence ${e.signal.confidence}/100 (not a win probability)")
        is DemoEvent.TradeOpened -> Pair("DEMO TRADE opened", "#${e.trade.id} ${e.trade.direction} stake $${String.format(Locale.US, "%.2f", e.trade.stake)} - paper only")
        is DemoEvent.TradeClosed -> Pair("DEMO TRADE ${e.trade.result?.name ?: ""}", "#${e.trade.id} ${e.trade.direction} P/L ${money(e.trade.pnl ?: 0.0)} - paper only")
        is DemoEvent.DailyTarget -> Pair("DEMO TRADE - daily target reached", "Demo P/L today ${money(e.pnl)}")
        is DemoEvent.RiskHalt -> Pair("DEMO TRADE - risk protection", e.reason.message)
        is DemoEvent.EngineStopped -> Pair("DEMO TRADE - engine stopped", e.reason)
    }

    private fun post(title: String, text: String, sound: Boolean) {
        if (!canNotify()) return
        try {
            ensureChannels()
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSilent(!sound)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java)?.notify(nextId++, n)
            if (nextId > 7999) nextId = 7000
        } catch (e: SecurityException) {
            // permission revoked while running: skip
        }
    }

    companion object {
        private const val CHANNEL = "jarvis_demo_trading"
    }
}

private class CandleJob(val candles: List<Candle>, val candleMs: Long, val asset: String, val price: Double?)

/**
 * Wires the engine to the live feed: one serial worker for candles (so two candles can never race), a 1-second clock for
 * expiry/settlement and for detecting a dead data feed. Created lazily by [DemoTradingModule].
 */
class DemoRuntime(
    val engine: DemoTradingEngine,
    private val aiProvider: AiOpinionProvider?,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : DemoFeed {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = Channel<CandleJob>(Channel.CONFLATED)
    @Volatile private var lastPrice: Double? = null
    @Volatile private var lastTickMs = 0L
    @Volatile private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch {
            for (job in jobs) {
                try {
                    process(job)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    engine.markPhase(EnginePhase.WAITING_FOR_DATA, "analysis error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
        scope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // never let the clock die
                }
                delay(1000L)
            }
        }
    }

    fun stop() {
        started = false
        jobs.close()
        scope.cancel()
    }

    override fun onTick(price: Double) {
        if (price.isNaN() || price.isInfinite() || price <= 0.0) return
        lastPrice = price
        lastTickMs = clock()
    }

    override fun onCandleClosed(candles: List<Candle>, candleMs: Long, asset: String, price: Double?) {
        if (!started) return
        jobs.trySend(CandleJob(candles, candleMs, asset, price))
    }

    private fun tick() {
        val now = clock()
        val stale = lastTickMs == 0L || now - lastTickMs > FEED_TIMEOUT_MS
        if (stale) {
            val msg = if (lastTickMs == 0L) "no price received yet" else "no price for ${(now - lastTickMs) / 1000}s"
            engine.setFeedUnavailable(msg, now)
            engine.onTick(now, null)
        } else {
            engine.setFeedUnavailable(null, now)
            engine.onTick(now, lastPrice)
        }
    }

    private suspend fun process(job: CandleJob) {
        val now = clock()
        val s = engine.settingsSnapshot()
        engine.markPhase(EnginePhase.ANALYZING, "Analyzing closed candle")
        var ai: AiOpinion? = null
        val provider = aiProvider
        if (s.useAi && provider != null && !engine.hasActiveTrade()) {
            val p = engine.preview(job.candles, job.candleMs, now)
            if (p.promising(s)) {
                engine.markPhase(EnginePhase.CONFIRMING, "Asking the AI layer for a second opinion")
                ai = provider.opinion(AiSignalAnalyzer.buildPrompt(p, job.asset, s))
            }
        }
        engine.onCandleClosed(job.candles, job.candleMs, job.asset, job.price ?: lastPrice, clock(), ai)
    }

    companion object {
        const val FEED_TIMEOUT_MS = 10_000L
    }
}

/** Single entry point wired into AppContainer (`container.demoTrading`). */
class DemoTradingModule(context: Context, aiService: AiService?) {
    private val appContext = context.applicationContext
    private val notifier = DemoNotifier(appContext)

    val engine: DemoTradingEngine by lazy {
        // Closed trades live in the app's Room database; if it cannot be opened the engine falls back to the JSON file.
        val history: DemoTradeHistoryStore? = try {
            RoomTradeHistory(JarvisDatabase.getInstance(appContext).demoTradeDao())
        } catch (e: Exception) {
            null
        }
        DemoTradingEngine(
            store = FileDemoStateStore(File(appContext.filesDir, "demo_trading_state.json")),
            listener = { e, s -> notifier.handle(e, s) },
            history = history
        ).also { it.restore() }
    }

    /** Single place that changes the demo settings: also starts/stops the foreground service to match Auto Demo Trading. */
    fun updateSettings(change: (DemoSettings) -> DemoSettings) {
        val e = engine
        e.updateSettings(change(e.settingsSnapshot()))
        DemoEngineService.sync(appContext, e.settingsSnapshot().autoDemoTrading)
    }

    val runtime: DemoRuntime by lazy {
        DemoRuntime(engine, aiService?.let { AiSignalAnalyzer(it) }).also { it.start() }
    }
}
