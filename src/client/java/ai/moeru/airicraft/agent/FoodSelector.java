package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.modded.ItemGroups;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Automatic choices are deliberately limited to ordinary, predictable food: vanilla plus whatever {@link ItemGroups} adds. */
public final class FoodSelector {
	private FoodSelector() { }

	public static Optional<String> choose(List<Candidate> inventory, FoodPolicy.FoodChoice choice, int hunger) {
		if (inventory == null || choice == null || hunger >= 20) return Optional.empty();
		int deficit = 20 - hunger;
		return inventory.stream().filter(candidate -> candidate != null && candidate.nutrition() > 0)
			.filter(candidate -> ItemGroups.contains(ItemGroups.Group.COOKED_FOOD, candidate.itemId())
				|| choice == FoodPolicy.FoodChoice.ANY && ItemGroups.contains(ItemGroups.Group.OTHER_SAFE_FOOD, candidate.itemId()))
			.min(Comparator.comparingInt((Candidate candidate) -> Math.max(0, candidate.nutrition() - deficit))
				.thenComparingInt(candidate -> -candidate.nutrition())
				.thenComparing(Candidate::itemId))
			.map(Candidate::itemId);
	}

	/**
	 * Last-resort food for a body losing a fight with nothing else to eat. Rotten flesh usually brings hunger, but four
	 * points of food take 14 to 18, where health regenerates again. A playtest body died at 9 health and 14 food
	 * with two rotten flesh in its pack and its reflex reporting no food.
	 */
	public static Optional<String> emergency(List<Candidate> inventory, FoodPolicy.FoodChoice choice, int hunger,
		float health, float maxHealth) {
		if (inventory == null || choice != FoodPolicy.FoodChoice.ANY || hunger >= 20 || health > maxHealth / 2.0F) {
			return Optional.empty();
		}
		return inventory.stream().filter(candidate -> candidate != null && EMERGENCY_FOOD.equals(candidate.itemId()))
			.findFirst().map(Candidate::itemId);
	}

	static final String EMERGENCY_FOOD = "minecraft:rotten_flesh";

	public record Candidate(String itemId, int nutrition) { }
}
