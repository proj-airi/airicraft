package ai.moeru.airicraft.agent.reflex;

import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.MotorIntent;
import ai.moeru.airicraft.navigation.MovementPolicy;
import ai.moeru.airicraft.navigation.Path;
import ai.moeru.airicraft.navigation.PathFollower;
import ai.moeru.airicraft.navigation.Step;
import ai.moeru.airicraft.navigation.TerrainView;

import java.util.List;
import java.util.Map;

/**
 * Runs navigation-core's executor for one ascend or descend, translating its intent into keys
 * relative to the threat-facing heading. It never breaks, places or opens anything.
 */
final class CombatTraversal {
	private static final int MAX_TICKS = 60;

	private sealed interface State permits Idle, Active { }
	private record Idle(String outcome) implements State { }
	private record Active(PathFollower follower, Step step, CombatPositioning.Cell destination, long startedTick) implements State { }
	record Control(CombatPositioning.Steering steering, boolean jump, boolean sneak) { }
	private State state = new Idle("idle");

	CombatPositioning.Cell destination() { return state instanceof Active a ? a.destination() : null; }

	void start(Step step, CombatPositioning.Cell destination, long tick) {
		state = new Active(new PathFollower(new Path(step.from(), List.of(step), step.cost())), step, destination, tick);
	}

	Map<String, Object> evidence() {
		if (state instanceof Active a) return Map.of("phase", a.step().type().name(), "destination", a.destination(), "startedTick", a.startedTick());
		return Map.of("phase", ((Idle) state).outcome());
	}

	Control tick(BodyState body, TerrainView live, MovementPolicy policy, double facingX, double facingZ, long tick) {
		if (!(state instanceof Active a)) return stopped();
		if (tick - a.startedTick() > MAX_TICKS) {
			state = new Idle("failed: age=" + (tick - a.startedTick()));
			return stopped();
		}
		PathFollower.Tick next = a.follower().tick(body, live, policy);
		if (next.intent().action() != null) {
			state = new Idle("rejected_block_interaction");
			return stopped();
		}
		switch (next.status()) {
			case ARRIVED -> {
				state = new Idle("SUCCESS");
				return stopped();
			}
			case REPLAN, FAILED -> {
				state = new Idle("failed: " + next.detail());
				return stopped();
			}
			case RUNNING -> { }
		}
		MotorIntent intent = next.intent();
		var steering = CombatPositioning.steering(body.x(), body.z(), body.x() + intent.moveX(), body.z() + intent.moveZ(), facingX, facingZ);
		return new Control(steering, intent.jump(), intent.sneak());
	}

	private static Control stopped() { return new Control(new CombatPositioning.Steering(false, false, false, false), false, false); }
}
