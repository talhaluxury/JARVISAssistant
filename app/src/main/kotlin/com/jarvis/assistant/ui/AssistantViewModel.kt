package com.jarvis.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.JarvisApplication
import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.ai.ChatMessage
import com.jarvis.assistant.ai.PromptBuilder
import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.describe
import com.jarvis.assistant.command.requiresConfirmation
import com.jarvis.assistant.data.local.db.entity.ConversationEntity
import com.jarvis.assistant.data.local.db.entity.MessageEntity
import com.jarvis.assistant.search.needsWebSearch
import com.jarvis.assistant.util.NetworkMonitor
import com.jarvis.assistant.voice.SpeechEvent
import com.jarvis.assistant.voice.VoiceState
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

@OptIn(ExperimentalCoroutinesApi::class)
class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as JarvisApplication).container

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _statusText = MutableStateFlow("SYSTEM READY")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _lastResponse = MutableStateFlow("")
    val lastResponse: StateFlow<String> = _lastResponse.asStateFlow()

    private val _pendingCommand = MutableStateFlow<PendingCommand?>(null)
    val pendingCommand: StateFlow<PendingCommand?> = _pendingCommand.asStateFlow()

    private val _isOffline = MutableStateFlow(!NetworkMonitor.isOnline(application))
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    /** A short rolling console of real system events — what JARVIS actually just did. */
    private val _commandLog = MutableStateFlow<List<String>>(emptyList())
    val commandLog: StateFlow<List<String>> = _commandLog.asStateFlow()

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

        viewModelScope.launch {
            container.speechToTextManager.listen(languageTag).collectLatest { event ->
                when (event) {
                    SpeechEvent.ListeningStarted -> {
                        _voiceState.value = VoiceState.LISTENING
                        _statusText.value = "LISTENING..."
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
        viewModelScope.launch {
            refreshConnectivity()
            val conversationId = ensureConversation(text)
            container.conversationRepository.addMessage(conversationId, "user", text)

            if (_isOffline.value) {
                val offlineReply = "Offline mode — I can still open apps, settings, alarms and timers, " +
                    "but I need internet to think through open-ended questions."
                container.conversationRepository.addMessage(conversationId, "assistant", offlineReply)
                _lastResponse.value = offlineReply
                _statusText.value = "NETWORK OFFLINE"
                _voiceState.value = VoiceState.IDLE
                return@launch
            }

            _voiceState.value = VoiceState.THINKING
            _statusText.value = "PROCESSING..."

            var searchContext = ""
            if (needsWebSearch(text)) {
                container.webSearchService.search(text).onSuccess { searchContext = it }
            }

            val memoryContext = container.memoryRepository.getAllAsPromptContext()
            val systemPrompt = PromptBuilder.systemPrompt(memoryContext, languageHint = text) +
                if (searchContext.isNotBlank()) "\n\nRecent web search results for this question:\n$searchContext" else ""

            val history = container.conversationRepository.getHistory(conversationId)
                .takeLast(20)
                .map { ChatMessage(role = if (it.role == "user") "user" else "assistant", content = it.content) }

            container.aiService.send(history, systemPrompt).fold(
                onSuccess = { result ->
                    container.conversationRepository.addMessage(conversationId, "assistant", result.replyText)
                    _lastResponse.value = result.replyText
                    _statusText.value = "SYSTEM READY"

                    val command = CommandEngine.parse(result.commandJson)
                    if (command != null) log("COMMAND RECEIVED: ${command::class.simpleName?.uppercase()}")
                    when {
                        command is JarvisCommand.ReadScreen -> {
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
                            if (speakReply) speak(screenText) else _voiceState.value = VoiceState.IDLE
                        }
                        command != null -> {
                            handleCommand(command)
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
                }
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

    private fun handleCommand(command: JarvisCommand) {
        if (command is JarvisCommand.Remember) {
            viewModelScope.launch { container.memoryRepository.remember(command.content) }
            return
        }
        if (command.requiresConfirmation() || container.securePrefs.confirmEveryAction) {
            _pendingCommand.value = PendingCommand(command, command.describe())
        } else {
            val label = command.describe().removeSuffix("?").uppercase()
            _statusText.value = "EXECUTING: $label"
            log("ACTION: $label")
            val result = container.actionExecutor.execute(command)
            _statusText.value = when (result) {
                is ExecutionResult.Success -> "TASK COMPLETE"
                is ExecutionResult.Failure -> result.message
            }
            log("STATUS: ${if (result is ExecutionResult.Success) "COMPLETE" else "FAILED"}")
        }
    }

    fun confirmPendingCommand(confirmed: Boolean) {
        val pending = _pendingCommand.value ?: return
        _pendingCommand.value = null
        if (confirmed) {
            val label = pending.command.describe().removeSuffix("?").uppercase()
            _statusText.value = "EXECUTING: $label"
            log("ACTION: $label")
            val result = container.actionExecutor.execute(pending.command)
            _statusText.value = when (result) {
                is ExecutionResult.Success -> "TASK COMPLETE"
                is ExecutionResult.Failure -> result.message
            }
            log("STATUS: ${if (result is ExecutionResult.Success) "COMPLETE" else "FAILED"}")
        } else {
            _statusText.value = "SYSTEM READY"
        }
    }

    /** Replays a past message through TTS — used by the "speaker" button on chat bubbles. */
    fun speakAgain(text: String) {
        viewModelScope.launch { speak(text) }
    }

    private suspend fun speak(text: String) {
        _voiceState.value = VoiceState.SPEAKING
        _statusText.value = "RESPONDING..."
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
