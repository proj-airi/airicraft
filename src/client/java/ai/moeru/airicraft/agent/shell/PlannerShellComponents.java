package ai.moeru.airicraft.agent.shell;

import ai.moeru.airicraft.agent.dialogue.DialogueRuntime;
import ai.moeru.airicraft.agent.llm.CurrentViewVisionService;
import ai.moeru.airicraft.agent.recording.PlannerCallJournal;

public record PlannerShellComponents(
	CurrentViewVisionService visionService,
	DialogueRuntime dialogueRuntime,
	PlannerShellJournal plannerJournal,
	PlannerCallJournal plannerCallJournal,
	ai.moeru.airicraft.agent.llm.PlannerOrchestrator controllerPlanner,
	ai.moeru.airicraft.agent.llm.RulesToolProvider rulesTools
) {
}
