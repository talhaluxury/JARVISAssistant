package com.jarvis.assistant.quotex.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.quotex.QuotexModule
import com.jarvis.assistant.quotex.QuotexUiState
import com.jarvis.assistant.quotex.agent.AgentNarrator
import com.jarvis.assistant.quotex.agent.AgentStatus
import com.jarvis.assistant.quotex.agent.CandleClock
import com.jarvis.assistant.quotex.agent.ExplanationEngine
import com.jarvis.assistant.quotex.agent.GuessStrength
import com.jarvis.assistant.quotex.agent.QuickGuessEngine
import com.jarvis.assistant.wingo.ScreenStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Floating JARVIS HUD for the Quotex analyzer with a small chat box. Mostly information; the only action it can trigger is the opt-in AUTO DEMO TRADE switch. Nothing else on it
 * acts on the trading app. FLAG_SECURE keeps the HUD out of the screen capture.
 */
class QuotexOverlayService : Service() {

    private enum class Tab { DETAILS, HISTORY, CHAT }

    private lateinit var windowManager: WindowManager
    private lateinit var module: QuotexModule
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false
    private var tab = Tab.DETAILS
    private var chatText = "Ask about the current chart. Answers come from stored data only."
    private var historyText = "Loading journal…"
    private var lastState = QuotexUiState()

