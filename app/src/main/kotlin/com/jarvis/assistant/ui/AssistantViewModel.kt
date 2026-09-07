package com.jarvis.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.agent.FailureReason
import com.jarvis.assistant.agent.IntentConfidence
import com.jarvis.assistant.ai.ChatMessage
import com.jarvis.assistant.ai.PromptBuilder
import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.LocalIntentRouter
import com.jarvis.assistant.command.describe
import com.jarvis.assistant.command.requiresConfirmation
import com.jarvis.assistant.data.local.db.entity.ConversationEntity
import com.jarvis.assistant.data.local.db.entity.MessageEntity
import com.jarvis.assistant.search.needsWebSearch
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.voice.SpeechEvent
import com.jarvis.assistant.voice.VoiceState
import com.jarvis.assistant.hud.HudCommandExecutor
import com.jarvis.assistant.hud.JarvisHudState
import com.jarvis.assistant.hud.WallpaperEventBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

data class PendingCommand(val command: JarvisCommand, val confirmationText: String)
data class PendingPlan(val actions: List<JarvisCommand>, val confirmationText: String)
data class PendingClarification(val question: String)

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as JarvisApplication).container

    /** Narrow, read-only access for composables that need prefs for something outside chat
     * flow (e.g. the Home screen's low-battery notification check) without exposing the whole
     * container. */
    fun securePrefsForNotifications() = container.securePrefs

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _statusText = MutableStateFlow("SYSTEM READY")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse.asStateFlow()

    private val _pendingCommand = MutableStateFlow<PendingCommand?>(null)
    val pendingCommand: StateFlow<PendingCommand?> = _pendingCommand.asStateFlow()

    private val _pendingPlan = MutableStateFlow<PendingPlan?>(null)
    val pendingPlan: StateFlow<PendingPlan?> = _pendingPlan.asStateFlow()

    // #49 CONFIDENCE — surfaced when the Intent Engine can't safely resolve an ambiguous,
    // consequential request on its own; the next thing the user types is treated as the answer.
    private val _pendingClarification = MutableStateFlow<PendingClarification?>(null)
    val pendingClarification: StateFlow<PendingClarification?> = _pendingClarification.asStateFlow()

    private val _isOffline = MutableStateFlow(!NetworkMonitor.isOnline(application))
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    /** A short rolling console of real system events — what JARVIS actually just did. */
    private val _commandLog = MutableStateFlow<List<String>>(emptyList())
    val commandLog: StateFlow<List<String>> = _commandLog.asStateFlow()

    // #46 CONVERSATIONAL INTELLIGENCE / #53 INTERRUPTIONS — session-only state, never persisted:
    // what to re-run for "phir se karo" / RETRY, and whether a plan is currently pausable.
    private var lastUserRequestText: String? = null
    @Volatile private var hasActiveTask = false

    private fun log(line: String) {
        _commandLog.value = (_commandLog.value + line).takeLast(6)
    }

    init {
        // Real, live progress from the Accessibility Service as it works through an AUTOMATE
        // sequence — not simulated text, this fires exactly when a step actually runs.
        JarvisAccessibilityService.onStepEvent = { current, total, label, success ->
            val tag = when (success) {
                true -> "OK"
                false -> "SKIPPED"
                null -> "..."
            }
            log("TASK $current/$total  $label  $tag")
        }
    }

    private val _currentConversationId = MutableStateFlow<Long?>(null)
    val currentConversationId: StateFlow<Long?> = _currentConversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationEntity>>(emptyList())
    val conversations: StateFlow<List<ConversationEntity>> = _conversations.asStateFlow()

    init {
        currentConversationId.flatMapLatest { id ->
            if (id == null) MutableStateFlow(emptyList()) else container.conversationRepository.observeMessages(id)
        }.onEach { _messages.value = it }.launchIn(viewModelScope)

        container.conversationRepository.observeConversations()
            .onEach { _conversations.value = it }
            .launchIn(viewModelScope)
    }

    fun refreshConnectivity() {
        _isOffline.value = !NetworkMonitor.isOnline(getApplication())
    }

    fun startNewConversation() {
        _currentConversationId.value = null
        _messages.value = emptyList()
        _lastResponse.value = ""
    }

    fun openConversation(id: Long) {
        _currentConversationId.value = id
    }

    fun deleteConversation(conversation: ConversationEntity) {
        viewModelScope.launch {
            container.conversationRepository.deleteConversation(conversation)
            if (_currentConversationId.value == conversation.id) startNewConversation()
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            container.conversationRepository.clearAll()
            startNewConversation()
        }
    }

    fun startListening(languageTag: String?) {
        if (_voiceState.value == VoiceState.LISTENING) return
        _voiceState.value = VoiceState.LISTENING
        _statusText.value = "LISTENING..."
        WallpaperEventBus.emit(JarvisHudState.LISTENING, "VOICE", "VOICE LISTENING")

        viewModelScope.launch {
            container.speechToTextManager.listen(languageTag).collectLatest { event ->
                when (event) {
                    SpeechEvent.ListeningStarted -> {
                        _voiceState.value = VoiceState.LISTENING
                        _statusText.value = "LISTENING..."
                        WallpaperEventBus.emit(JarvisHudState.LISTENING, "VOICE", "VOICE LISTENING")
                    }
                    SpeechEvent.ListeningEnded -> { /* result or error follows */ }
                    is SpeechEvent.PartialResult -> _statusText.value = event.text
                    is SpeechEvent.FinalResult -> {
                        if (event.text.isNotBlank()) {
                            sendMessage(event.text, speakReply = true)
                        } else {
                            _voiceState.value = VoiceState.IDLE
                            _statusText.value = "SYSTEM READY"
                        }
                    }
                    is SpeechEvent.Error -> {
                        _voiceState.value = VoiceState.ERROR
                        _statusText.value = event.message
                    }
                }
            }
        }
    }

    fun cancelListening() {
        container.speechToTextManager.cancel()
        _voiceState.value = VoiceState.IDLE
        _statusText.value = "SYSTEM READY"
    }

    fun sendMessage(text: String, speakReply: Boolean) {
        if (text.isBlank()) return
        _pendingClarification.value = null
        viewModelScope.launch {
            // Routing-critical commands (scroll, back, home, search-here, open app, stop, and
            // now the brain commands: diagnostic/memory/pause/resume/retry/stats) never touch
            // the AI — instant, always correct, and work fully offline / without an API key.
            LocalIntentRouter.match(text)?.let { command ->
                val conversationId = ensureConversation(text)
                container.conversationRepository.addMessage(conversationId, "user", text)

                // #53 INTERRUPTIONS and #46 "phir se karo" need live session state the local
                // command executors don't own, so they're resolved directly here.
                when (command) {
                    JarvisCommand.PauseTask -> {
                        container.taskEngine.pause()
                        return@launch finishLocal(conversationId, command, "Task paused.", speakReply)
                    }
                    JarvisCommand.ResumeTask -> {
                        container.taskEngine.resume()
                        return@launch finishLocal(conversationId, command, "Resuming.", speakReply)
                    }
                    JarvisCommand.RetryLastTask -> {
                        val target = lastUserRequestText
                        return@launch if (target.isNullOrBlank()) {
                            finishLocal(conversationId, command, "There's nothing to retry yet.", speakReply)
                        } else {
                            container.conversationRepository.addMessage(conversationId, "assistant", "Retrying: $target")
                            sendMessage(target, speakReply)
                        }
                    }
                    else -> {}
                }

                if (command == JarvisCommand.StopAction) {
                    container.textToSpeechManager.stop()
                    container.voiceActivityDetector.stop()
                    container.speechToTextManager.cancel()
                }

                // A locally-matched command can still be destructive (e.g. ForgetMemory) — the
                // "fast path never confirms" rule only ever applied to the confirmation-free
                // commands in this router; anything that needs a yes/no still gets one.
                if (command.requiresConfirmation() || container.securePrefs.confirmEveryAction) {
                    _pendingCommand.value = PendingCommand(command, command.describe())
                    _statusText.value = "AWAITING CONFIRMATION"
                    return@launch
                }

                val brainReply = container.brainCommandExecutor.execute(command)
                val hudReply = if (brainReply != null) null else HudCommandExecutor.execute(getApplication(), command)
                if (brainReply == null && hudReply == null) {
                    WallpaperEventBus.emit(JarvisHudState.EXECUTING, "COMMAND", command::class.simpleName?.uppercase() ?: "EXECUTING")
                }
                val result = if (brainReply != null || hudReply != null) null else container.actionExecutor.execute(command)
                val reply = brainReply ?: hudReply ?: when (result!!) {
                    is ExecutionResult.Success -> command.describe().removeSuffix(".").removeSuffix("?")
                    is ExecutionResult.Failure -> result.message
                }
                container.conversationRepository.addMessage(conversationId, "assistant", reply)
                _lastResponse.value = reply
                _statusText.value = "SYSTEM READY"
                log("ACTION: ${command::class.simpleName?.uppercase()}")
                container.contextManager.recordCommand(command)
                logHistory(command, success = result !is ExecutionResult.Failure, message = reply)
                if (brainReply == null && hudReply == null) WallpaperEventBus.emit(JarvisHudState.COMPLETED, "CORE", "COMMAND COMPLETED")
                if (speakReply) speak(reply) else {
                    _voiceState.value = VoiceState.IDLE
                    if (brainReply == null && hudReply == null) WallpaperEventBus.emit(JarvisHudState.IDLE, "CORE", "SYSTEM READY")
                }
                return@launch
            }

            refreshConnectivity()
            val conversationId = ensureConversation(text)
            container.conversationRepository.addMessage(conversationId, "user", text)

            if (_isOffline.value) {
                // #43 ERROR RECOVERY — "use offline capability if possible" rather than just
                // apologizing: if the local knowledge base has something relevant to what was
                // asked, answer from that instead of the generic offline message.
                val offlineKnowledge = container.knowledgeBase.retrieve(text, limit = 1)
                val offlineReply = offlineKnowledge.firstOrNull()?.content
                    ?: ("Offline mode — I can still open apps, settings, alarms and timers, " +
                        "but I need internet to think through open-ended questions.")
                container.conversationRepository.addMessage(conversationId, "assistant", offlineReply)
                _lastResponse.value = offlineReply
                _statusText.value = "NETWORK OFFLINE"
                _voiceState.value = VoiceState.IDLE
                return@launch
            }

            // #37 CONTEXT MANAGER — one assembled context (message + previous command +
            // current task + device state + relevant memory + capabilities) for everything
            // downstream, instead of ad hoc lookups scattered through this function.
            val context = container.contextManager.build(text, hasPendingTask = hasActiveTask)

            // #49 CONFIDENCE — a genuinely ambiguous, consequential request gets ONE
            // clarifying question instead of a guess. Low-stakes/reversible requests never
            // trigger this (IntentEngine only returns LOW confidence for exactly that case).
            if (context.intent.confidence == IntentConfidence.LOW) {
                val question = "Which app do you mean?"
                _pendingClarification.value = PendingClarification(question)
                container.conversationRepository.addMessage(conversationId, "assistant", question)
                _lastResponse.value = question
                _statusText.value = "AWAITING CLARIFICATION"
                if (speakReply) speak(question) else _voiceState.value = VoiceState.IDLE
                return@launch
            }

            lastUserRequestText = text
            _voiceState.value = VoiceState.THINKING
            _statusText.value = "PROCESSING..."
            WallpaperEventBus.emit(JarvisHudState.THINKING, "CORE", "THINKING")

            var searchContext = ""
            if (needsWebSearch(text)) {
                container.webSearchService.search(text).onSuccess { searchContext = it }
            }

            val memoryContext = context.relevantMemory.joinToString("\n") { "- $it" }
            val phoneContext = context.phoneContext.toPromptString()
            val appRegistry = container.appRegistry.compactPrompt()
            val capabilitySnapshot = container.capabilityRegistry.compactPrompt()
            val previousActionSummary = context.previousCommand?.describe()
            val knowledgeMatches = container.knowledgeBase.retrieve(text)
            val knowledgeContext = container.knowledgeBase.compactPrompt(knowledgeMatches)
            val systemPrompt = PromptBuilder.systemPrompt(
                memoryContext, languageHint = text, phoneContext = phoneContext, appRegistry = appRegistry,
                capabilitySnapshot = capabilitySnapshot, currentTaskDescription = context.currentTaskDescription,
                previousActionSummary = previousActionSummary, knowledgeContext = knowledgeContext
            ) + if (searchContext.isNotBlank()) "\n\nRecent web search results for this question:\n$searchContext" else ""

            val history = container.conversationRepository.getHistory(conversationId)
                .takeLast(20)
                .map { ChatMessage(role = if (it.role == "user") "user" else "assistant", content = it.content) }

            container.aiService.send(history, systemPrompt).fold(
                onSuccess = { result ->
                    container.conversationRepository.addMessage(conversationId, "assistant", result.replyText)
                    _lastResponse.value = result.replyText
                    _statusText.value = "SYSTEM READY"

                    val command = CommandEngine.parse(result.commandJson)
                    val plan = container.agentPlanner.parsePlan(result.commandJson)
                    if (plan != null) log("PLAN RECEIVED: ${plan.actions.size} ACTIONS")
                    else if (command != null) log("COMMAND RECEIVED: ${command::class.simpleName?.uppercase()}")
                    when {
                        plan != null -> handlePlan(plan.actions, text, speakReply, context.intent.category.name)
                        command is JarvisCommand.ReadScreen || command is JarvisCommand.ReadNotifications -> {
                            WallpaperEventBus.emit(
                                JarvisHudState.EXECUTING,
                                if (command is JarvisCommand.ReadScreen) "SCREEN" else "NOTIFICATIONS",
                                if (command is JarvisCommand.ReadScreen) "SCREEN ANALYSIS" else "NOTIFICATION ANALYSIS"
                            )
                            // A read-only command: what it "says" is whatever it finds on screen,
                            // not the AI's filler reply, so speak/show that instead.
                            _statusText.value = "READING SCREEN..."
                            val screenResult = container.actionExecutor.execute(command)
                            val screenText = when (screenResult) {
                                is ExecutionResult.Success -> screenResult.message
                                is ExecutionResult.Failure -> screenResult.message
                            }
                            container.conversationRepository.addMessage(conversationId, "assistant", screenText)
                            _lastResponse.value = screenText
                            _statusText.value = "SYSTEM READY"
                            container.contextManager.recordCommand(command)
                            if (speakReply) speak(screenText) else _voiceState.value = VoiceState.IDLE
                        }
                        command != null -> {
                            handleCommand(command)
                            container.contextManager.recordCommand(command)
                            if (speakReply) speak(result.replyText) else _voiceState.value = VoiceState.IDLE
                        }
                        else -> {
                            if (speakReply) speak(result.replyText) else _voiceState.value = VoiceState.IDLE
                        }
                    }
                },
                onFailure = { error ->
                    val message = "Sorry, something went wrong: ${error.message ?: "unknown error"}"
                    container.conversationRepository.addMessage(conversationId, "assistant", message)
                    _lastResponse.value = message
                    _voiceState.value = VoiceState.ERROR
                    _statusText.value = "SYSTEM ERROR"
                    WallpaperEventBus.emit(JarvisHudState.ERROR, "CORE", "SYSTEM ERROR")
                }
            )
        }
    }

    /** Shared tail for the tiny brain commands (pause/resume/retry-miss) that don't go through
     * an executor and just need a message logged + spoken. */
    private suspend fun finishLocal(conversationId: Long, command: JarvisCommand, reply: String, speakReply: Boolean) {
        container.conversationRepository.addMessage(conversationId, "assistant", reply)
        _lastResponse.value = reply
        _statusText.value = "SYSTEM READY"
        log("ACTION: ${command::class.simpleName?.uppercase()}")
        logHistory(command, success = true, message = reply)
        if (speakReply) speak(reply) else _voiceState.value = VoiceState.IDLE
    }

    private fun logHistory(command: JarvisCommand, success: Boolean, message: String) {
        viewModelScope.launch {
            container.brainRepository.logCommand(
                commandType = command::class.simpleName ?: "UNKNOWN",
                description = command.describe(),
                success = success,
                resultMessage = message
            )
        }
    }

    private suspend fun ensureConversation(firstMessage: String): Long {
        val existing = _currentConversationId.value
        if (existing != null) return existing
        val title = firstMessage.take(40)
        val id = container.conversationRepository.createConversation(title)
        _currentConversationId.value = id
        return id
    }

    private fun handlePlan(actions: List<JarvisCommand>, request: String, speakReply: Boolean, intentCategory: String) {
        if (actions.isEmpty()) return
        val requiresConfirmation = actions.any { it.requiresConfirmation() } || container.securePrefs.confirmEveryAction
        val confirmation = actions.mapIndexed { index, action ->
            "${index + 1}. ${action.describe().removeSuffix("?")}"
        }.joinToString("\n")
        if (requiresConfirmation) {
            _pendingPlan.value = PendingPlan(actions, confirmation)
            _statusText.value = "AWAITING CONFIRMATION"
            WallpaperEventBus.emit(JarvisHudState.PERMISSION_REQUIRED, "CORE", "PERMISSION REQUIRED")
            return
        }
        executePlan(actions, request, speakReply, intentCategory)
    }

    private fun executePlan(actions: List<JarvisCommand>, request: String, speakReply: Boolean, intentCategory: String = "MULTI_STEP_TASK") {
        viewModelScope.launch {
            hasActiveTask = true
            container.contextManager.setCurrentTask(request)
            _voiceState.value = VoiceState.THINKING
            _statusText.value = "PLANNING ${actions.size} STEPS..."
            WallpaperEventBus.emit(JarvisHudState.PLANNING, "CORE", "PLANNING ${actions.size} STEPS", 0f)
            log("PLAN START: ${actions.size} STEPS")
            val task = container.taskEngine.run(request, actions) { update ->
                WallpaperEventBus.emit(
                    when (update.status) {
                        com.jarvis.assistant.agent.TaskStatus.EXECUTING -> JarvisHudState.EXECUTING
                        com.jarvis.assistant.agent.TaskStatus.VERIFYING -> JarvisHudState.VERIFYING
                        com.jarvis.assistant.agent.TaskStatus.RECOVERING -> JarvisHudState.EXECUTING
                        com.jarvis.assistant.agent.TaskStatus.COMPLETED -> JarvisHudState.COMPLETED
                        com.jarvis.assistant.agent.TaskStatus.FAILED -> JarvisHudState.ERROR
                        com.jarvis.assistant.agent.TaskStatus.CANCELLED -> JarvisHudState.ERROR
                        else -> JarvisHudState.THINKING
                    },
                    "TASK",
                    "STEP ${update.currentStep}/${actions.size}",
                    if (actions.isEmpty()) 0f else update.currentStep.toFloat() / actions.size.toFloat()
                )
                _statusText.value = when (update.status) {
                    com.jarvis.assistant.agent.TaskStatus.EXECUTING -> "EXECUTING ${update.currentStep}/${actions.size}"
                    com.jarvis.assistant.agent.TaskStatus.VERIFYING -> "VERIFYING ${update.currentStep}/${actions.size}"
                    com.jarvis.assistant.agent.TaskStatus.RECOVERING -> "RECOVERING (RETRY ${update.retryCount})"
                    com.jarvis.assistant.agent.TaskStatus.PAUSED -> "TASK PAUSED"
                    com.jarvis.assistant.agent.TaskStatus.COMPLETED -> "TASK COMPLETE"
                    com.jarvis.assistant.agent.TaskStatus.FAILED -> "TASK FAILED"
                    com.jarvis.assistant.agent.TaskStatus.CANCELLED -> "TASK CANCELLED"
                    else -> "PROCESSING"
                }
                log("STEP ${update.currentStep}/${actions.size}")
            }
            hasActiveTask = false
            container.contextManager.setCurrentTask(null)
            val reply = when (task.status) {
                com.jarvis.assistant.agent.TaskStatus.COMPLETED -> "Task completed successfully."
                com.jarvis.assistant.agent.TaskStatus.CANCELLED -> "Task cancelled."
                else -> {
                    val detail = task.failedSteps.lastOrNull()?.substringAfter(": ")
                    "I couldn't complete the task${detail?.let { ": $it" } ?: "."}"
                }
            }
            _lastResponse.value = reply
            _statusText.value = if (task.status == com.jarvis.assistant.agent.TaskStatus.COMPLETED) "SYSTEM READY" else "SYSTEM ERROR"
            log(if (task.status == com.jarvis.assistant.agent.TaskStatus.COMPLETED) "PLAN COMPLETE" else "PLAN FAILED")

            // #44 LEARNING ENGINE — every plan run (success or failure) becomes one data point.
            container.learningEngine.recordOutcome(
                userRequest = request,
                intentCategory = intentCategory,
                stepCount = actions.size,
                success = task.status == com.jarvis.assistant.agent.TaskStatus.COMPLETED,
                failureReason = task.failureReason?.name,
                executionTimeMs = System.currentTimeMillis() - task.startedAt
            )

            // #25 NOTIFICATIONS — only multi-step tasks post a completion notification (never
            // single quick commands), and only when it actually succeeded.
            if (task.status == com.jarvis.assistant.agent.TaskStatus.COMPLETED) {
                com.jarvis.assistant.agent.JarvisNotifier.notifyTaskCompleted(getApplication(), container.securePrefs, "Task completed: $request".take(120))
            }

            if (speakReply) speak(reply) else _voiceState.value = VoiceState.IDLE
        }
    }

    private fun handleCommand(command: JarvisCommand) {
        HudCommandExecutor.execute(getApplication(), command)?.let { reply ->
            _lastResponse.value = reply
            _statusText.value = "SYSTEM READY"
            logHistory(command, success = true, message = reply)
            WallpaperEventBus.emit(JarvisHudState.COMPLETED, "CORE", "COMMAND COMPLETED")
            return
        }
        if (command is JarvisCommand.Remember) {
            viewModelScope.launch { container.memoryRepository.remember(command.content) }
            logHistory(command, success = true, message = "Remembered.")
            return
        }
        viewModelScope.launch {
            container.brainCommandExecutor.execute(command)?.let { reply ->
                _lastResponse.value = reply
                _statusText.value = "SYSTEM READY"
                logHistory(command, success = true, message = reply)
                return@launch
            }
            if (command.requiresConfirmation() || container.securePrefs.confirmEveryAction) {
                _pendingCommand.value = PendingCommand(command, command.describe())
            } else {
                val label = command.describe().removeSuffix("?").uppercase()
                _statusText.value = "EXECUTING: $label"
                log("ACTION: $label")
                WallpaperEventBus.emit(JarvisHudState.EXECUTING, "COMMAND", label)
                val result = container.actionExecutor.execute(command)
                _statusText.value = when (result) {
                    is ExecutionResult.Success -> "TASK COMPLETE"
                    is ExecutionResult.Failure -> result.message
                }
                log("STATUS: ${if (result is ExecutionResult.Success) "COMPLETE" else "FAILED"}")
                logHistory(command, success = result is ExecutionResult.Success, message = result.message)
                recordSingleCommandOutcome(command, result)
            }
        }
    }

    private fun recordSingleCommandOutcome(command: JarvisCommand, result: ExecutionResult) {
        viewModelScope.launch {
            container.learningEngine.recordOutcome(
                userRequest = command.describe(),
                intentCategory = command::class.simpleName ?: "UNKNOWN",
                stepCount = 1,
                success = result is ExecutionResult.Success,
                failureReason = if (result is ExecutionResult.Failure) FailureReason.classify(result.message).name else null,
                executionTimeMs = 0L
            )
        }
    }

    fun confirmPendingCommand(confirmed: Boolean) {
        val pending = _pendingCommand.value ?: return
        _pendingCommand.value = null
        if (confirmed) {
            val label = pending.command.describe().removeSuffix("?").uppercase()
            _statusText.value = "EXECUTING: $label"
            log("ACTION: $label")
            viewModelScope.launch {
                // Brain commands (e.g. a confirmed ForgetMemory) are local-only and never go
                // through AndroidActionExecutor — same interception order as everywhere else.
                val brainReply = container.brainCommandExecutor.execute(pending.command)
                if (brainReply != null) {
                    _lastResponse.value = brainReply
                    _statusText.value = "TASK COMPLETE"
                    logHistory(pending.command, success = true, message = brainReply)
                    return@launch
                }
                val result = container.actionExecutor.execute(pending.command)
                // A confirmed WhatsApp message is typed and waiting — actually send it now that
                // the user has said yes. The AI never gets a direct path to the Send button itself.
                if (result is ExecutionResult.Success && pending.command is JarvisCommand.SendWhatsAppMessage) {
                    container.actionExecutor.execute(JarvisCommand.SendPendingMessage)
                }
                _statusText.value = when (result) {
                    is ExecutionResult.Success -> "TASK COMPLETE"
                    is ExecutionResult.Failure -> result.message
                }
                log("STATUS: ${if (result is ExecutionResult.Success) "COMPLETE" else "FAILED"}")
                logHistory(pending.command, success = result is ExecutionResult.Success, message = result.message)
                recordSingleCommandOutcome(pending.command, result)
            }
        } else {
            _statusText.value = "SYSTEM READY"
        }
    }

    fun confirmPendingPlan(confirmed: Boolean) {
        val pending = _pendingPlan.value ?: return
        _pendingPlan.value = null
        if (confirmed) {
            executePlan(pending.actions, "confirmed multi-step task", speakReply = true)
        } else {
            _statusText.value = "SYSTEM READY"
            _voiceState.value = VoiceState.IDLE
        }
    }

    /** Replays a past message through TTS — used by the "speaker" button on chat bubbles. */
    fun speakAgain(text: String) {
        viewModelScope.launch { speak(text) }
    }

    private suspend fun speak(text: String) {
        // #16 JARVIS MODES — SILENT: still shows the reply and returns to ready, just never
        // reaches the TTS engine at all.
        if (container.securePrefs.silentModeEnabled) {
            _voiceState.value = VoiceState.IDLE
            _statusText.value = "SYSTEM READY (SILENT)"
            return
        }
        _voiceState.value = VoiceState.SPEAKING
        _statusText.value = "RESPONDING..."
        WallpaperEventBus.emit(JarvisHudState.COMPLETED, "VOICE", "RESPONDING")
        val prefs = container.securePrefs
        container.textToSpeechManager.setRate(prefs.speechRate)
        // Default to a male-sounding voice the first time, then remember whatever the user picks.
        val voiceName = prefs.voiceName ?: container.textToSpeechManager.autoSelectMaleVoice()?.also { prefs.voiceName = it }
        container.textToSpeechManager.setVoice(voiceName)

        // Barge-in: while speaking, also watch the mic for the user starting to talk over it.
        // The moment that happens, stop talking immediately and start listening to them instead.
        val interrupted = java.util.concurrent.atomic.AtomicBoolean(false)
        container.voiceActivityDetector.start {
            if (interrupted.compareAndSet(false, true)) {
                container.textToSpeechManager.stop()
            }
        }
        container.textToSpeechManager.speak(text)
        container.voiceActivityDetector.stop()

        if (interrupted.get()) {
            startListening(languageTag = null)
        } else {
            _voiceState.value = VoiceState.IDLE
            _statusText.value = "SYSTEM READY"
        }
    }

    override fun onCleared() {
        super.onCleared()
        JarvisAccessibilityService.onStepEvent = null
        container.textToSpeechManager.shutdown()
        container.speechToTextManager.cancel()
    }
}
