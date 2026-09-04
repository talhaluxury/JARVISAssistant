package com.jarvis.assistant.agent

import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.command.AndroidActionExecutor
import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand
import kotlinx.coroutines.delay
import java.util.UUID

/** Sequential, bounded agent loop: execute -> re-scan -> verify -> retry/recover. */
class TaskEngine(
    private val executor: AndroidActionExecutor,
    private val contextEngine: PhoneContextEngine
) {
    @Volatile private var cancelled = false

    fun cancel() { cancelled = true; JarvisAccessibilityService.cancelQueue() }

    suspend fun run(request: String, commands: List<JarvisCommand>, onUpdate: (AgentTask) -> Unit = {}): AgentTask {
        cancelled = false
        var task = AgentTask(UUID.randomUUID().toString(), request, commands, status = TaskStatus.EXECUTING)
        onUpdate(task)
        val completed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var retries = 0

        for ((index, command) in commands.withIndex()) {
            if (cancelled) {
                return task.copy(currentStep = index, status = TaskStatus.CANCELLED, completedSteps = completed, failedSteps = failed, retryCount = retries)
            }
            val label = command.describeForAgent()
            var result: ExecutionResult = ExecutionResult.Failure("Not executed")
            var success = false
            for (attempt in 0 until 3) {
                if (cancelled) break
                if (attempt > 0) { retries++; delay(500L * attempt) }
                result = executor.execute(command)
                if (result is ExecutionResult.Success) {
                    if (command is JarvisCommand.Automate) {
                        var waited = 0
                        while (JarvisAccessibilityService.isAutomationBusy() && waited < 15000 && !cancelled) {
                            delay(250); waited += 250
                        }
                        success = !JarvisAccessibilityService.isAutomationBusy() && !JarvisAccessibilityService.lastAutomationHadFailure()
                    } else {
                        delay(300)
                        success = verify(command)
                    }
                }
                if (success) break
            }
            if (success) completed += label else failed += "$label: ${result.message}"
            task = task.copy(currentStep = index + 1, status = if (success) TaskStatus.VERIFYING else TaskStatus.EXECUTING,
                completedSteps = completed.toList(), failedSteps = failed.toList(), retryCount = retries)
            onUpdate(task)
            if (!success) {
                return task.copy(status = TaskStatus.FAILED)
            }
        }
        return task.copy(status = TaskStatus.COMPLETED, currentStep = commands.size, completedSteps = completed, failedSteps = failed, retryCount = retries)
    }

    private fun verify(command: JarvisCommand): Boolean {
        val ctx = contextEngine.snapshot()
        return when (command) {
            is JarvisCommand.OpenApp -> ctx.appLabel?.contains(command.target, true) == true ||
                ctx.packageName?.contains(command.target, true) == true
            is JarvisCommand.GoHome -> true
            is JarvisCommand.GoBack, JarvisCommand.OpenRecentApps -> true
            is JarvisCommand.StopAction -> true
            else -> true // executor success is the strongest available signal for system intents.
        }
    }

    private fun JarvisCommand.describeForAgent(): String = when (this) {
        is JarvisCommand.OpenApp -> "OPEN_APP $target"
        is JarvisCommand.SearchCurrentApp -> "SEARCH_CURRENT_APP $query"
        is JarvisCommand.Automate -> "AUTOMATE ${steps.size} steps"
        is JarvisCommand.SendWhatsAppMessage -> "WHATSAPP $contact"
        is JarvisCommand.TapFirstResult -> "TAP_FIRST_RESULT"
        is JarvisCommand.ScrollDown -> "SCROLL_DOWN"
        is JarvisCommand.ScrollUp -> "SCROLL_UP"
        else -> toString().substringBefore('(')
    }
}
