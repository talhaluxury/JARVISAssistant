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
import com.jarvis.assistant.ai.PromptBuilder
import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.requiresConfirmation
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.search.needsWebSearch
import com.jarvis.assistant.voice.SpeechEvent
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lets JARVIS work from ANY app: a small floating mic bubble stays on top of whatever the
 * user is doing. Tapping it listens, thinks, and acts using exactly the same AI/command
 * pipeline as the in-app chat — nothing here is a separate/fake pathway. It only ever runs
 * because the user explicitly turned it on in Settings and granted the "display over other
 * apps" permission Android requires for this; it can be turned off from Settings at any time.
 *
 * Actions that would normally ask for confirmation are NOT auto-approved here — instead the
 * user is told to open JARVIS to approve them, since a floating bubble has no safe way to
 * show a real confirmation dialog of its own.
 */
class OverlayService : Service() {

    private lateinit var container: AppContainer
    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var conversationId: Long? = null
    private var isBusy = false

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        container = (applicationContext as JarvisApplication).container
        startForegroundNotification()
        addBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        container.voiceActivityDetector.stop()
        container.speechToTextManager.cancel()
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
    }

    // ---------------------------------------------------------------- notification / lifecycle

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "JARVIS Background", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS is running in the background")
            .setContentText("Tap the floating icon to talk to JARVIS from any app.")
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

    // -------------------------------------------------------------------------- voice pipeline

    private fun onBubbleTapped() {
        if (isBusy) {
            // Tapping again mid-session cancels it — a manual "never mind".
            container.textToSpeechManager.stop()
            container.voiceActivityDetector.stop()
            container.speechToTextManager.cancel()
            isBusy = false
            setBubbleColor(IDLE_COLOR)
            return
        }
        isBusy = true
        serviceScope.launch {
            runVoiceTurn()
            isBusy = false
            setBubbleColor(IDLE_COLOR)
        }
    }

    private suspend fun runVoiceTurn(depth: Int = 0) {
        if (depth > 4) return
        setBubbleColor(LISTENING_COLOR)
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
        setBubbleColor(THINKING_COLOR)
        val convId = ensureConversation()
        container.conversationRepository.addMessage(convId, "user", text)

        var searchContext = ""
        if (needsWebSearch(text)) {
            container.webSearchService.search(text).onSuccess { searchContext = it }
        }
        val memoryContext = container.memoryRepository.getAllAsPromptContext()
        val systemPrompt = PromptBuilder.systemPrompt(memoryContext, languageHint = text) +
            if (searchContext.isNotBlank()) "\n\nRecent web search results for this question:\n$searchContext" else ""
        val history = container.conversationRepository.getHistory(convId)
            .takeLast(20)
            .map { ChatMessage(role = if (it.role == "user") "user" else "assistant", content = it.content) }

        var interrupted = false
        container.aiService.send(history, systemPrompt).fold(
            onSuccess = { result ->
                container.conversationRepository.addMessage(convId, "assistant", result.replyText)
                val command = CommandEngine.parse(result.commandJson)
                interrupted = when {
                    command is JarvisCommand.ReadScreen -> {
                        val screenResult = container.actionExecutor.execute(command)
                        val screenText = when (screenResult) {
                            is ExecutionResult.Success -> screenResult.message
                            is ExecutionResult.Failure -> screenResult.message
                        }
                        container.conversationRepository.addMessage(convId, "assistant", screenText)
                        speakWithBargeIn(screenText)
                    }
                    command != null && !command.requiresConfirmation() && !container.securePrefs.confirmEveryAction -> {
                        container.actionExecutor.execute(command)
                        speakWithBargeIn(result.replyText)
                    }
                    command != null -> {
                        // Sensitive action — hand off to the app rather than guessing or
                        // silently skipping the user's request.
                        val said = speakWithBargeIn("That needs your confirmation — please open JARVIS to approve it.")
                        openAppForConfirmation()
                        said
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

    private suspend fun speakWithBargeIn(text: String): Boolean {
        setBubbleColor(SPEAKING_COLOR)
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
    }
}
