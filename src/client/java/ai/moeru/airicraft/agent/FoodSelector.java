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

	public record Candidate(String itemId, int nutrition) { }
}
