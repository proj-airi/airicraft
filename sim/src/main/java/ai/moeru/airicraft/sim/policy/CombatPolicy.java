package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonObject;

/**
 * A melee combat policy. Receives a privileged-state observation (possibly stale
 * by the configured obs delay) and returns one tick of semantic intent. The
 * optimizer may only change implementations of this interface.
 */
public interface CombatPolicy {
	String id();

	/** Called once when the episode resets. */
	void reset();

	Intent decide(JsonObject observation);
}
