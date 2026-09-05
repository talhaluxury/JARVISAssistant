package com.jarvis.assistant.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * When Background JARVIS / Wake Word is enabled in Settings, this service runs a
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
        installAutomationVoiceFeedback()
        startWakeWordLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        wakeWordJob?.cancel()
        com.jarvis.assistant.accessibility.JarvisAccessibilityService.onStepEvent = null
        serviceScope.cancel()
        container.voiceActivityDetector.stop()
        container.speechToTextManager.cancel()
    }

    /** Speaks concise live progress for multi-step Accessibility automation. */
    private fun installAutomationVoiceFeedback() {
        com.jarvis.assistant.accessibility.JarvisAccessibilityService.onStepEvent = { current, total, label, success ->
            serviceScope.launch {
                when (success) {
                    null -> speakStatus("Step $current of $total: ${friendlyStep(label)}")
                    true -> {
                        // Keep successful step feedback short; the final task result is spoken separately.
                        if (current == total) speakStatus("Step $current of $total complete.")
                    }
                    false -> speakStatus("Step $current of $total failed: ${friendlyStep(label)}. I couldn't do that step.")
                }
            }
        }
    }

    private suspend fun speakStatus(text: String) {
        setState(VoiceState.SPEAKING)
        val prefs = container.securePrefs
        container.textToSpeechManager.setRate(prefs.speechRate)
        val voiceName = prefs.voiceName ?: container.textToSpeechManager.autoSelectMaleVoice()?.also { prefs.voiceName = it }
        container.textToSpeechManager.setVoice(voiceName)
        container.textToSpeechManager.speak(text)
        if (!isBusy) setState(VoiceState.IDLE)
    }

    private fun friendlyStep(label: String): String = label
        .replace("TAP ", "tap ", ignoreCase = true)
        .replace("TYPE ", "type ", ignoreCase = true)
        .replace("LONG_PRESS ", "long-press ", ignoreCase = true)
        .replace("SCROLL", "scroll", ignoreCase = true)
        .replace("BACK", "go back", ignoreCase = true)
        .replace("HOME", "go home", ignoreCase = true)
        .replace("SUBMIT", "submit the field", ignoreCase = true)
        .replace("TAP FIRST RESULT", "open the first result", ignoreCase = true)
        .replace("WAIT", "wait", ignoreCase = true)

    // ------------------------------------------------------------------------------ wake word

    /**
     * Runs for the whole life of the service. When the user has turned "Always listening" on
     * in Settings, this repeatedly listens for short bursts of speech and checks each one for
     * the wake word ("Jarvis"). It backs off whenever JARVIS is busy with another task, and
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
            var permissionWarningSpoken = false
            var consecutiveSpeechErrors = 0
            while (isActive) {
                if (!container.securePrefs.wakeWordEnabled || isBusy) {
                    delay(700)
                    continue
                }

                // Without RECORD_AUDIO, every listen() attempt below fails instantly and
                // forever — that used to mean total, permanent silence with zero indication of
                // why. Say it once (not every loop) and back off hard instead of hammering a
                // recognizer that can never succeed.
                if (ContextCompat.checkSelfPermission(this@OverlayService, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    if (!permissionWarningSpoken) {
                        permissionWarningSpoken = true
                        isBusy = true
                        try {
                            speakStatus("I can't hear anything — microphone permission is missing. Please grant it for JARVIS, then try again.")
                        } finally {
                            isBusy = false
                        }
                    }
                    delay(5000)
                    continue
                }

                var heard: String? = null
                var sawError = false
                runCatching {
                    container.speechToTextManager.listen(languageTag = null).collect { event ->
                        when (event) {
                            is SpeechEvent.FinalResult -> heard = event.text
                            is SpeechEvent.Error -> sawError = true
                            else -> {}
                        }
                    }
                }.onFailure { sawError = true }

                if (heard != null) {
                    consecutiveSpeechErrors = 0
                } else if (sawError) {
                    consecutiveSpeechErrors++
                    // A handful of "no speech"/timeout errors in a row is completely normal for
                    // continuous listening (most of the time nobody's talking to it). A long,
                    // unbroken run instead points to something actually wrong (recognizer stuck,
                    // no network for a cloud-backed recognizer, engine busy repeatedly) — say so
                    // once so the user isn't left wondering why nothing ever happens.
                    if (consecutiveSpeechErrors == 15 && !isBusy) {
                        isBusy = true
                        try {
                            speakStatus("I'm having trouble hearing right now — you can still open JARVIS directly to give a command.")
                        } finally {
                            isBusy = false
                        }
                        consecutiveSpeechErrors = 0
                    }
                }

                if (isBusy || !container.securePrefs.wakeWordEnabled) continue

                val afterWakeWord = heard?.let { extractAfterWakeWord(it) }
                if (afterWakeWord != null) {
                    isBusy = true
                    try {
                        if (afterWakeWord.isBlank()) {
                            // Just "Jarvis" was said with nothing after it — listen once more
                            // for the actual request.
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
            "Always listening for \"Jarvis\". No floating microphone."
        } else {
            "Background JARVIS is running. Enable Wake Word for hands-free use."
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

    // -------------------------------------------------------------------------- voice pipeline

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
            val currentApp = container.phoneContextEngine.snapshot().appLabel
            speakStatus("I will ${command.describe().removeSuffix(".").removeSuffix("?")}${currentApp?.let { " on $it" } ?: ""}.")
            val result = container.actionExecutor.execute(command)
            val reply = when (result) {
                is ExecutionResult.Success -> "Done. ${result.message}"
                is ExecutionResult.Failure -> "Command failed: ${result.message} ${failureGuidance(result.message)}"
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
                            speakStatus("I will perform ${plan.actions.size} steps and verify each one.")
                            val task = container.taskEngine.run(text, plan.actions) { update ->
                                if (update.failedSteps.isNotEmpty() && update.currentStep > 0) {
                                    // The TaskEngine has already bounded retries; the final failure is
                                    // announced below with a useful next step instead of looping.
                                }
                            }
                            val reply = when (task.status) {
                                com.jarvis.assistant.agent.TaskStatus.COMPLETED -> "Task completed successfully."
                                com.jarvis.assistant.agent.TaskStatus.CANCELLED -> "Task cancelled."
                                else -> {
                                    val detail = task.failedSteps.lastOrNull()?.substringAfter(": ")
                                    "Command failed${detail?.let { ": $it" } ?: ""}. ${failureGuidance(detail ?: "The required screen element was not available.")}"
                                }
                            }
                            speakWithBargeIn(reply)
                        }
                    }
                    command is JarvisCommand.ReadScreen || command is JarvisCommand.ReadNotifications -> {
                        val screenResult = container.actionExecutor.execute(command)
                        val screenText = when (screenResult) {
                            is ExecutionResult.Success -> screenResult.message
                            is ExecutionResult.Failure -> screenResult.message
                        }
                        container.conversationRepository.addMessage(convId, "assistant", screenText)
                        speakWithBargeIn(screenText)
                    }
                    command != null && !container.confirmationManager.required(command) -> {
                        speakStatus("I will ${command.describe().removeSuffix(".").removeSuffix("?")}.")
                        val execution = container.actionExecutor.execute(command)
                        val reply = when (execution) {
                            is ExecutionResult.Success -> result.replyText.ifBlank { "Done." }
                            is ExecutionResult.Failure -> "Command failed: ${execution.message} ${failureGuidance(execution.message)}"
                        }
                        container.conversationRepository.addMessage(convId, "assistant", reply)
                        speakWithBargeIn(reply)
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
                        when (val sendResult = container.actionExecutor.execute(JarvisCommand.SendPendingMessage)) {
                            is ExecutionResult.Success -> "Sent."
                            is ExecutionResult.Failure -> "Command failed while sending: ${sendResult.message} ${failureGuidance(sendResult.message)}"
                        }
                    } else "Done."
                    is ExecutionResult.Failure -> "Command failed: ${result.message} ${failureGuidance(result.message)}"
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

    private fun failureGuidance(message: String): String {
        val m = message.lowercase()
        return when {
            "accessibility" in m || "screen automation" in m -> "Please enable JARVIS Accessibility and Screen automation in Settings, then try again."
            "isn't installed" in m || "not installed" in m -> "Please check the app name or install that app first."
            "permission" in m -> "Please grant the required Android permission and try again."
            "search" in m -> "Please open the target app and make sure its search field is visible."
            "send" in m -> "Please make sure the correct chat is open and the Send button is visible."
            "volume" in m -> "Please check that Android allows volume control for the selected audio stream."
            else -> "I stopped safely instead of making random taps. You can retry after checking the current screen."
        }
    }

    private suspend fun ensureConversation(): Long {
        conversationId?.let { return it }
        val id = container.conversationRepository.createConversation("Background session")
        conversationId = id
        return id
    }

    /** Publishes the current voice state process-wide (drives the live-wallpaper orb animation
     * via [JarvisGlobalState]) — this was called all over this file but never actually defined,
     * so the whole service failed to compile. */
    private fun setState(state: VoiceState) {
        JarvisGlobalState.update(state)
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
