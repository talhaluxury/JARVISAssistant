package com.jarvis.assistant.di

import android.content.Context
import com.jarvis.assistant.ai.AiService
import com.jarvis.assistant.agent.AppRegistry
import com.jarvis.assistant.agent.AgentPlanner
import com.jarvis.assistant.agent.BrainCommandExecutor
import com.jarvis.assistant.agent.CapabilityRegistry
import com.jarvis.assistant.agent.ConfirmationManager
import com.jarvis.assistant.agent.ContextManager
import com.jarvis.assistant.agent.DiagnosticEngine
import com.jarvis.assistant.agent.KnowledgeBase
import com.jarvis.assistant.agent.LearningEngine
import com.jarvis.assistant.agent.PermissionManager
import com.jarvis.assistant.agent.PhoneContextEngine
import com.jarvis.assistant.agent.TaskEngine
import com.jarvis.assistant.ai.OpenAiService
import com.jarvis.assistant.command.AndroidActionExecutor
import com.jarvis.assistant.data.local.db.JarvisDatabase
import com.jarvis.assistant.data.local.prefs.SecurePrefs
import com.jarvis.assistant.data.repository.BrainRepository
import com.jarvis.assistant.data.repository.ConversationRepository
import com.jarvis.assistant.data.repository.KnowledgeRepository
import com.jarvis.assistant.data.repository.MemoryRepository
import com.jarvis.assistant.search.WebSearchService
import com.jarvis.assistant.voice.SpeechToTextManager
import com.jarvis.assistant.voice.TextToSpeechManager
import com.jarvis.assistant.voice.VoiceActivityDetector
import com.jarvis.assistant.core.config.FeatureFlags
import com.jarvis.assistant.core.event.JarvisEventBus
import com.jarvis.assistant.core.state.JarvisStateStore
import com.jarvis.assistant.core.state.StateSnapshotStore
import com.jarvis.assistant.core.state.SystemSnapshotProvider
import com.jarvis.assistant.core.skill.SkillRegistry
import com.jarvis.assistant.core.skill.BuiltInSkills
import com.jarvis.assistant.core.resource.ResourceManager
import com.jarvis.assistant.core.resource.ResourceLockManager
import com.jarvis.assistant.core.time.TimeAwareness
import com.jarvis.assistant.core.cache.SmartCommandCache
import com.jarvis.assistant.core.workflow.WorkflowStore
import com.jarvis.assistant.core.verification.VerificationEngine
import com.jarvis.assistant.core.observability.PerformanceTelemetry
import com.jarvis.assistant.core.recovery.CrashRecoveryStore
import com.jarvis.assistant.core.security.RiskEngine
import com.jarvis.assistant.core.security.ContextSanitizer
import com.jarvis.assistant.core.simulation.SimulationEngine
import com.jarvis.assistant.core.mission.MissionManager
import com.jarvis.assistant.core.workflow.WorkflowValidator
import com.jarvis.assistant.core.observability.AuditLog
import com.jarvis.assistant.core.recovery.RecoveryPolicy
import com.jarvis.assistant.data.local.prefs.createPersistentTradingSettingsStore
import com.jarvis.assistant.trading.BrokerAdapter
import com.jarvis.assistant.trading.DemoBrokerAdapter
import com.jarvis.assistant.trading.EmergencyStopController
import com.jarvis.assistant.trading.ForexBrainCommandExecutor
import com.jarvis.assistant.trading.InMemoryTradeJournal
import com.jarvis.assistant.trading.MarketDataService
import com.jarvis.assistant.trading.MarketStructureEngine
import com.jarvis.assistant.trading.MultiTimeframeEngine
import com.jarvis.assistant.trading.PaperTradingEngine
import com.jarvis.assistant.trading.RiskManagementEngine
import com.jarvis.assistant.trading.SignalConfidenceEngine
import com.jarvis.assistant.trading.TradeJournal
import com.jarvis.assistant.quotex.QuotexModule
import com.jarvis.assistant.wingo.WinGoModule

/**
 * Simple hand-written dependency container. Keeping this manual (instead of
 * Hilt/Dagger annotation processing) keeps the Gradle/KAPT setup lighter and
 * easier to build reliably from GitHub Actions without Android Studio.
 */
class AppContainer(context: Context) {

    // Central runtime primitives: event-driven state, risk, simulation, missions and audit.
    val eventBus = JarvisEventBus()
    val stateStore = JarvisStateStore(eventBus)
    val stateSnapshots = StateSnapshotStore()
    val systemSnapshotProvider by lazy { SystemSnapshotProvider(context, capabilityRegistry) }
    val skillRegistry = SkillRegistry()
    val riskEngine = RiskEngine()
    val simulationEngine = SimulationEngine()
    val missionManager = MissionManager()
    val workflowValidator = WorkflowValidator()
    val auditLog = AuditLog()
    val recoveryPolicy = RecoveryPolicy()
    val resourceManager = ResourceManager(context)
    val resourceLocks = ResourceLockManager()
    val timeAwareness = TimeAwareness()
    val commandCache = SmartCommandCache<String, String>()
    val workflowStore = WorkflowStore(context)
    val verificationEngine = VerificationEngine()
    val performanceTelemetry = PerformanceTelemetry()
    val crashRecoveryStore = CrashRecoveryStore(context)
    val featureFlags = FeatureFlags()

