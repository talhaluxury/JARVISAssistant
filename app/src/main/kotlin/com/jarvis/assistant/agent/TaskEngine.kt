package com.jarvis.assistant.agent

import com.jarvis.assistant.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.command.AndroidActionExecutor
import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand
import kotlinx.coroutines.delay
import com.jarvis.assistant.core.resource.ResourceLockManager
import java.util.UUID

/**
 * Sequential, bounded agent loop implementing #42 SELF-VERIFICATION, #43 ERROR RECOVERY, and
 * #52 TASK STATE MACHINE: execute -> re-scan -> verify -> retry/recover -> next step, with
 * explicit pause/resume/cancel support (#53 INTERRUPTIONS).
 *
 * Each step is also tagged with the [AgentDomain] "specialist" handling it, and - when a
 * [replanner] is supplied - a step that exhausts its normal retries gets one more chance:
 * the replanner (backed by the AI) is told exactly what failed and why, and can propose a
 * single corrective command to try instead of giving up immediately. This is bounded by
 * [maxReplansPerTask] so a stubborn failure can never turn into a runaway loop of AI calls.
 */
class TaskEngine(
    private val executor: AndroidActionExecutor,
    private val contextEngine: PhoneContextEngine,
    private val resourceLocks: ResourceLockManager = ResourceLockManager(),
    private val replanner: (suspend (goal: String, failed: JarvisCommand, domain: AgentDomain, reason: String) -> JarvisCommand?)? = null,
    private val maxReplansPerTask: Int = 2
) {
    @Volatile private var cancelled = false
    @Volatile private var paused = false

    fun cancel() { cancelled = true; paused = false; JarvisAccessibilityService.cancelQueue() }
    fun pause() { paused = true }
    fun resume() { paused = false }
    fun isPaused(): Boolean = paused

    suspend fun run(request: String, commands: List<JarvisCommand>, onUpdate: (AgentTask) -> Unit = {}): AgentTask {
        cancelled = false
        paused = false
        var task = AgentTask(UUID.randomUUID().toString(), request, commands, status = TaskStatus.EXECUTING)
        onUpdate(task)
        val completed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var retries = 0
        var replans = 0

        var index = 0
        var queue = commands
        while (index < queue.size) {
            var command = queue[index]

            // #53 INTERRUPTIONS — block here (not spin) while paused, still responsive to cancel.
            while (paused && !cancelled) {
                task = task.copy(status = TaskStatus.PAUSED)
                onUpdate(task)
                delay(300)
            }
            if (cancelled) {
                return task.copy(currentStep = index, status = TaskStatus.CANCELLED, completedSteps = completed, failedSteps = failed, retryCount = retries)
            }

            val domain = AgentDomain.of(command)
            var label = "[${domain.label}] ${command.describeForAgent()}"
            var result: ExecutionResult = ExecutionResult.Failure("Not executed")
            var success = false

            for (attempt in 0 until 3) {
                if (cancelled) break
                if (attempt > 0) {
                    retries++
                    task = task.copy(status = TaskStatus.RECOVERING, retryCount = retries)
                    onUpdate(task)
                    delay(500L * attempt)
                }
                result = if (command is JarvisCommand.Automate) {
                    val resource = command.packageName ?: contextEngine.snapshot().packageName ?: "foreground-ui"
                    resourceLocks.withLock("ui:$resource") { executor.execute(command) }
                } else executor.execute(command)
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

            // Normal retries are exhausted. Before giving up on the whole task, give the AI one
            // chance per remaining replan budget to propose a different command for this same
            // step (e.g. the app name was wrong, or a different approach is needed).
            if (!success && replanner != null && replans < maxReplansPerTask && !cancelled) {
                replans++
                task = task.copy(status = TaskStatus.RECOVERING, retryCount = retries)
                onUpdate(task)
                val revised = runCatching { replanner?.invoke(request, command, domain, result.message) }.getOrNull()
                if (revised != null && revised != command) {
                    command = revised
                    label = "[${domain.label}] ${command.describeForAgent()} (replanned)"
                    for (attempt in 0 until 2) {
                        if (cancelled) break
                        if (attempt > 0) {
                            retries++
                            delay(500L * attempt)
                        }
                        result = executor.execute(command)
                        success = result is ExecutionResult.Success && run {
                            delay(300); verify(command)
                        }
                        if (success) break
                    }
                }
            }

            if (success) completed += label else failed += "$label: ${result.message}"
            task = task.copy(currentStep = index + 1, status = if (success) TaskStatus.VERIFYING else TaskStatus.EXECUTING,
                completedSteps = completed.toList(), failedSteps = failed.toList(), retryCount = retries)
            onUpdate(task)
            if (!success) {
                return task.copy(status = TaskStatus.FAILED, failureReason = FailureReason.classify(result.message))
            }
            index++
        }
        return task.copy(status = TaskStatus.COMPLETED, currentStep = queue.size, completedSteps = completed, failedSteps = failed, retryCount = retries)
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
