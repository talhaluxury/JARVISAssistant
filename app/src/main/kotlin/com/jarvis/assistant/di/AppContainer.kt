package com.jarvis.assistant.di

import android.content.Context
import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.agent.AppRegistry
import com.jarvis.assistant.agent.ConfirmationManager
import com.jarvis.assistant.agent.PermissionManager
import com.jarvis.assistant.agent.PhoneContextEngine
import com.jarvis.assistant.agent.TaskEngine
import com.jarvis.assistant.ai.OpenAiService
import com.jarvis.assistant.command.AndroidActionExecutor
import com.jarvis.assistant.data.local.db.JarvisDatabase
import com.jarvis.assistant.data.local.prefs.SecurePrefs
import com.jarvis.assistant.data.repository.ConversationRepository
import com.jarvis.assistant.data.repository.MemoryRepository
import com.jarvis.assistant.search.WebSearchService
import com.jarvis.assistant.voice.SpeechToTextManager
import com.jarvis.assistant.voice.TextToSpeechManager
import com.jarvis.assistant.voice.VoiceActivityDetector

/**
 * Simple hand-written dependency container. Keeping this manual (instead of
 * Hilt/Dagger annotation processing) keeps the Gradle/KAPT setup lighter and
 * easier to build reliably from GitHub Actions without Android Studio.
 */
class AppContainer(context: Context) {

    val securePrefs = SecurePrefs(context)

    private val database = JarvisDatabase.getInstance(context)

    val memoryRepository = MemoryRepository(database.memoryDao())
    val conversationRepository = ConversationRepository(database.conversationDao(), database.messageDao())

    // Swap this line to point at a different provider implementation without touching any UI code.
    val aiService: AiService = OpenAiService(securePrefs)

    val webSearchService = WebSearchService(securePrefs)

    val speechToTextManager = SpeechToTextManager(context)
    val textToSpeechManager = TextToSpeechManager(context)
    val voiceActivityDetector = VoiceActivityDetector(context)

    val actionExecutor = AndroidActionExecutor(context, securePrefs)

    // Agent layer: perception, capability state, app discovery, confirmation and bounded execution.
    val phoneContextEngine = PhoneContextEngine(context)
    val appRegistry = AppRegistry(context)
    val permissionManager = PermissionManager(context)
    val confirmationManager = ConfirmationManager(securePrefs)
    val taskEngine = TaskEngine(actionExecutor, phoneContextEngine)
}