    val securePrefs = SecurePrefs(context)

    private val database = JarvisDatabase.getInstance(context)

    val memoryRepository = MemoryRepository(database.memoryDao())
    val conversationRepository = ConversationRepository(database.conversationDao(), database.messageDao())
    val brainRepository = BrainRepository(
        database.commandHistoryDao(), database.taskOutcomeDao(), database.systemEventDao(), database.preferenceDao()
    )

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
    val agentPlanner = AgentPlanner()
    val taskEngine = TaskEngine(actionExecutor, phoneContextEngine, resourceLocks)

    // Iterative multi-agent runner: unlike agentPlanner's single-shot plan, this asks the AI
    // for one action at a time and adapts based on real results (see AutonomousAgentOrchestrator).
    val agentRouter = com.jarvis.assistant.agent.AgentRouter()
    val autonomousAgentOrchestrator = com.jarvis.assistant.agent.AutonomousAgentOrchestrator(
        aiService = aiService,
        actionExecutor = actionExecutor,
        riskEngine = riskEngine,
        missionManager = missionManager,
        auditLog = auditLog,
        router = agentRouter
    )

    // #35-58 AI Brain layer: capability truth, structured intent/context, memory relevance,
    // self-diagnostics, and controlled learning — see individual class docs for the spec section.
    val capabilityRegistry = CapabilityRegistry(context, securePrefs)
    init { BuiltInSkills.registerInto(skillRegistry) { capabilityRegistry.snapshot() } }
    val contextManager = ContextManager(phoneContextEngine, capabilityRegistry, memoryRepository)
    val learningEngine = LearningEngine(brainRepository)

    // #45 KNOWLEDGE BASE — bundled facts (assets/knowledge/*.json) seeded once at app start
    // (see JarvisApplication.onCreate) and retrieved by relevance, kept fully separate from
    // the user's own personal memory.
    val knowledgeRepository = KnowledgeRepository(context, database.knowledgeDao())
    val knowledgeBase = KnowledgeBase(knowledgeRepository)

    val diagnosticEngine = DiagnosticEngine(context, securePrefs, capabilityRegistry, speechToTextManager, brainRepository, knowledgeBase)

    // Forex trading module (see com.jarvis.assistant.trading). Wired to DemoBrokerAdapter by
    // default — a real broker (OandaBrokerAdapter today) is a drop-in replacement for
    // forexBroker once verified against a real account (see docs/OANDA_INTEGRATION_CHECKLIST.md);
    // nothing else on this list needs to change to swap it, that's the whole point of
    // BrokerAdapter being an interface.
    val forexBroker: BrokerAdapter = DemoBrokerAdapter()
    val forexMarketDataService = MarketDataService(forexBroker)
    val forexStructureEngine = MarketStructureEngine()
    val forexMultiTimeframeEngine = MultiTimeframeEngine(forexMarketDataService, forexStructureEngine)
    val forexSignalEngine = SignalConfidenceEngine()
    val forexRiskEngine = RiskManagementEngine()
    // In-memory only for now — every trade/signal decision is lost on process death. Swapping
    // in a Room-backed TradeJournal implementation is a follow-up, not done here, since it needs
    // its own migration/schema thought rather than being rushed in alongside this wiring.
    val forexTradeJournal: TradeJournal = InMemoryTradeJournal()
    val forexTradingSettingsStore = createPersistentTradingSettingsStore(securePrefs)
    val forexEmergencyStop = EmergencyStopController { forexTradingSettingsStore.current().risk }
    val paperTradingEngine = PaperTradingEngine(
        forexMarketDataService, forexMultiTimeframeEngine, forexSignalEngine, forexRiskEngine,
        forexBroker, forexTradeJournal, forexEmergencyStop
    )
    val forexBrainCommandExecutor = ForexBrainCommandExecutor(
        forexTradingSettingsStore, paperTradingEngine, forexTradeJournal, forexEmergencyStop, forexBroker
    )

    val brainCommandExecutor = BrainCommandExecutor(diagnosticEngine, memoryRepository, learningEngine, forexBrainCommandExecutor)

    /** WinGo Big/Small analyzer: separate DB, no shared state with the modules above. */
    val winGo: WinGoModule by lazy { WinGoModule(context) }

    /** Quotex chart analyzer: analysis only (no order path), separate DB. */
    val quotex: QuotexModule by lazy { QuotexModule(context) }
}
