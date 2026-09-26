package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodRuntimeTest {
	@Test void reportsMissingFoodOnceAndRetriesWhenInventoryChanges() {
		var runtime = new FoodRuntime();
		assertTrue(runtime.evaluateIdle(true, 6, 20F, 20F, List.of(), 1L).missingFood());
		assertFalse(runtime.evaluateIdle(true, 6, 20F, 20F, List.of(), 2L).missingFood());
		assertEquals(Optional.of("minecraft:bread"), runtime.evaluateIdle(true, 6, 20F, 20F,
			List.of(new FoodSelector.Candidate("minecraft:bread", 5)), 3L).itemId());
		runtime.recordAttempt(3L);
		var coolingDown = runtime.evaluateIdle(true, 6, 20F, 20F,
			List.of(new FoodSelector.Candidate("minecraft:bread", 5)), 4L);
		assertEquals(Optional.empty(), coolingDown.itemId());
		assertFalse(coolingDown.missingFood());
		assertEquals(Optional.of("minecraft:bread"), runtime.evaluateIdle(true, 6, 20F, 20F,
			List.of(new FoodSelector.Candidate("minecraft:bread", 5)), 103L).itemId());
	}

	@Test void combatHealingIgnoresIdleOffSettingButHonorsFoodChoice() {
		var runtime = new FoodRuntime();
		runtime.configure(FoodPolicy.Goal.OFF, FoodPolicy.FoodChoice.COOKED_ONLY);
		var inventory = List.of(new FoodSelector.Candidate("minecraft:beef", 3),
			new FoodSelector.Candidate("minecraft:cooked_beef", 8));
		assertEquals(Optional.of("minecraft:cooked_beef"), runtime.combatCandidate(6, 9F, 20F, inventory));
		assertEquals(Optional.empty(), runtime.combatCandidate(18, 9F, 20F, inventory));
	}

	@Test void combatCanKeepItsFoodChoiceWhileWaitingForTheNextBite() {
		var runtime = new FoodRuntime();
		var inventory = List.of(new FoodSelector.Candidate("minecraft:bread", 5));
		runtime.recordAttempt(1L);
		assertEquals(Optional.of("minecraft:bread"), runtime.combatCandidate(12, 9F, 20F, inventory));
		assertFalse(runtime.readyToEat(2L));
		assertTrue(runtime.readyToEat(41L));
	}
}
