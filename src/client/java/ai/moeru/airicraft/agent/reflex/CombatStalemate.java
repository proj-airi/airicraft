package ai.moeru.airicraft.agent.reflex;

import net.minecraft.world.phys.Vec3;
import java.util.Map;

/** Track closest approach to distant threats; retain their identity while the planner chooses a tactic. */
record CombatStalemate(Phase phase, long sinceTick, Vec3 playerPosition, Map<String, Vec3> threats) {
	static final int STALLED_TICKS = 400;
	enum Phase { APPROACHING, DEFERRED }

	CombatStalemate {
		threats = Map.copyOf(threats);
	}

	static CombatStalemate observe(CombatStalemate previous, long tick, Vec3 playerPosition,
		Map<String, Vec3> threats, boolean immediateDanger) {
		if (immediateDanger || threats.isEmpty()) return null;
		if (previous == null || !previous.threats.keySet().equals(threats.keySet())) {
			return new CombatStalemate(Phase.APPROACHING, tick, playerPosition, threats);
		}
		if (previous.phase == Phase.DEFERRED) return previous;
		// Walking in circles, jumping and falling must not keep resetting the stall timer.
		if (nearestDistance(playerPosition, threats) <= nearestDistance(previous.playerPosition, previous.threats) - 1D) {
			return new CombatStalemate(Phase.APPROACHING, tick, playerPosition, threats);
		}
		return tick - previous.sinceTick >= STALLED_TICKS
			? new CombatStalemate(Phase.DEFERRED, previous.sinceTick, playerPosition, threats) : previous;
	}

	private static double nearestDistance(Vec3 player, Map<String, Vec3> threats) {
		return threats.values().stream().mapToDouble(player::distanceTo).min().orElseThrow();
	}

	boolean deferred() { return phase == Phase.DEFERRED; }
}
