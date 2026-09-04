package com.jarvis.assistant

import android.app.Application
import com.jarvis.assistant.di.AppContainer
import com.jarvis.assistant.hud.WallpaperEventBus

class JarvisApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        WallpaperEventBus.initialize(this)
    }
}
