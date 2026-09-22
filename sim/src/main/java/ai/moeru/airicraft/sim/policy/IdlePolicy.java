package ai.moeru.airicraft.sim.policy;

import ai.moeru.airicraft.sim.input.Intent;
import com.google.gson.JsonObject;

/** Does nothing — control/baseline for episode validation and damage-taking tests. */
public final class IdlePolicy implements CombatPolicy {
	@Override
	public String id() {
		return "idle";
	}

	@Override
	public void reset() {}

	@Override
	public Intent decide(JsonObject observation) {
		return Intent.IDLE;
	}
}
