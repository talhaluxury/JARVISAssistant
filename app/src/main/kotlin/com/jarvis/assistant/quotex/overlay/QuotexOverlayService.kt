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
import com.jarvis.assistant.quotex.QuotexModule
import com.jarvis.assistant.quotex.QuotexUiState
import com.jarvis.assistant.quotex.voice.QuotexNarrator
import com.jarvis.assistant.trading.QuotexDecision
import com.jarvis.assistant.wingo.ScreenStatus
import com.jarvis.assistant.wingo.domain.Fmt
import com.jarvis.assistant.wingo.domain.Signal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Floating JARVIS HUD for the Quotex analyzer with a small chat box. Information only: nothing on it
 * acts on the trading app. FLAG_SECURE keeps the HUD out of the screen capture.
 */
class QuotexOverlayService : Service() {

    private enum class Tab { ANALYSIS, CHAT }

    private lateinit var windowManager: WindowManager
    private lateinit var module: QuotexModule
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var root: LinearLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false
    private var tab = Tab.ANALYSIS
    private var chatText = "Ask about the current chart. Answers come from stored data only."
    private var lastState = QuotexUiState()

    private lateinit var pill: TextView
    private lateinit var panel: LinearLayout
    private lateinit var assetView: TextView
    private lateinit var decisionView: TextView
    private lateinit var detailView: TextView
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
            layoutParams = LinearLayout.LayoutParams(dp(280), LinearLayout.LayoutParams.WRAP_CONTENT)
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

        assetView = label("ASSET —", 10f, MUTED)
        decisionView = label("WAIT", 22f, MUTED, bold = true)
        detailView = label("", 11f, Color.WHITE)
        panel.addView(assetView)
        panel.addView(decisionView)
        panel.addView(detailView)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(4))
        }
        for ((t, name) in listOf(Tab.ANALYSIS to "ANALYSIS", Tab.CHAT to "CHAT")) {
            val tv = label(name, 11f, MUTED, bold = true).apply {
                setPadding(0, 0, dp(14), 0)
                setOnClickListener { showTab(t) }
            }
            tabViews[t] = tv
            tabs.addView(tv)
        }
        panel.addView(tabs)

        contentView = label("", 10f, Color.WHITE)
        val scroll = ScrollView(this).apply {
            addView(contentView)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(130))
        }
        panel.addView(scroll)

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
        showTab(Tab.ANALYSIS)
        render(lastState)
    }

    private fun toggle() {
        expanded = !expanded
        panel.visibility = if (expanded) View.VISIBLE else View.GONE
        pill.visibility = if (expanded) View.GONE else View.VISIBLE
        if (!expanded) setWindowFocusable(false)
        updateLayout()
    }

    private fun showTab(newTab: Tab) {
        tab = newTab
        for ((t, view) in tabViews) view.setTextColor(if (t == newTab) CYAN else MUTED)
        chatRow.visibility = if (newTab == Tab.CHAT) View.VISIBLE else View.GONE
        if (newTab != Tab.CHAT) setWindowFocusable(false)
        renderContent()
    }

    private fun ask(question: String) {
        if (question.isBlank()) return
        setWindowFocusable(false)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        input.setText("")
        chatText = "…"
        showTab(Tab.CHAT)
        scope.launch {
            chatText = try {
                withContext(Dispatchers.Default) { module.chat.answer(question) }
            } catch (e: Exception) {
                "I could not answer that."
            }
            renderContent()
        }
    }

    private fun render(state: QuotexUiState) {
        lastState = state
        if (root == null) return
        val p = state.prediction
        val signal = p?.signal ?: Signal.WAIT
        val decision = p?.decision ?: QuotexDecision.WAIT
        val shown = signal != Signal.WAIT && decision != QuotexDecision.WAIT
        val color = when (signal) {
            Signal.HIGH -> GREEN
            Signal.MEDIUM -> CYAN
            Signal.LOW -> AMBER
            Signal.WAIT -> MUTED
        }
        val word = if (decision == QuotexDecision.CALL) "CALL" else "PUT"

        pill.text = when {
            !state.monitorOn -> "◉ QUOTEX · OFF"
            state.screenStatus == ScreenStatus.NOT_DETECTED -> "◉ QUOTEX · NO CHART"
            p != null && shown -> "◉ $word ${Fmt.pct(p.confidence)}"
            else -> "◉ WAIT"
        }
        pill.setTextColor(color)

        assetView.text = "${state.asset ?: "ASSET —"}  ${state.lastPrice?.toString() ?: ""}"
        decisionView.text = if (shown) word else "WAIT"
        decisionView.setTextColor(color)

        val detail = StringBuilder()
        if (p != null && shown) {
            detail.appendLine("CONFIDENCE  ${Fmt.pct(p.confidence)}")
            detail.appendLine("SIGNAL      ${signal.name}")
            detail.appendLine("MODELS      ${p.agree}/${p.totalModels} agree")
            detail.appendLine("EXPIRY      ${state.expirySeconds}s")
        } else {
            detail.appendLine(state.message ?: p?.waitReason ?: "Collecting price history…")
        }
        val bt = state.backtest?.independentCalls
        val acc = bt?.accuracy
        if (bt != null && acc != null) {
            detail.appendLine("BACKTEST    ${Fmt.pct(acc, 1)} (n=${bt.calls}, break-even ${Fmt.pct(state.breakEven, 1)})")
        } else {
            detail.appendLine("BACKTEST    n/a")
        }
        val last = state.lastOutcome
        if (last != null) {
            detail.append("LAST        ${if (last.correct) "✓ CORRECT" else "✕ WRONG"}")
        }
        detailView.text = detail.toString().trimEnd()
        renderContent()
    }

    private fun renderContent() {
        if (root == null) return
        val state = lastState
        contentView.text = when (tab) {
            Tab.ANALYSIS -> {
                val p = state.prediction
                if (p == null) {
                    state.message ?: "No analysis yet."
                } else {
                    QuotexNarrator.why(state) + "\n\n" + p.edge.summary + "\n" + (state.backtest?.verdict ?: "")
                }
            }
            Tab.CHAT -> chatText
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
                        params.x = (startX + dx).coerceIn(0, (metrics.widthPixels - dp(60)).coerceAtLeast(0))
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
        private const val ACTION_SHOW = "com.jarvis.assistant.quotex.overlay.SHOW"
        private const val ACTION_HIDE = "com.jarvis.assistant.quotex.overlay.HIDE"
        private val CYAN = Color.parseColor("#38BDF8")
        private val BG = Color.parseColor("#E60A0E14")
        private val MUTED = Color.parseColor("#94A3B8")
        private val GREEN = Color.parseColor("#4ADE80")
        private val AMBER = Color.parseColor("#FBBF24")

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
