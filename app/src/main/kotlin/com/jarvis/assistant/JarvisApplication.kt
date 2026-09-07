package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.hud.WallpaperEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class JarvisApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WallpaperEventBus.initialize(this)

        // #45 KNOWLEDGE BASE — seed the bundled Android/JARVIS/device/automation/
        // troubleshooting/voice/system/capability facts from assets into the local DB on
        // first run only (ensureSeeded is a no-op once bundled rows already exist). Off the
        // main thread since it touches disk; nothing else depends on it finishing immediately.
        CoroutineScope(Dispatchers.IO).launch {
            container.knowledgeRepository.ensureSeeded()
        }
    }
}
