package ai.moeru.airicraft.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FoodSelectorTest {
	@Test void cookedChoiceSkipsRawFoodAndSpecialConsumables() {
		var inventory = List.of(new FoodSelector.Candidate("minecraft:beef", 3),
			new FoodSelector.Candidate("minecraft:golden_apple", 4),
			new FoodSelector.Candidate("minecraft:pufferfish", 1),
			new FoodSelector.Candidate("minecraft:cooked_beef", 8));
		assertEquals(Optional.of("minecraft:cooked_beef"), FoodSelector.choose(inventory,
			FoodPolicy.FoodChoice.COOKED_ONLY, 6));
	}

	@Test void anyChoiceMinimizesFoodWasteAndReservesSpecialFood() {
		assertEquals(Optional.of("minecraft:bread"), FoodSelector.choose(List.of(
			new FoodSelector.Candidate("minecraft:golden_apple", 4),
			new FoodSelector.Candidate("minecraft:cooked_beef", 8),
			new FoodSelector.Candidate("minecraft:bread", 5)), FoodPolicy.FoodChoice.ANY, 17));
		assertEquals(Optional.of("minecraft:beef"), FoodSelector.choose(List.of(
			new FoodSelector.Candidate("minecraft:pufferfish", 1),
			new FoodSelector.Candidate("minecraft:beef", 3)), FoodPolicy.FoodChoice.ANY, 17));
	}
}
