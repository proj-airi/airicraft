package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.AgentConfig;
import net.minecraft.client.MinecraftClient;

/** What a sensor may read for one sample: the tick, the client (null in headless tests) and its budgets. */
public record SensorContext(long tick, MinecraftClient client, AgentConfig.PerceptionConfig budgets) {
	public SensorContext {
		budgets = budgets == null ? AgentConfig.PerceptionConfig.defaults() : budgets;
	}
}
