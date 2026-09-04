package com.jarvis.assistant.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.ChatMessage
import com.jarvis.assistant.agent.AgentPlanner
import com.jarvis.assistant.ai.PromptBuilder
import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.LocalIntentRouter
import com.jarvis.assistant.command.describe
import com.jarvis.assistant.command.requiresConfirmation
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.search.needsWebSearch
import com.jarvis.assistant.voice.JarvisGlobalState
import com.jarvis.assistant.voice.SpeechEvent
import com.jarvis.assistant.voice.VoiceState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lets JARVIS work from ANY app: a small floating mic bubble stays on top of whatever the
 * user is doing. Tapping it listens, thinks, and acts using exactly the same AI/command
 * pipeline as the in-app chat — nothing here is a separate/fake pathway. It only ever runs
 * because the user explicitly turned it on in Settings and granted the "display over other
 * apps" permission Android requires for this; it can be turned off from Settings at any time.
 *
 * If the user also turns on "Wake Word" in Settings, this service additionally runs a
 * continuous listen-for-"Jarvis" loop (see [startWakeWordLoop]) so the user can speak without
 * tapping anything first. The persistent notification stays visible the whole time this is
 * active — Android requires that for any app using the microphone in the background, and it
 * is JARVIS's only way of telling the user "I can hear you right now."
 *
 * Actions that need confirmation are asked out loud ("Send this to Ali? Say yes or no.") and
 * only carried out on a clear spoken "yes" — a floating bubble has no dialog of its own, so a
 * genuinely unclear answer falls back to opening JARVIS so the user can approve it there instead
 * of JARVIS guessing wrong on something sensitive.
 */
class OverlayService : Service() {

    private lateinit var container: AppContainer
    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var conversationId: Long? = null
    private var isBusy = false
    private var wakeWordJob: Job? = null

    /** A command awaiting a spoken "haan"/"yes" or "nahi"/"no" from the user. */
    private var pendingConfirmation: JarvisCommand? = null
    private var pendingPlan: List<JarvisCommand>? = null
    private val agentPlanner = AgentPlanner()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        container = (applicationContext as JarvisApplication).container
        startForegroundNotification()
        addBubble()
        startWakeWordLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeWordJob?.cancel()
        serviceScope.cancel()
        container.voiceActivityDetector.stop()
        container.speechToTextManager.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    // ------------------------------------------------------------------------------ wake word

    /**
     * Runs for the whole life of the service. When the user has turned "Always listening" on
     * in Settings, this repeatedly listens for short bursts of speech and checks each one for
     * the wake word ("Jarvis"). It backs off whenever the bubble is busy with a manual tap, and
     * re-checks the Settings toggle on every loop so turning it off takes effect within ~1s
     * without needing to restart the service.
     *
     * Note: unlike a dedicated low-power wake-word engine, this uses the same cloud/system
     * speech recognizer as the rest of the app, restarted in a loop — so it uses more battery
     * and data than a true offline wake-word chip would. That trade-off is what makes hands-free
     * activation possible without adding a new native dependency.
     */
    private fun startWakeWordLoop() {
        wakeWordJob = serviceScope.launch {
            while (isActive) {
                if (!container.securePrefs.wakeWordEnabled || isBusy) {
                    delay(700)
                    continue
                }
                var heard: String? = null
                runCatching {
                    container.speechToTextManager.listen(languageTag = null).collect { event ->
                        if (event is SpeechEvent.FinalResult) heard = event.text
                    }
                }
                if (isBusy || !container.securePrefs.wakeWordEnabled) continue

                val afterWakeWord = heard?.let { extractAfterWakeWord(it) }
                if (afterWakeWord != null) {
                    isBusy = true
                    try {
                        if (afterWakeWord.isBlank()) {
                            // Just "Jarvis" was said with nothing after it — listen once more
                            // for the actual request, same as tapping the bubble.
                            runVoiceTurn()
                        } else {
                            var bargedIn = processCommand(afterWakeWord)
                            var depth = 0
                            while (bargedIn && depth < 4) {
                                bargedIn = false
                                setState(VoiceState.LISTENING)
                                var followUp: String? = null
                                container.speechToTextManager.listen(languageTag = null).collect { event ->
                                    if (event is SpeechEvent.FinalResult) followUp = event.text
                                }
                                val t = followUp?.takeIf { it.isNotBlank() } ?: break
                                bargedIn = processCommand(t)
                                depth++
                            }
                        }
                    } finally {
                        isBusy = false
                        setState(VoiceState.IDLE)
                    }
                }
                delay(250)
            }
        }
    }

