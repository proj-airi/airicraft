package ai.moeru.airicraft.agent.food;

import ai.moeru.airicraft.agent.events.EventCause;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FoodOutcomeIndexTest {
	@Test void returnsFirstOutcomeStrictlyAfterSequenceAcrossBothTypes() {
		var index = new FoodOutcomeIndex(3);
		var eaten = event(3, "food.eaten");
		var failed = event(5, "food.eat_failed");
		index.accept(eaten);
		index.accept(failed);
		assertSame(eaten, index.firstAfter(2).orElseThrow());
		assertSame(failed, index.firstAfter(3).orElseThrow());
		assertTrue(index.firstAfter(5).isEmpty());
	}

	@Test void evictsOnlyOldestFoodOutcomeAtCapacity() {
		var index = new FoodOutcomeIndex(2);
		var second = event(2, "food.eat_failed");
		var third = event(3, "food.eaten");
		index.accept(event(1, "food.eaten"));
		index.accept(second);
		index.accept(third);
		assertSame(second, index.firstAfter(0).orElseThrow());
		assertSame(third, index.firstAfter(2).orElseThrow());
	}

	@Test void unrelatedEventsDoNotDisplaceFoodOutcomes() {
		var index = new FoodOutcomeIndex(1);
		var eaten = event(1, "food.eaten");
		index.accept(eaten);
		index.accept(event(2, "interaction.example"));
		assertSame(eaten, index.firstAfter(0).orElseThrow());
	}

	@Test void clearRemovesPriorOutcomes() {
		var index = new FoodOutcomeIndex(2);
		index.accept(event(1, "food.eaten"));
		index.clear();
		assertTrue(index.firstAfter(0).isEmpty());
	}

	private static SemanticEvent event(long sequence, String type) {
		return new SemanticEvent(sequence, 8, 9, type, Map.of("itemId", "minecraft:bread"),
			"FoodRuntime", EventCause.work("OPERATION:eat"));
	}
}
