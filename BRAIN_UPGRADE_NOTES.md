# JARVIS AI Brain Upgrade (sections 35–58)

This build layers the "intelligent agent architecture" spec on top of the existing
JARVISAssistant app (Compose UI, Room persistence, AgentPlanner/TaskEngine automation,
live-wallpaper HUD). It does not replace that architecture — it extends the parts that were
still simple keyword/one-shot logic into the layers the spec asks for, and wires them into both
places the app currently accepts input: `AssistantViewModel` (chat/tap-to-talk) and
`OverlayService` (background wake-word bubble).

## What's new and where it lives

| Spec section | File(s) | What it actually does |
|---|---|---|
| #37 Context-aware intelligence | `agent/ContextManager.kt` | Assembles one `ConversationContext` per turn (message + previous command + current task + device state + relevance-filtered memory + capabilities) instead of ad hoc lookups scattered through the ViewModel. |
| #38 Advanced memory architecture | `data/local/db/entity/{MemoryEntity,BrainEntities}.kt`, `dao/BrainDao.kt`, `repository/{MemoryRepository,BrainRepository}.kt`, real Room migration in `JarvisDatabase.kt` | Adds category/source/confidence/approved metadata to memory, plus command-history, task-outcome, system-event, and structured-preference tables. Migration is explicit SQL (v1→v2), not destructive fallback — existing memories survive an update. |
| #39 Intent engine | `agent/IntentModels.kt`, `agent/IntentEngine.kt` | Classifies raw multilingual text into a structured `ParsedIntent` (category + slots + confidence), reusing `LocalIntentRouter` for the fast-path commands and adding follow-up ("iska"/"uska") resolution against the previous command. |
| #40 Agent planning | `agent/AgentPlanner.kt` (existing, lightly extended) | Now rejects brain-only commands (diagnostic/memory/pause/resume/retry/stats) from appearing inside a multi-step plan, since those must go through the ViewModel-level interception, not the executor. |
| #41 Capability router | `agent/CapabilityRegistry.kt` | Live AVAILABLE/UNAVAILABLE/REQUIRES_PERMISSION snapshot of every tool, built from real permission/service/network checks. The prompt is given this snapshot so the model is told not to claim an unavailable capability. |
| #42/#43 Verification & recovery | `agent/TaskEngine.kt` | Existing execute→verify→retry loop now has an explicit RECOVERING state during retries and classifies failures (`agent/AgentModels.kt: FailureReason`) into element-not-found / app-not-responding / permission-missing / network-unavailable / unknown. |
| #44 Learning/evaluation | `agent/LearningEngine.kt` | Records every task/command outcome, computes success rate and common failures, and flags a request that has failed 2+ times. Pure data — never modifies code or bypasses a restriction based on what it learns. |
| #45 Knowledge base | `assets/knowledge/*.json` (8 category files, 35 entries), `data/local/db/entity/KnowledgeEntity.kt`, `dao/KnowledgeDao.kt`, `repository/KnowledgeRepository.kt`, `agent/KnowledgeBase.kt` | Real content (not placeholders) about how JARVIS/Android/automation/voice/the system actually behave, seeded from data files into Room on first run, retrieved by keyword relevance and injected into the prompt only when it matches the question. Also answers directly when offline instead of just apologizing. |
| #46 Conversational intelligence | `IntentEngine` categories (CONFIRMATION/CANCELLATION/FOLLOW_UP/CLARIFICATION) + `RetryLastTask` command | "phir se karo" / "try again" now actually re-runs the last request in both the chat ViewModel and the voice overlay. |
| #47 Confirmation intelligence | `command/CommandModels.kt: requiresConfirmation()` (existing, extended) | New destructive command (`ForgetMemory`) added to the confirm-first list; fast local commands still skip confirmation only when they're genuinely low-risk. |
| #48 Self-diagnostic brain | `agent/DiagnosticEngine.kt`, `RunDiagnostic` command, Settings → "Run JARVIS diagnostic" | Checks AI, memory, voice, TTS, accessibility, automation, database, HUD, live wallpaper, network, permissions, and task-engine state — each check reads real state; nothing is guessed OK. |
| #49 Confidence | `agent/IntentModels.kt: IntentConfidence`, `AssistantViewModel.sendMessage` | A LOW-confidence, consequential intent (e.g. "open that app" with no resolvable target) now asks one clarifying question instead of guessing. |
| #50 Modular prompt architecture | `ai/PromptComponents.kt`, `ai/PromptBuilder.kt` | Prompt assembled from independent blocks (personality, security rules, response style, language, device, capability, task, memory, confidence) instead of one hardcoded string. |
| #51 Tool-aware AI | `ai/PromptBuilder.kt: AVAILABLE_TOOLS` | Every action type the model may propose has a real `CommandEngine` case; new brain commands were added to both simultaneously. |
| #52 Task state machine | `agent/AgentModels.kt: TaskStatus` | Expanded to CREATED/UNDERSTANDING/PLANNING/WAITING_PERMISSION/EXECUTING/VERIFYING/RECOVERING/PAUSED/COMPLETED/FAILED/CANCELLED. |
| #53 Interruptions | `TaskEngine.pause()/resume()`, `PauseTask`/`ResumeTask` commands | Pausing blocks the task loop (checked between steps) rather than cancelling it; resume continues from where it left off. |
| #54 Progress feedback | Existing `WallpaperEventBus` / command log (unchanged) | Already implemented before this upgrade; RECOVERING/PAUSED states now flow through it too. |
| #55 Personality | `ai/PromptComponents.kt: corePersonality()/responseStyle()` | Same tone rules as before, now their own block. |
| #56 Dashboard | `ui/screens/dashboard/{DashboardViewModel,DashboardScreen}.kt`, reachable from Settings → "Open task performance dashboard" | A real screen (not just Settings text): stat cards, a Canvas-drawn success/fail timeline over actual `TaskOutcomeEntity` rows, a most-used-commands bar chart, common failures, flagged workflows, and a live system-event log. Not added to the bottom nav bar to avoid crowding a 6-item row on a phone screen. |
| #57/#58 | This document + the table above | |

## Known gaps / deliberate simplifications

- **#45 Knowledge base** and **#56 Dashboard** were the two gaps in the first pass of this
  upgrade — both are now implemented for real (see the table above) rather than left as
  placeholders.
- **Knowledge base scope**: 35 entries across 8 categories is a solid starting set, not
  exhaustive — it covers the app's own permissions/behavior and common troubleshooting, not
  general Android knowledge unrelated to JARVIS. Adding more is just adding JSON entries to
  `assets/knowledge/*.json`, no Kotlin changes needed.
- **#58 Demonstration**: this environment has no Android SDK/emulator, so the 12 scenarios in
  the spec were verified by code review (every new command has a real parse → route → execute
  → log path, exercised by tracing the code, not by running the app) rather than by an actual
  on-device run. Treat the first Gradle build as the real test — expect to fix small integration
  issues Kotlin's exhaustiveness checks might have missed. Specifically worth checking on first
  run: the v1→v2→v3 Room migrations apply cleanly on an existing install, and the knowledge base
  seeds correctly on first launch (Settings → Run JARVIS diagnostic will show a KNOWLEDGE_BASE
  entry count once it has).

## Build

Same as before — no new Gradle plugins or dependencies were added (Room 2.6.1 already supports
the `Migration` API used here). The only new build input is `app/src/main/assets/knowledge/`,
which Gradle picks up automatically as a standard Android assets folder.

    gradle assembleDebug

The APK output is `app/build/outputs/apk/debug/app-debug.apk`.