    /** Returns the text after the wake word if it was said, or null if it wasn't heard at all. */
    private fun extractAfterWakeWord(text: String): String? {
        val lower = text.lowercase()
        for (wakeWord in WAKE_WORDS) {
            val idx = lower.indexOf(wakeWord)
            if (idx >= 0) {
                return text.substring(idx + wakeWord.length).trim().trimStart(',', '.', ':', '،', '-')
            }
        }
        return null
    }

    // ---------------------------------------------------------------- notification / lifecycle

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "JARVIS Background", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val contentText = if (container.securePrefs.wakeWordEnabled) {
            "Say \"Jarvis\" anytime, or tap the floating icon."
        } else {
            "Tap the floating icon to talk to JARVIS from any app."
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is running in the background")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------------- bubble view

    private fun addBubble() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val view = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setPadding(28, 28, 28, 28)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC0A0E14.toInt())
                setStroke(5, IDLE_COLOR)
            }
        }
        val lp = WindowManager.LayoutParams(
            150,
            150,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    lp.x = initialX + dx
                    lp.y = initialY + dy
                    runCatching { windowManager.updateViewLayout(view, lp) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onBubbleTapped()
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(view, lp) }
        bubbleView = view
        layoutParams = lp
    }

    private fun setBubbleColor(color: Int) {
        (bubbleView?.background as? GradientDrawable)?.setStroke(6, color)
    }

    /** Updates both the floating bubble's ring color and the shared state the live wallpaper
     * (and anything else in the app) reads to know what JARVIS is doing right now. */
    private fun setState(voiceState: VoiceState) {
        val color = when (voiceState) {
            VoiceState.LISTENING -> LISTENING_COLOR
            VoiceState.THINKING -> THINKING_COLOR
            VoiceState.SPEAKING -> SPEAKING_COLOR
            VoiceState.ERROR, VoiceState.IDLE -> IDLE_COLOR
        }
        setBubbleColor(color)
        JarvisGlobalState.update(voiceState)
    }

    // -------------------------------------------------------------------------- voice pipeline

    private fun onBubbleTapped() {
        if (isBusy) {
            // Tapping again mid-session cancels it — a manual "never mind".
            container.textToSpeechManager.stop()
            container.voiceActivityDetector.stop()
            container.speechToTextManager.cancel()
            pendingConfirmation = null
            pendingPlan = null
            container.taskEngine.cancel()
            isBusy = false
            setState(VoiceState.IDLE)
            return
        }
        isBusy = true
        serviceScope.launch {
            runVoiceTurn()
            isBusy = false
            setState(VoiceState.IDLE)
        }
    }

    private suspend fun runVoiceTurn(depth: Int = 0) {
        if (depth > 4) return
        setState(VoiceState.LISTENING)
        var finalText: String? = null
        container.speechToTextManager.listen(languageTag = null).collect { event ->
            if (event is SpeechEvent.FinalResult) finalText = event.text
        }
        val text = finalText?.takeIf { it.isNotBlank() } ?: return
        val bargedIn = processCommand(text)
        if (bargedIn) runVoiceTurn(depth + 1)
    }

    /** Returns true if the user started talking again while JARVIS was replying (barge-in). */
    private suspend fun processCommand(text: String): Boolean {
        // If JARVIS just asked "should I do this?", this utterance is the answer — never
        // route it through the AI or treat it as a new, unrelated command.
        pendingConfirmation?.let { pending -> return handlePendingConfirmation(pending, text) }
        pendingPlan?.let { plan -> return handlePendingPlanConfirmation(plan, text) }

        // Routing-critical commands (scroll, back, home, search-here, open app, stop) are
        // matched locally first: instant, always acts on the current foreground app, never
        // opens JARVIS, and works even with no AI configured.
        LocalIntentRouter.match(text)?.let { command ->
            setState(VoiceState.THINKING)
            val convId = ensureConversation()
            container.conversationRepository.addMessage(convId, "user", text)
            if (command is JarvisCommand.StopAction) container.taskEngine.cancel()
            val result = container.actionExecutor.execute(command)
            val reply = when (result) {
                is ExecutionResult.Success -> command.describe().removeSuffix(".").removeSuffix("?")
                is ExecutionResult.Failure -> result.message
            }
            container.conversationRepository.addMessage(convId, "assistant", reply)
            return speakWithBargeIn(reply)
        }

        setState(VoiceState.THINKING)
        val convId = ensureConversation()
        container.conversationRepository.addMessage(convId, "user", text)

        var searchContext = ""
        if (needsWebSearch(text)) {
            container.webSearchService.search(text).onSuccess { searchContext = it }
        }
        val memoryContext = container.memoryRepository.getAllAsPromptContext()
        val phoneContext = container.phoneContextEngine.snapshot().toPromptString()
        val appRegistry = container.appRegistry.compactPrompt()
        val systemPrompt = PromptBuilder.systemPrompt(memoryContext, languageHint = text, phoneContext = phoneContext, appRegistry = appRegistry) +
            if (searchContext.isNotBlank()) "\n\nRecent web search results for this question:\n$searchContext" else ""
        val history = container.conversationRepository.getHistory(convId)
            .takeLast(20)
            .map { ChatMessage(role = if (it.role == "user") "user" else "assistant", content = it.content) }

        var interrupted = false
        container.aiService.send(history, systemPrompt).fold(
            onSuccess = { result ->
                container.conversationRepository.addMessage(convId, "assistant", result.replyText)
                val command = CommandEngine.parse(result.commandJson)
                val plan = agentPlanner.parsePlan(result.commandJson)
                interrupted = when {
                    plan != null -> {
                        if (plan.actions.any { container.confirmationManager.required(it) }) {
                            pendingPlan = plan.actions
                            speakWithBargeIn("I have a multi-step task ready. ${plan.actions.size} actions. Say yes or no.")
                        } else {
                            val task = container.taskEngine.run(text, plan.actions)
                            val reply = if (task.status.name == "COMPLETED") "Task completed." else "I couldn't complete the task."
                            speakWithBargeIn(reply)
                        }
                    }
                    command is JarvisCommand.ReadScreen -> {
                        val screenResult = container.actionExecutor.execute(command)
                        val screenText = when (screenResult) {
                            is ExecutionResult.Success -> screenResult.message
                            is ExecutionResult.Failure -> screenResult.message
                        }
                        container.conversationRepository.addMessage(convId, "assistant", screenText)
                        speakWithBargeIn(screenText)
                    }
                    command != null && !container.confirmationManager.required(command) -> {
                        container.actionExecutor.execute(command)
                        speakWithBargeIn(result.replyText)
                    }
                    command != null -> {
                        // Sensitive action — ask out loud instead of silently guessing or
                        // forcing the user to leave what they're doing to approve it.
                        pendingConfirmation = command
                        speakWithBargeIn("${command.describe()} Say yes or no.")
                    }
                    else -> speakWithBargeIn(result.replyText)
                }
            },
            onFailure = {
                interrupted = speakWithBargeIn("Sorry, something went wrong.")
            }
        )
        return interrupted
    }

    /** Handles the user's spoken answer to a pending "should I do this?" question. */
    private suspend fun handlePendingConfirmation(pending: JarvisCommand, answerText: String): Boolean {
        val convId = ensureConversation()
        val answer = LocalIntentRouter.parseConfirmation(answerText)
        return when (answer) {
            true -> {
                pendingConfirmation = null
                setState(VoiceState.THINKING)
                val result = container.actionExecutor.execute(pending)
                val reply = when (result) {
                    is ExecutionResult.Success -> if (pending is JarvisCommand.SendWhatsAppMessage) {
                        // The message is now typed and ready — send it, then confirm out loud.
                        container.actionExecutor.execute(JarvisCommand.SendPendingMessage)
                        "Sent."
                    } else "Done."
                    is ExecutionResult.Failure -> result.message
                }
                container.conversationRepository.addMessage(convId, "assistant", reply)
                speakWithBargeIn(reply)
            }
            false -> {
                pendingConfirmation = null
                container.conversationRepository.addMessage(convId, "assistant", "Cancelled.")
                speakWithBargeIn("Cancelled.")
            }
            null -> {
                // Genuinely unclear — safest fallback is to hand off to the app rather than
                // guess wrong on a sensitive action.
                pendingConfirmation = null
                val said = speakWithBargeIn("I didn't catch that — please open JARVIS to approve it.")
                openAppForConfirmation()
                said
            }
        }
    }

    private suspend fun handlePendingPlanConfirmation(plan: List<JarvisCommand>, answerText: String): Boolean {
        val answer = LocalIntentRouter.parseConfirmation(answerText)
        return when (answer) {
            true -> {
                pendingPlan = null
                setState(VoiceState.THINKING)
                val task = container.taskEngine.run("confirmed plan", plan)
                val reply = when (task.status) {
                    com.jarvis.assistant.agent.TaskStatus.COMPLETED -> "Task completed."
                    com.jarvis.assistant.agent.TaskStatus.CANCELLED -> "Cancelled."
                    else -> "I couldn't complete the task."
                }
                speakWithBargeIn(reply)
            }
            false -> {
                pendingPlan = null
                speakWithBargeIn("Cancelled.")
            }
            null -> speakWithBargeIn("Please say yes or no.")
        }
    }

    private suspend fun speakWithBargeIn(text: String): Boolean {
        setState(VoiceState.SPEAKING)
        val prefs = container.securePrefs
        container.textToSpeechManager.setRate(prefs.speechRate)
        val voiceName = prefs.voiceName ?: container.textToSpeechManager.autoSelectMaleVoice()?.also { prefs.voiceName = it }
        container.textToSpeechManager.setVoice(voiceName)

        val interrupted = AtomicBoolean(false)
        container.voiceActivityDetector.start {
            if (interrupted.compareAndSet(false, true)) container.textToSpeechManager.stop()
        }
        container.textToSpeechManager.speak(text)
        container.voiceActivityDetector.stop()
        return interrupted.get()
    }

    private suspend fun ensureConversation(): Long {
        conversationId?.let { return it }
        val id = container.conversationRepository.createConversation("Background session")
        conversationId = id
        return id
    }

    private fun openAppForConfirmation() {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    companion object {
        private const val CHANNEL_ID = "jarvis_background"
        private const val NOTIFICATION_ID = 4201
        private val IDLE_COLOR = 0xFF38BDF8.toInt()
        private val LISTENING_COLOR = 0xFFFF6B6B.toInt()
        private val THINKING_COLOR = 0xFF7C6BFF.toInt()
        private val SPEAKING_COLOR = 0xFF34D399.toInt()

        /** Checked case-insensitively against each heard phrase; add more variants if needed. */
        private val WAKE_WORDS = listOf("hey jarvis", "jarvis", "جارویس", "जार्विस", "ہے جارویس")
    }
}
