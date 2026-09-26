package ai.moeru.airicraft.agent;

import java.util.List;
import java.util.Optional;

/** State for session-scoped automatic eating decisions. Gameplay actuation stays with PlayerItemUseController. */
public final class FoodRuntime {
	private FoodPolicy policy = FoodPolicy.defaults();
	private boolean missingFoodReported;
	private long nextAttemptTick;

	public FoodPolicy policy() { return policy; }

	public FoodPolicy configure(FoodPolicy.Goal goal, FoodPolicy.FoodChoice choice) {
		policy = new FoodPolicy(goal, choice);
		missingFoodReported = false;
		return policy;
	}

	public Decision evaluateIdle(boolean allowed, int hunger, float health, float maxHealth,
		List<FoodSelector.Candidate> inventory, long tick) {
		if (!allowed || !policy.shouldEatIdle(hunger, health, maxHealth)) {
			if (allowed) missingFoodReported = false;
			return Decision.none();
		}
		Optional<String> item = FoodSelector.choose(inventory, policy.foodChoice(), hunger);
		if (item.isEmpty()) {
			boolean first = !missingFoodReported;
			missingFoodReported = true;
			return new Decision(Optional.empty(), first);
		}
		missingFoodReported = false;
		return new Decision(tick >= nextAttemptTick ? item : Optional.empty(), false);
	}

	public Optional<String> combatCandidate(int hunger, float health, float maxHealth,
		List<FoodSelector.Candidate> inventory) {
		return policy.shouldSeekCombatHeal(hunger, health, maxHealth)
			? FoodSelector.choose(inventory, policy.foodChoice(), hunger) : Optional.empty();
	}

	public boolean readyToEat(long tick) { return tick >= nextAttemptTick; }

	public void recordAttempt(long tick) { nextAttemptTick = tick + 40L; }

	public void reset() {
		policy = FoodPolicy.defaults();
		missingFoodReported = false;
		nextAttemptTick = 0L;
	}

	public record Decision(Optional<String> itemId, boolean missingFood) {
		private static Decision none() { return new Decision(Optional.empty(), false); }
	}
}
