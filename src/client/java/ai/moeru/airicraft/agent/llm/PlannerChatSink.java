package ai.moeru.airicraft.agent.llm;

/** Sends one planner-authored line to Minecraft chat. */
public interface PlannerChatSink {
	PlannerChatSink NO_OP = text -> {
	};

	void say(String text);
}
