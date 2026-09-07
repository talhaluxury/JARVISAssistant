package com.jarvis.assistant.hud

import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.LocalIntentRouter
import org.junit.Assert.assertTrue
import org.junit.Test

class HudCommandTest {
    @Test fun localHudCommandsRouteOffline() {
        assertTrue(LocalIntentRouter.match("activate hud") is JarvisCommand.ActivateHud)
        assertTrue(LocalIntentRouter.match("show battery") is JarvisCommand.ShowBattery)
        assertTrue(LocalIntentRouter.match("show system status") is JarvisCommand.ShowSystemStatus)
        assertTrue(LocalIntentRouter.match("full hud") is JarvisCommand.FullHud)
        assertTrue(LocalIntentRouter.match("minimal hud") is JarvisCommand.MinimalHud)
    }

    @Test fun aiHudCommandsAreValidated() {
        assertTrue(CommandEngine.parse("""{"type":"ACTIVATE_HUD"}""") is JarvisCommand.ActivateHud)
        assertTrue(CommandEngine.parse("""{"type":"SHOW_NETWORK"}""") is JarvisCommand.ShowNetwork)
        assertTrue(CommandEngine.parse("""{"type":"POWER_SAVING_HUD"}""") is JarvisCommand.PowerSavingHud)
    }
}
