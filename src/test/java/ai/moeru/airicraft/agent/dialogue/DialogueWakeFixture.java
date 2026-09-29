package ai.moeru.airicraft.agent.dialogue;

import ai.moeru.airicraft.agent.AgentConfig;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.llm.CurrentInventoryTool;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionTool;
import ai.moeru.airicraft.agent.llm.LlmBackend;
import ai.moeru.airicraft.agent.llm.OpenAiCompatibleChatClient;
import ai.moeru.airicraft.agent.llm.PlannerCompactionService;
import ai.moeru.airicraft.agent.llm.PlannerContextAggregator;
import ai.moeru.airicraft.agent.llm.PlannerExecutor;
import ai.moeru.airicraft.agent.llm.PlannerActionToolExecutor;
import ai.moeru.airicraft.agent.llm.PlannerLifecycleListener;
import ai.moeru.airicraft.agent.llm.PlannerOrchestrator;
import ai.moeru.airicraft.agent.llm.PlannerToolExecutionObserver;
import ai.moeru.airicraft.agent.llm.PlannerChatSink;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import ai.moeru.airicraft.agent.llm.PlannerVisionMode;
import ai.moeru.airicraft.agent.observability.NoopObservability;

import java.time.Clock;


final class DialogueWakeFixture {
	static DialogueRuntime create(LlmBackend backend, CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode, ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore goal, Clock clock) {
		return create(backend, visionTool, visionMode, goal, clock, PlannerToolRegistry.empty());
	}
	static DialogueRuntime create(LlmBackend backend, CurrentViewVisionTool visionTool,
		PlannerVisionMode visionMode, ai.moeru.airicraft.agent.llm.goal.PlannerGoalStore goal, Clock clock, PlannerToolRegistry tools) {
		AgentConfig.LlmConfig config = AgentConfig.LlmConfig.defaults();
		PlannerOrchestrator orchestrator = new PlannerOrchestrator(
			new PlannerExecutor(backend),
			new PlannerCompactionService(new OpenAiCompatibleChatClient(config)),
			new PlannerContextAggregator(
				clock,
				config.plannerCompactionTriggerTokens(),
				config.plannerPendingSemanticEventCap(),
				visionMode, tools
			),
			visionTool,
			CurrentInventoryTool.disabled(),
			visionMode,
			config.visionImageDetail(),
			config.plannerSessionMaxConcurrentAttempts(),
			config.plannerSessionCoalesceStepMillis(),
			config.plannerSessionCoalesceMinMillis(),
			config.plannerSessionCoalesceMaxMillis(),
			clock,
			NoopObservability.INSTANCE,
			PlannerLifecycleListener.NO_OP,
			new AgentDebugRecorder(),
			PlannerActionToolExecutor.DISABLED,
			PlannerChatSink.NO_OP,
			tools,
			PlannerToolExecutionObserver.NO_OP
		);
		return new DialogueRuntime(orchestrator, 8, clock, goal);
	}

}
