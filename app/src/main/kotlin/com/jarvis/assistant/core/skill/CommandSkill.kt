package com.jarvis.assistant.core.skill

import com.jarvis.assistant.agent.CapabilityStatusEntry
import com.jarvis.assistant.command.*

/** Built-in skill adapters. They only route closed commands; execution remains in AndroidActionExecutor. */
class CommandSkill(
    override val id: String,
    private val matcher: (JarvisCommand) -> Boolean,
    private val capabilityProvider: () -> List<CapabilityStatusEntry> = { emptyList() }
) : JarvisSkill {
    override val version: Int = 1
    override fun capabilities(): List<CapabilityStatusEntry> = capabilityProvider()
    override fun supports(command: JarvisCommand): Boolean = matcher(command)
}

object BuiltInSkills {
    fun registerInto(registry: SkillRegistry, capabilities: () -> List<CapabilityStatusEntry>) {
        registry.register(CommandSkill("voice", { false }, capabilities))
        registry.register(CommandSkill("memory", { it is JarvisCommand.Remember || it is JarvisCommand.MemoryQuery || it is JarvisCommand.ForgetMemory }, capabilities))
        registry.register(CommandSkill("device", { it is JarvisCommand.ShowBattery || it is JarvisCommand.ShowNetwork || it is JarvisCommand.OpenSettings || it is JarvisCommand.AdjustVolume }, capabilities))
        registry.register(CommandSkill("app", { it is JarvisCommand.OpenApp || it is JarvisCommand.ListInstalledApps || it is JarvisCommand.CloseCurrentApp }, capabilities))
        registry.register(CommandSkill("browser", { it is JarvisCommand.OpenBrowser || it is JarvisCommand.SearchCurrentApp }, capabilities))
        registry.register(CommandSkill("automation", { it is JarvisCommand.Automate || it is JarvisCommand.ScrollDown || it is JarvisCommand.ScrollUp || it is JarvisCommand.LongPress || it is JarvisCommand.TapFirstResult }, capabilities))
        registry.register(CommandSkill("vision", { it is JarvisCommand.ReadScreen }, capabilities))
        registry.register(CommandSkill("diagnostic", { it is JarvisCommand.RunDiagnostic || it is JarvisCommand.ShowSystemStatus }, capabilities))
        registry.register(CommandSkill("notification", { it is JarvisCommand.ReadNotifications || it is JarvisCommand.ShowNotificationsHud || it is JarvisCommand.EnableNotificationAccess }, capabilities))
        registry.register(CommandSkill("workflow", { it is JarvisCommand.Automate || it is JarvisCommand.RetryLastTask || it is JarvisCommand.PauseTask || it is JarvisCommand.ResumeTask }, capabilities))
    }
}
