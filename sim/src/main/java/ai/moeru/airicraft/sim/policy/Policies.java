package ai.moeru.airicraft.sim.policy;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Registry of available policies by id. */
public final class Policies {
	private static final Map<String, Supplier<CombatPolicy>> REGISTRY = Map.of(
			"baseline-melee", BaselineMeleePolicy::new,
			"idle", IdlePolicy::new);

	private Policies() {}

	public static CombatPolicy create(String id) {
		Supplier<CombatPolicy> supplier = REGISTRY.get(id);
		if (supplier == null) {
			throw new IllegalArgumentException("unknown policy '" + id + "'; available: " + REGISTRY.keySet());
		}
		return supplier.get();
	}

	public static Set<String> ids() {
		return REGISTRY.keySet();
	}
}
