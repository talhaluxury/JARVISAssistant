package com.jarvis.assistant.agent

import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.ai.ChatMessage
import com.jarvis.assistant.ai.PromptBuilder
import com.jarvis.assistant.command.AndroidActionExecutor
import com.jarvis.assistant.command.CommandEngine
import com.jarvis.assistant.command.ExecutionResult
import com.jarvis.assistant.command.JarvisCommand
import com.jarvis.assistant.command.describe
import com.jarvis.assistant.command.requiresConfirmation
import com.jarvis.assistant.core.mission.MissionManager
import com.jarvis.assistant.core.mission.MissionStatus
import com.jarvis.assistant.core.observability.AuditLog
import com.jarvis.assistant.core.security.RiskEngine
import com.jarvis.assistant.core.state.RiskLevel
import org.json.JSONObject

/** One thing that happened during an autonomous run, reported to the UI as it happens. */
sealed class AgentEvent {
    data class Started(val agentName: String) : AgentEvent()
    data class Thinking(val stepNumber: Int) : AgentEvent()
    data class StepExecuted(val stepNumber: Int, val command: JarvisCommand, val result: ExecutionResult) : AgentEvent()
    data class AwaitingConfirmation(val command: JarvisCommand) : AgentEvent()
    data class Done(val summary: String) : AgentEvent()
    data class Stopped(val reason: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
}

/**
 * A true iterative ("ReAct"-style) autonomous agent. Unlike the existing
 * single-shot AGENT_PLAN (where the AI writes a fixed list of actions once,
 * upfront, and the app then executes all of them blindly), this asks the AI
 * for ONE action at a time, runs it, feeds back the real result, and lets
 * the AI decide the next action - so it can adapt if something doesn't go
 * as expected instead of ploughing on with a stale plan.
 *
 * Delegates to one of several specialized [AgentDefinition]s (chosen by
 * [AgentRouter]) so the system prompt stays focused on the kind of task at
 * hand instead of one giant do-everything prompt.
 *
 * Every proposed action still goes through the exact same [CommandEngine]
 * validation and [RiskEngine] risk check as the rest of the app - the AI's
 * JSON is still only ever a suggestion, never trusted or executed blindly.
 * Anything classified HIGH risk (sending a message, deleting memory, etc.)
 * pauses and waits for the owner's explicit confirmation via
 * [confirmPendingAndContinue] rather than running unattended: full autonomy
 * for reversible/low-risk steps, a human checkpoint for anything that can't
 * be easily undone.
 */
class AutonomousAgentOrchestrator(
    private val aiService: AiService,
    private val actionExecutor: AndroidActionExecutor,
    private val riskEngine: RiskEngine,
    private val missionManager: MissionManager,
    private val auditLog: AuditLog,
    private val router: AgentRouter = AgentRouter(),
    private val maxSteps: Int = 8
) {
    private var pendingCommand: JarvisCommand? = null
    private var pendingHistory: MutableList<ChatMessage>? = null
    private var pendingSystemPrompt: String? = null
    private var pendingGoal: String? = null
    private var pendingStep: Int = 0
    private var pendingMissionId: String? = null

    suspend fun run(
        goal: String,
        phoneContextPrompt: String = "",
        appRegistryPrompt: String = "",
        onEvent: suspend (AgentEvent) -> Unit
    ) {
        val agent = router.route(goal)
        val mission = missionManager.create(goal)
        auditLog.record("AGENT_START", "[${agent.displayName}] $goal")
        onEvent(AgentEvent.Started(agent.displayName))

        val basePrompt = PromptBuilder.systemPrompt(
            memoryContext = "",
            languageHint = goal,
            phoneContext = phoneContextPrompt,
            appRegistry = appRegistryPrompt
        )
        val systemPrompt = basePrompt + "\n\n" + agent.personaPrompt + "\n\n" + ITERATIVE_MODE_INSTRUCTIONS
        val history = mutableListOf(ChatMessage("user", goal))

        continueLoop(goal, history, systemPrompt, startStep = 0, mission.id, onEvent)
    }

    /** Call after the owner approves a step reported via [AgentEvent.AwaitingConfirmation]. */
    suspend fun confirmPendingAndContinue(onEvent: suspend (AgentEvent) -> Unit) {
        val command = pendingCommand ?: return
        val history = pendingHistory ?: return
        val systemPrompt = pendingSystemPrompt ?: return
        val goal = pendingGoal ?: return
        val missionId = pendingMissionId
        val step = pendingStep
        clearPending()

        val execResult = runStep(command, history, goal)
        onEvent(AgentEvent.StepExecuted(step, command, execResult))
        auditLog.record("AGENT_STEP_CONFIRMED", "${command.describe()} -> ${execResult.message}", execResult is ExecutionResult.Success)

        continueLoop(goal, history, systemPrompt, startStep = step, missionId, onEvent)
    }

    /** Call if the owner declines a step reported via [AgentEvent.AwaitingConfirmation]. */
    fun cancelPending() { clearPending() }

    private fun clearPending() {
        pendingCommand = null; pendingHistory = null; pendingSystemPrompt = null
        pendingGoal = null; pendingStep = 0; pendingMissionId = null
    }

    private suspend fun continueLoop(
        goal: String,
        history: MutableList<ChatMessage>,
        systemPrompt: String,
        startStep: Int,
        missionId: String?,
        onEvent: suspend (AgentEvent) -> Unit
    ) {
        var step = startStep
        while (step < maxSteps) {
            step++
            onEvent(AgentEvent.Thinking(step))
            val outcome = aiService.send(history, systemPrompt)
            val aiResult = outcome.getOrElse {
                onEvent(AgentEvent.Error(it.message ?: "AI request failed"))
                auditLog.record("AGENT_ERROR", it.message ?: "unknown", success = false)
                if (missionId != null) missionManager.update(missionId) { m -> m.copy(status = MissionStatus.FAILED, lastError = it.message) }
                return
            }
            history.add(ChatMessage("assistant", aiResult.replyText))

            val json = aiResult.commandJson
            if (json != null && isDoneSignal(json)) {
                val summary = runCatching { JSONObject(json).optString("summary", aiResult.replyText) }.getOrDefault(aiResult.replyText)
                onEvent(AgentEvent.Done(summary))
                auditLog.record("AGENT_DONE", summary)
                if (missionId != null) missionManager.update(missionId) { m -> m.copy(status = MissionStatus.COMPLETED) }
                return
            }

            val command = CommandEngine.parse(json)
            if (command == null) {
                // No actionable step proposed - treat the reply itself as the final answer.
                onEvent(AgentEvent.Done(aiResult.replyText))
                auditLog.record("AGENT_DONE", aiResult.replyText)
                if (missionId != null) missionManager.update(missionId) { m -> m.copy(status = MissionStatus.COMPLETED) }
                return
            }

            val risk = riskEngine.assess(command)
            if (risk == RiskLevel.HIGH || command.requiresConfirmation()) {
                pendingCommand = command
                pendingHistory = history
                pendingSystemPrompt = systemPrompt
                pendingGoal = goal
                pendingStep = step
                pendingMissionId = missionId
                onEvent(AgentEvent.AwaitingConfirmation(command))
                auditLog.record("AGENT_PAUSED", command.describe())
                if (missionId != null) missionManager.update(missionId) { m -> m.copy(status = MissionStatus.WAITING_PERMISSION) }
                return
            }

            val execResult = runStep(command, history, goal)
            onEvent(AgentEvent.StepExecuted(step, command, execResult))
            auditLog.record("AGENT_STEP", "${command.describe()} -> ${execResult.message}", execResult is ExecutionResult.Success)
        }

        onEvent(AgentEvent.Stopped("Reached the step limit ($maxSteps) without finishing."))
        auditLog.record("AGENT_STOPPED", "step limit reached", success = false)
        if (missionId != null) missionManager.update(missionId) { m -> m.copy(status = MissionStatus.FAILED, lastError = "step limit reached") }
    }

    private fun runStep(command: JarvisCommand, history: MutableList<ChatMessage>, goal: String): ExecutionResult {
        val result = actionExecutor.execute(command)
        history.add(ChatMessage(
            "user",
            "[SYSTEM] Result of that action: ${result.message}. " +
                "If the goal is now fully complete, respond with the AGENT_DONE block. " +
                "Otherwise propose the next single action toward: \"$goal\"."
        ))
        return result
    }

    private fun isDoneSignal(json: String): Boolean =
        runCatching { JSONObject(json).optString("type").uppercase() == "AGENT_DONE" }.getOrDefault(false)

    companion object {
        private val ITERATIVE_MODE_INSTRUCTIONS = """
            AUTONOMOUS AGENT MODE: you are now running step-by-step toward a goal, not just
            answering one message. On every turn, propose EXACTLY ONE next action as a single
            fenced ```jarvis_command block, using the same action types and JSON shapes already
            described above (one action per turn here - never an AGENT_PLAN array in this mode).
            After each action you will be told its real result as a system message - use that to
            decide the next step, and change approach if something didn't work instead of
            repeating the same failed action. When the goal is fully achieved, respond with this
            fenced block instead of a normal action, summarizing what was done:
            ```jarvis_command
            {"type":"AGENT_DONE","summary":"<one or two sentence summary of what was accomplished>"}
            ```
        """.trimIndent()
    }
}
