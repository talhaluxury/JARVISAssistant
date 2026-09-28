package com.jarvis.assistant.wingo

import android.content.Context
import com.jarvis.assistant.wingo.data.GameHistoryRepository
import com.jarvis.assistant.wingo.data.PredictionRepository
import com.jarvis.assistant.wingo.data.WinGoDatabase
import com.jarvis.assistant.wingo.voice.WinGoChatController
import com.jarvis.assistant.wingo.voice.WinGoVoiceController

/** Single entry point wired into AppContainer (`container.winGo`). Everything is created lazily. */
class WinGoModule(context: Context) {
    private val appContext = context.applicationContext

    private val database: WinGoDatabase by lazy { WinGoDatabase.create(appContext) }

    val settings: WinGoSettings by lazy { WinGoSettings(appContext) }
    val historyRepository: GameHistoryRepository by lazy { GameHistoryRepository(database.gameResultDao()) }
    val predictionRepository: PredictionRepository by lazy { PredictionRepository(database.predictionDao()) }
    val coordinator: WinGoCoordinator by lazy { WinGoCoordinator(historyRepository, predictionRepository, settings) }
    val chat: WinGoChatController by lazy { WinGoChatController(coordinator) }
    val controls: WinGoControls by lazy { WinGoControls(appContext) }
    val voice: WinGoVoiceController by lazy { WinGoVoiceController({ coordinator }, { chat }, controls) }
}
