package ai.moeru.airicraft.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Automatic choices are deliberately limited to ordinary, predictable vanilla food. */
public final class FoodSelector {
	private static final Set<String> COOKED = Set.of(
		"minecraft:bread", "minecraft:baked_potato", "minecraft:cooked_beef", "minecraft:cooked_porkchop",
		"minecraft:cooked_mutton", "minecraft:cooked_chicken", "minecraft:cooked_rabbit",
		"minecraft:cooked_cod", "minecraft:cooked_salmon", "minecraft:mushroom_stew",
		"minecraft:beetroot_soup", "minecraft:rabbit_stew", "minecraft:pumpkin_pie", "minecraft:cookie"
	);
	private static final Set<String> OTHER_SAFE = Set.of(
		"minecraft:apple", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot", "minecraft:melon_slice",
		"minecraft:sweet_berries", "minecraft:glow_berries", "minecraft:dried_kelp",
		"minecraft:beef", "minecraft:porkchop", "minecraft:mutton", "minecraft:rabbit",
		"minecraft:cod", "minecraft:salmon"
	);

	private FoodSelector() { }

	public static Optional<String> choose(List<Candidate> inventory, FoodPolicy.FoodChoice choice, int hunger) {
		if (inventory == null || choice == null || hunger >= 20) return Optional.empty();
		int deficit = 20 - hunger;
		return inventory.stream().filter(candidate -> candidate != null && candidate.nutrition() > 0)
			.filter(candidate -> COOKED.contains(candidate.itemId())
				|| choice == FoodPolicy.FoodChoice.ANY && OTHER_SAFE.contains(candidate.itemId()))
			.min(Comparator.comparingInt((Candidate candidate) -> Math.max(0, candidate.nutrition() - deficit))
				.thenComparingInt(candidate -> -candidate.nutrition())
				.thenComparing(Candidate::itemId))
			.map(Candidate::itemId);
	}

	public record Candidate(String itemId, int nutrition) { }
}