    private lateinit var pill: TextView
    private lateinit var panel: LinearLayout
    private lateinit var assetView: TextView
    private lateinit var autoBtn: TextView
    private lateinit var autoStatusView: TextView
    private var autoOn = false
    private var autoTaps = 0
    private var lastAutoOpenMs = -1L
    private var autoStatus = ""
    private var autoWait = ""
    private var autoPending: Pair<Long, Boolean>? = null
    private var lastTradeOpenMs = 0L
    private var missStreak = 0
    private lateinit var aiView: TextView
    private val aiVerdicts = LinkedHashMap<Long, com.jarvis.assistant.quotex.agent.AiVerdict>()
    private var aiAskedFor = 0L
    private var aiInFlight = false
    private var lastAiRequestMs = 0L
    private lateinit var heroCard: LinearLayout
    private lateinit var heroArrow: TextView
    private lateinit var heroStrength: TextView
    private val strengthBars = ArrayList<View>()
    private lateinit var timerChip: TextView
    private lateinit var timerView: TextView
    private lateinit var progressFill: View
    private lateinit var progressRest: View
    private lateinit var statTrend: TextView
    private lateinit var statData: TextView
    private lateinit var statRecord: TextView
    private val candleDots = ArrayList<View>()
    private lateinit var whyBox: LinearLayout
    private lateinit var noteView: TextView
    private lateinit var contentScroll: ScrollView
    private var contentOpen = false
    private var detailText = ""
    private lateinit var contentView: TextView
    private lateinit var chatRow: LinearLayout
    private lateinit var input: EditText
    private val tabViews = HashMap<Tab, TextView>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        module = (applicationContext as JarvisApplication).container.quotex
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HIDE) {
            removeOverlay()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) {
            buildOverlay()
            scope.launch {
                withContext(Dispatchers.Default) { module.coordinator.ensureReady() }
                module.coordinator.state.collect { render(it) }
            }
            // Fast loop for the demo auto-tap so it fires right at the candle open (the 1s redraw is too coarse for short candles).
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(150)
                    autoTick()
                }
            }
            // Re-draw once a second so the "next candle in" countdown keeps moving between price updates.
            scope.launch {
                while (true) {
                    kotlinx.coroutines.delay(1000)
                    render(lastState)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun label(text: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
    }

    private fun buildOverlay() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(220)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        pill = label("◉ QUOTEX", 13f, CYAN, bold = true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(BG, CYAN, 18)
            setOnTouchListener(DragTouch { toggle() })
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = rounded(BG, CYAN, 12)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(PANEL_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setOnTouchListener(DragTouch {})
        }
        header.addView(label("J.A.R.V.I.S · QUOTEX", 12f, CYAN, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("–", 16f, CYAN, bold = true).apply {
            setPadding(dp(10), 0, dp(10), 0)
            setOnClickListener { toggle() }
        })
        header.addView(label("✕", 14f, MUTED, bold = true).apply {
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { removeOverlay(); stopSelf() }
        })
        panel.addView(header)

        val match = LinearLayout.LayoutParams.MATCH_PARENT
        val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
        fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

        assetView = label("ASSET —", 10f, MUTED)
        panel.addView(assetView)

        autoBtn = label("AUTO DEMO TRADE: OFF", 11f, CYAN, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(9))
            background = rounded(Color.TRANSPARENT, CYAN, 10)
            setOnClickListener { toggleAuto() }
        }
        val autoLp = lp(match, wrap)
        autoLp.topMargin = dp(6)
        panel.addView(autoBtn, autoLp)
        autoStatusView = label("", 9f, MUTED).apply { setPadding(0, dp(3), 0, 0) }
        panel.addView(autoStatusView)

        // 1) Big direction card: arrow + word + 3 strength bars.
        heroCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rounded(NEUTRAL_FILL, MUTED, 14)
        }
        heroArrow = label("…", 30f, MUTED, bold = true).apply { gravity = Gravity.CENTER }
        heroStrength = label("", 11f, MUTED, bold = true).apply { gravity = Gravity.CENTER }
        val bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        for (n in 0 until 3) {
            val bar = View(this).apply { background = rounded(CARD, STROKE_DIM, 3) }
            val barLp = lp(dp(30), dp(7))
            barLp.leftMargin = dp(3)
            barLp.rightMargin = dp(3)
            bars.addView(bar, barLp)
            strengthBars.add(bar)
        }
        heroCard.addView(heroArrow)
        heroCard.addView(heroStrength)
        heroCard.addView(bars)
        val heroLp = lp(match, wrap)
        heroLp.topMargin = dp(6)
        panel.addView(heroCard, heroLp)

        // 2) Countdown: chip (ENTER IN / ENTER NOW) + big timer + progress bar of the candle.
        val timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(4))
        }
        timerChip = label("", 11f, AMBER, bold = true).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = rounded(Color.TRANSPARENT, AMBER, 8)
        }
        timerView = label("--:--", 22f, Color.WHITE, bold = true).apply { gravity = Gravity.END }
        timerRow.addView(timerChip, lp(0, wrap, 1f))
        timerRow.addView(timerView)
        panel.addView(timerRow)
        val progress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(CARD, Color.TRANSPARENT, 4)
        }
        progressFill = View(this).apply { background = rounded(AMBER, AMBER, 4) }
        progressRest = View(this)
        progress.addView(progressFill, lp(0, dp(7), 0f))
        progress.addView(progressRest, lp(0, dp(7), 1f))
        panel.addView(progress, lp(match, dp(7)))

        // 3) Three small status boxes: trend / data quality / guess record.
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun statBox(caption: String): TextView {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(5), dp(4), dp(5))
                background = rounded(CARD, STROKE_DIM, 8)
            }
            box.addView(label(caption, 8f, MUTED).apply { gravity = Gravity.CENTER })
            val value = label("—", 11f, MUTED, bold = true).apply { gravity = Gravity.CENTER }
            box.addView(value)
            val boxLp = lp(0, wrap, 1f)
            boxLp.leftMargin = dp(2)
            boxLp.rightMargin = dp(2)
            statsRow.addView(box, boxLp)
            return value
        }
        statTrend = statBox("TREND")
        statData = statBox("DATA")
        statRecord = statBox("RECORD")
        panel.addView(statsRow)

        aiView = label("", 10f, MUTED).apply {
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = rounded(CARD, STROKE_DIM, 8)
        }
        val aiLp = lp(match, wrap)
        aiLp.topMargin = dp(8)
        panel.addView(aiView, aiLp)

        // 4) Last 10 candles as green / red bars.
        val stripRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        stripRow.addView(label("LAST", 9f, MUTED).apply { setPadding(0, 0, dp(6), 0) })
        for (n in 0 until 10) {
            val dot = View(this).apply { background = rounded(CARD, STROKE_DIM, 2) }
            val dotLp = lp(dp(12), dp(16))
            dotLp.rightMargin = dp(3)
            stripRow.addView(dot, dotLp)
            candleDots.add(dot)
        }
        panel.addView(stripRow)

        // 5) Why chips + short honest note.
        whyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        panel.addView(whyBox)
        noteView = label("", 9f, MUTED).apply { setPadding(0, dp(8), 0, dp(4)) }
        panel.addView(noteView)

        // 6) Real buttons. Tap once to open, tap again to close.
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(6))
        }
        for ((t, name) in listOf(Tab.DETAILS to "DETAILS", Tab.HISTORY to "HISTORY", Tab.CHAT to "CHAT")) {
            val tv = label(name, 10f, CYAN, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(2), dp(8), dp(2), dp(8))
                background = rounded(Color.TRANSPARENT, CYAN, 8)
                setOnClickListener { showTab(t) }
            }
            tabViews[t] = tv
            val tabLp = lp(0, wrap, 1f)
            tabLp.leftMargin = dp(2)
            tabLp.rightMargin = dp(2)
            tabs.addView(tv, tabLp)
        }
        panel.addView(tabs)

        contentView = label("", 10f, Color.WHITE)
        contentScroll = ScrollView(this).apply {
            addView(contentView)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130))
        }
        panel.addView(contentScroll)

        chatRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((text, question) in listOf("SIGNAL" to "signal", "WHY" to "why", "ACCURACY" to "accuracy", "BACKTEST" to "backtest")) {
            val chip = label(text, 10f, CYAN, bold = true).apply {
                setPadding(dp(6), dp(4), dp(6), dp(4))
                background = rounded(Color.TRANSPARENT, CYAN, 6)
                setOnClickListener { ask(question) }
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.rightMargin = dp(4)
            chips.addView(chip, lp)
        }
        input = EditText(this).apply {
            hint = "Ask JARVIS…"
            textSize = 11f
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) setWindowFocusable(true)
                false
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    ask(text.toString())
                    true
                } else {
                    false
                }
            }
        }
        chatRow.addView(chips)
        chatRow.addView(input)
        panel.addView(chatRow)

        container.addView(pill)
        container.addView(panel)
        root = container
        windowManager.addView(container, params)
        refreshTabs()
        render(lastState)
    }

    /** Furthest right the overlay may sit: the chart's price scale (right ~16% of the screen) must stay visible to the reader. */
    private fun maxOverlayX(): Int {
        val widthPx = resources.displayMetrics.widthPixels
        val overlayWidth = dp(if (expanded) PANEL_WIDTH_DP else PILL_WIDTH_DP)
        return ((widthPx * 0.84f).toInt() - overlayWidth).coerceAtLeast(0)
    }

    private fun toggle() {
        expanded = !expanded
        params.x = params.x.coerceAtMost(maxOverlayX())
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        pill.visibility = if (expanded) View.GONE else View.VISIBLE
        if (!expanded) setWindowFocusable(false)
        updateLayout()
    }

    private fun refreshTabs() {
        for ((t, view) in tabViews) {
            val selected = contentOpen && t == tab
            view.setTextColor(if (selected) DARK else CYAN)
            view.background = rounded(if (selected) CYAN else Color.TRANSPARENT, CYAN, 8)
        }
        contentScroll.visibility = if (contentOpen) View.VISIBLE else View.GONE
        val chatOpen = contentOpen && tab == Tab.CHAT
        chatRow.visibility = if (chatOpen) View.VISIBLE else View.GONE
        if (!chatOpen) setWindowFocusable(false)
        updateLayout()
    }

    private fun showTab(newTab: Tab) {
        contentOpen = !(contentOpen && tab == newTab)
        tab = newTab
        refreshTabs()
        if (contentOpen && newTab == Tab.HISTORY) loadHistory()
        renderContent()
    }

    private fun ask(question: String) {
        if (question.isBlank()) return
        setWindowFocusable(false)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.setText("")
        chatText = "…"
        tab = Tab.CHAT
        contentOpen = true
        refreshTabs()
        scope.launch {
            chatText = try {
                withContext(Dispatchers.Default) { module.chat.answer(question) }
            } catch (e: Exception) {
                "I could not answer that."
            }
            renderContent()
        }
    }

    /** Section 40: the only five states the overlay may show, each with its own colour. No made-up confidence. */
    private fun statusColor(status: AgentStatus): Int = when (status) {
        AgentStatus.SETUP_DETECTED -> GREEN
        AgentStatus.WATCH -> AMBER
        AgentStatus.WAIT -> BLUE
        AgentStatus.NO_TRADE -> RED
        AgentStatus.DATA_UNCERTAIN -> MUTED
    }

    private fun toggleAuto() {
        if (!autoOn) {
            if (!JarvisAccessibilityService.isEnabled) {
                autoStatus = "Pehle Settings > Accessibility me JARVIS on karo"
                renderAuto()
                return
            }
            autoOn = true
            autoTaps = 0
            lastAutoOpenMs = -1L
            lastTradeOpenMs = 0L
            missStreak = 0
            autoPending = null
            autoWait = ""
            autoStatus = "Sirf STRONG + AI agree par trade hogi"
        } else {
            autoOn = false
            autoStatus = ""
            autoWait = ""
        }
        renderAuto()
    }

    private fun renderAuto() {
        if (root == null) return
        if (autoOn) {
            autoBtn.text = "AUTO DEMO TRADE: ON ($autoTaps/$MAX_AUTO_TAPS)"
            autoBtn.setTextColor(DARK)
            autoBtn.background = rounded(GREEN, GREEN, 10)
        } else {
            autoBtn.text = "AUTO DEMO TRADE: OFF"
            autoBtn.setTextColor(CYAN)
            autoBtn.background = rounded(Color.TRANSPARENT, CYAN, 10)
        }
        val statusText = (autoStatus + (if (autoOn && autoWait.isNotBlank()) "\n$autoWait" else "")).trim()
        autoStatusView.text = statusText
        autoStatusView.visibility = if (statusText.isBlank()) View.GONE else View.VISIBLE
    }

    /**
     * Selective auto trade. A tap happens only when ALL of these hold: the guess for this candle is STRONG, the AI (when a key
     * is configured) agrees with at least [AI_MIN_CONFIDENCE]% confidence, at least [MIN_GAP_CANDLES] candles passed since the
     * last auto trade, we are inside the first 30% of the candle, and the run has not hit its trade cap or miss streak.
     */
    private fun autoTick() {
        if (!autoOn || root == null) return
        val s = lastState
        if (!s.monitorOn) return
        val candleMs = module.coordinator.currentConfig().candleSeconds * 1000L
        if (candleMs <= 0L) return
        val now = System.currentTimeMillis()
        val currentOpen = Math.floorDiv(now, candleMs) * candleMs

        // Score the previous auto trade once its candle has closed.
        autoPending?.let { (openMs, wasUp) ->
            if (s.lastClosedOpenMs >= openMs) {
                autoPending = null
                val closedUp = s.lastClosedUp
                if (s.lastClosedOpenMs == openMs && closedUp != null) {
                    missStreak = if (closedUp == wasUp) 0 else missStreak + 1
                    autoStatus = (if (closedUp == wasUp) "\u2705 pichli trade sahi gayi" else "\u274C pichli trade galat gayi") + " (galat lagatar: $missStreak)"
                    if (missStreak >= MAX_MISS_STREAK) {
                        autoOn = false
                        autoWait = ""
                        autoStatus = "$MAX_MISS_STREAK galat lagatar, auto band. Record dekho."
                        renderAuto()
                        return
                    }
                }
            }
        }

        if (lastAutoOpenMs == currentOpen) return
        if (now - currentOpen > (candleMs * ENTRY_WINDOW).toLong()) return
        if (lastTradeOpenMs > 0L && currentOpen - lastTradeOpenMs < MIN_GAP_CANDLES * candleMs) {
            autoWait = "Gap: pichli trade ke baad $MIN_GAP_CANDLES candle ruko"
            return
        }
        val g = (s.entryGuess ?: s.nextGuess)?.takeIf { it.forOpenMs == currentOpen }
        if (g == null) {
            autoWait = "Guess ka intezar..."
            return
        }
        if (g.strength != com.jarvis.assistant.quotex.agent.GuessStrength.STRONG) {
            autoWait = "Guess ${g.strength.name} hai, STRONG chahiye"
            return
        }
        if (module.advisor != null) {
            val v = aiVerdicts[currentOpen]
            if (v == null) {
                autoWait = if (aiInFlight) "AI soch raha hai..." else "AI jawab nahi aaya (tez candle me der ho jati hai)"
                return
            }
            if (v.call != g.label || v.confidence < AI_MIN_CONFIDENCE) {
                autoWait = "AI agree nahi (${v.call} ${v.confidence}%): skip"
                lastAutoOpenMs = currentOpen
                return
            }
        }
        val result = JarvisAccessibilityService.tradeTap(g.up)
        if (result.ok) {
            lastAutoOpenMs = currentOpen
            lastTradeOpenMs = currentOpen
            autoPending = currentOpen to g.up
            autoTaps++
            autoWait = ""
            autoStatus = "\u2714 ${result.message} #$autoTaps"
            if (autoTaps >= MAX_AUTO_TAPS) {
                autoOn = false
                autoStatus = "$MAX_AUTO_TAPS trades poore hue, auto band. Record dekho."
            }
        } else {
            autoStatus = result.message
            if (result.liveBlocked) autoOn = false
        }
        renderAuto()
    }

    /** Asks the AI about a STRONG upcoming guess (one request per candle, one at a time, at most every 8 seconds). */
    private fun maybeAskAi(state: QuotexUiState) {
        val advisor = module.advisor
        val g = state.nextGuess
        if (advisor == null || g == null || g.strength != com.jarvis.assistant.quotex.agent.GuessStrength.STRONG) return
        if (aiInFlight || aiAskedFor == g.forOpenMs || aiVerdicts.containsKey(g.forOpenMs)) return
        val now = System.currentTimeMillis()
        if (now - lastAiRequestMs < 8_000L) return
        aiInFlight = true
        aiAskedFor = g.forOpenMs
        lastAiRequestMs = now
        scope.launch {
            val verdict = try { advisor.verdict(g) } catch (e: Exception) { null }
            if (verdict != null) aiVerdicts[g.forOpenMs] = verdict
            while (aiVerdicts.size > 6) aiVerdicts.remove(aiVerdicts.keys.first())
            aiInFlight = false
        }
    }

    private fun setHero(accent: Int, fill: Int, arrow: String, sub: String, bars: Int) {
        heroCard.background = rounded(fill, accent, 14)
        heroArrow.text = arrow
        heroArrow.setTextColor(accent)
        heroStrength.text = sub
        heroStrength.setTextColor(accent)
        for ((i, bar) in strengthBars.withIndex()) bar.background = if (i < bars) rounded(accent, accent, 3) else rounded(CARD, STROKE_DIM, 3)
    }

    private fun render(state: QuotexUiState) {
        lastState = state
        if (root == null) return
        val agent = state.agent
        val report = agent?.report
        val nowMs = System.currentTimeMillis()
        val status = report?.status ?: AgentStatus.DATA_UNCERTAIN

        val tfSeconds = module.coordinator.currentConfig().candleSeconds
        val candleMs = tfSeconds * 1000L
        val remainingMs = CandleClock.remainingMs(nowMs, candleMs)
        val elapsedMs = candleMs - remainingMs
        val elapsedFraction = if (candleMs > 0) elapsedMs.toDouble() / candleMs else 1.0
        val enterNow = state.entryGuess?.takeIf { elapsedFraction <= ENTRY_WINDOW }
        val g = enterNow ?: state.nextGuess

        assetView.text = "${state.asset ?: "ASSET —"}  ${CandleClock.label(tfSeconds)}  ${state.lastPrice?.toString() ?: ""}".trimEnd()

        // ---- big direction card -------------------------------------------------------------------
        val accent: Int
        when {
            !state.monitorOn -> {
                accent = MUTED
                setHero(MUTED, NEUTRAL_FILL, "◉ OFF", "Monitor band hai", 0)
            }
            state.screenStatus == ScreenStatus.NOT_DETECTED -> {
                accent = MUTED
                setHero(MUTED, NEUTRAL_FILL, "NO CHART", "Quotex ka chart screen pe kholo", 0)
            }
            g == null -> {
                accent = AMBER
                setHero(AMBER, NEUTRAL_FILL, "WAIT", "Candles jama ho rahi hain ${state.candleCount}/${QuickGuessEngine.MIN_CANDLES}", 0)
            }
            else -> {
                accent = if (g.up) GREEN else RED
                val bars = when (g.strength) { GuessStrength.WEAK -> 1; GuessStrength.MEDIUM -> 2; GuessStrength.STRONG -> 3 }
                setHero(accent, if (g.up) UP_FILL else DOWN_FILL, "${g.arrow} ${g.label}", "${g.strength.name} guess", bars)
            }
        }

        // ---- collapsed pill ------------------------------------------------------------------------
        pill.text = when {
            !state.monitorOn -> "◉ QUOTEX · OFF"
            state.screenStatus == ScreenStatus.NOT_DETECTED -> "⚪ NO CHART"
            g != null -> "${g.arrow} ${g.label}  ${CandleClock.format(remainingMs)}"
            else -> "${status.emoji} ${status.label}"
        }
        pill.setTextColor(if (!state.monitorOn) MUTED else if (g != null) accent else statusColor(status))

        // ---- countdown -----------------------------------------------------------------------------
        val chipColor: Int
        when {
            enterNow != null -> {
                chipColor = GREEN
                timerChip.text = "ENTER NOW"
                timerView.text = CandleClock.format((candleMs * ENTRY_WINDOW).toLong() - elapsedMs)
            }
            g != null -> {
                chipColor = AMBER
                timerChip.text = "ENTER IN"
                timerView.text = CandleClock.format(remainingMs)
            }
            else -> {
                chipColor = MUTED
                timerChip.text = "CANDLE"
                timerView.text = CandleClock.format(remainingMs)
            }
        }
        timerChip.setTextColor(chipColor)
        timerChip.background = rounded(Color.TRANSPARENT, chipColor, 8)
        progressFill.background = rounded(chipColor, chipColor, 4)
        val frac = elapsedFraction.coerceIn(0.0, 1.0).toFloat()
        (progressFill.layoutParams as LinearLayout.LayoutParams).weight = frac
        (progressRest.layoutParams as LinearLayout.LayoutParams).weight = 1f - frac
        progressFill.requestLayout()

        // ---- three small status boxes --------------------------------------------------------------
        val trendName = report?.trend?.name
        statTrend.text = when (trendName) {
            "STRONG_UP" -> "UP ⬆⬆"
            "WEAK_UP" -> "UP ⬆"
            "RANGE" -> "SIDE ↔"
            "WEAK_DOWN" -> "DOWN ⬇"
            "STRONG_DOWN" -> "DOWN ⬇⬇"
            "UNSTABLE" -> "UNSTABLE"
            else -> "—"
        }
        statTrend.setTextColor(when {
            trendName?.endsWith("UP") == true -> GREEN
            trendName?.endsWith("DOWN") == true -> RED
            trendName == null -> MUTED
            else -> AMBER
        })
        val dq = report?.dataQuality?.name
        statData.text = dq ?: "—"
        statData.setTextColor(when (dq) {
            "EXCELLENT", "GOOD" -> GREEN
            "FAIR" -> AMBER
            "POOR" -> RED
            else -> MUTED
        })
        if (state.guessTotal > 0) {
            val pct = state.guessHits * 100 / state.guessTotal
            statRecord.text = "${state.guessHits}/${state.guessTotal} · $pct%"
            statRecord.setTextColor(if (pct >= (state.breakEven * 100).toInt()) GREEN else AMBER)
        } else {
            statRecord.text = "—"
            statRecord.setTextColor(MUTED)
        }

        // ---- last candles --------------------------------------------------------------------------
        val offset = candleDots.size - state.recentUp.size
        for ((i, dot) in candleDots.withIndex()) {
            val up = if (i >= offset) state.recentUp[i - offset] else null
            dot.background = when (up) {
                true -> rounded(GREEN, GREEN, 2)
                false -> rounded(RED, RED, 2)
                null -> rounded(CARD, STROKE_DIM, 2)
            }
        }

        // ---- reasons + note ------------------------------------------------------------------------
        whyBox.removeAllViews()
        for (reason in g?.reasons.orEmpty()) {
            val chip = label("• $reason", 10f, Color.WHITE).apply {
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = rounded(CARD, STROKE_DIM, 8)
            }
            val chipLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            chipLp.bottomMargin = dp(3)
            whyBox.addView(chip, chipLp)
        }
        noteView.text = when {
            state.risk?.paused == true -> "TRADING PAUSED — ${state.risk?.reason}"
            dq == "POOR" -> "⚠ Data kharab hai, guess pe bharosa kam. Andaza hai, signal nahi."
            else -> "Andaza hai, signal nahi. Demo me hi try karo."
        }
        noteView.setTextColor(if (dq == "POOR" || state.risk?.paused == true) AMBER else MUTED)

        // ---- DETAILS tab text (the old full report) ------------------------------------------------
        val detail = StringBuilder()
        if (report != null && agent != null) {
            for (line in ExplanationEngine.overlayLines(report, state.asset ?: "ASSET —", tfSeconds).drop(1).dropLast(1)) detail.appendLine(line)
            detail.appendLine("DATA: ${report.dataQuality.name}")
            report.setupScore?.let { detail.appendLine("SETUP SCORE: $it/100 (strength, not a win probability)") }
            report.entry?.let { detail.appendLine("ENTRY: ${it.quality.name}") }
            report.warnings.take(2).forEach { detail.appendLine("\u2022 $it") }
            if (status == AgentStatus.SETUP_DETECTED) {
                val life = agent.lifecycle
                detail.appendLine("BIAS: ${report.direction.name} (analytical, not a guarantee)")
                detail.appendLine("SIGNAL AGE: ${life.ageSeconds}s   VALID FOR: ${life.remainingSeconds}s")
            } else {
                detail.appendLine(report.headlineReason.ifEmpty { "Conditions reviewed." })
            }
            detail.append("MODE: ${agent.mode.name.replace('_', ' ')}")
        } else {
            detail.append(state.message ?: "Collecting price history…")
        }
        if (state.chartStatus.isNotBlank()) detail.append("\nCHART: ${state.chartStatus}")
        if (state.screenStatus != ScreenStatus.TRACKING && state.readerNote.isNotBlank()) detail.append("\nREADER: ${state.readerNote}")
        detailText = detail.toString().trimEnd()
        maybeAskAi(state)
        val shown = g
        val verdict = shown?.let { aiVerdicts[it.forOpenMs] }
        when {
            module.advisor == null -> { aiView.text = "AI: key nahi hai (Settings me add karo)"; aiView.setTextColor(MUTED) }
            shown == null -> { aiView.text = "AI: guess ka intezar"; aiView.setTextColor(MUTED) }
            verdict != null -> {
                val agrees = verdict.call == shown.label
                aiView.text = "AI: ${verdict.call} ${verdict.confidence}%" + (if (verdict.reason.isNotBlank()) " - ${verdict.reason}" else "") +
                    if (verdict.call == "SKIP") "" else if (agrees) "  (guess se agree)" else "  (guess se alag)"
                aiView.setTextColor(if (verdict.call == "SKIP") AMBER else if (agrees) GREEN else RED)
            }
            aiInFlight -> { aiView.text = "AI: soch raha hai..."; aiView.setTextColor(AMBER) }
            shown.strength != com.jarvis.assistant.quotex.agent.GuessStrength.STRONG -> { aiView.text = "AI: sirf STRONG guess par poochta hai"; aiView.setTextColor(MUTED) }
            else -> { aiView.text = "AI: pooch raha hun..."; aiView.setTextColor(MUTED) }
        }
        renderAuto()
        renderContent()
    }

    private fun renderContent() {
        if (root == null) return
        val state = lastState
        contentView.text = when (tab) {
            Tab.DETAILS -> {
                val agent = state.agent
                if (agent == null) {
                    detailText.ifBlank { state.message ?: "No analysis yet." }
                } else {
                    detailText + "\n\n" + ExplanationEngine.explain(agent.report) + "\n\nHISTORICAL EVIDENCE: " + agent.evidence.summary
                }
            }
            Tab.HISTORY -> historyText
            Tab.CHAT -> chatText
        }
    }

    private fun loadHistory() {
        scope.launch {
            historyText = withContext(Dispatchers.Default) {
                AgentNarrator.journalList(module.coordinator.journalLast(10))
            }
            renderContent()
        }
    }

    private fun setWindowFocusable(focusable: Boolean) {
        val view = root ?: return
        val current = params.flags
        params.flags = if (focusable) {
            current and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            current or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (params.flags != current) {
            try { windowManager.updateViewLayout(view, params) } catch (e: Exception) { }
        }
    }

    private fun updateLayout() {
        val view = root ?: return
        try { windowManager.updateViewLayout(view, params) } catch (e: Exception) { }
    }

    private fun removeOverlay() {
        val view = root ?: return
        try { windowManager.removeView(view) } catch (e: Exception) { }
        root = null
        expanded = false
    }

    override fun onDestroy() {
        removeOverlay()
        scope.cancel()
        super.onDestroy()
    }

    private inner class DragTouch(private val onTap: () -> Unit) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        val metrics = resources.displayMetrics
                        params.x = (startX + dx).coerceIn(0, maxOverlayX())
                        params.y = (startY + dy).coerceIn(0, (metrics.heightPixels - dp(60)).coerceAtLeast(0))
                        updateLayout()
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            return true
        }
    }

    companion object {
        private const val PANEL_WIDTH_DP = 240
        private const val PILL_WIDTH_DP = 160
        private const val ACTION_SHOW = "com.jarvis.assistant.quotex.overlay.SHOW"
        private const val ACTION_HIDE = "com.jarvis.assistant.quotex.overlay.HIDE"
        private val CYAN = Color.parseColor("#38BDF8")
        private val BG = Color.parseColor("#E60A0E14")
        private val MUTED = Color.parseColor("#94A3B8")
        private val GREEN = Color.parseColor("#4ADE80")
        private val AMBER = Color.parseColor("#FBBF24")
        private val BLUE = Color.parseColor("#60A5FA")
        private val RED = Color.parseColor("#F87171")
        private val DARK = Color.parseColor("#0A0E14")
        private val CARD = Color.parseColor("#1E293B")
        private val STROKE_DIM = Color.parseColor("#334155")
        private val NEUTRAL_FILL = Color.parseColor("#331E293B")
        private val UP_FILL = Color.parseColor("#33166534")
        private val DOWN_FILL = Color.parseColor("#337F1D1D")
        /** A guess counts as "ENTER NOW" only during the first 30% of a candle. */
        private const val ENTRY_WINDOW = 0.3
        /** Safety cap: the auto switch turns itself off after this many taps in one run. */
        private const val MAX_AUTO_TAPS = 5
        /** Minimum candles between two auto trades (no back-to-back trading). */
        private const val MIN_GAP_CANDLES = 3
        /** The run stops after this many wrong auto trades in a row. */
        private const val MAX_MISS_STREAK = 2
        private const val AI_MIN_CONFIDENCE = 60

        fun show(context: Context) {
            try {
                context.startService(Intent(context, QuotexOverlayService::class.java).setAction(ACTION_SHOW))
            } catch (e: Exception) {
                // Background start not allowed; the user can open the overlay from the Quotex screen.
            }
        }

        fun hide(context: Context) {
            try {
                context.startService(Intent(context, QuotexOverlayService::class.java).setAction(ACTION_HIDE))
            } catch (e: Exception) {
                // Not running.
            }
        }
    }
}
