package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodPolicyTest {
	@Test void movementGoalWaitsUntilSprintingIsLost() {
		var policy = FoodPolicy.defaults();
		assertFalse(policy.shouldEatIdle(7, 20F, 20F));
		assertTrue(policy.shouldEatIdle(6, 20F, 20F));
		assertEquals(FoodPolicy.FoodChoice.ANY, policy.foodChoice());
	}

	@Test void healingGoalFeedsOnlyWhileHungerBlocksRegeneration() {
		var policy = new FoodPolicy(FoodPolicy.Goal.HEAL, FoodPolicy.FoodChoice.COOKED_ONLY);
		assertTrue(policy.shouldEatIdle(17, 10F, 20F));
		assertFalse(policy.shouldEatIdle(18, 10F, 20F));
		assertFalse(policy.shouldEatIdle(6, 20F, 20F));
		assertFalse(new FoodPolicy(FoodPolicy.Goal.OFF, FoodPolicy.FoodChoice.ANY)
			.shouldEatIdle(0, 1F, 20F));
	}

	@Test void combatHealingRequiresSevereInjuryAndMissingRegenerationFood() {
		var policy = new FoodPolicy(FoodPolicy.Goal.OFF, FoodPolicy.FoodChoice.ANY);
		assertTrue(policy.shouldSeekCombatHeal(17, 9F, 20F));
		assertFalse(policy.shouldSeekCombatHeal(17, 10F, 20F));
		assertFalse(policy.shouldSeekCombatHeal(18, 4F, 20F));
	}
}
