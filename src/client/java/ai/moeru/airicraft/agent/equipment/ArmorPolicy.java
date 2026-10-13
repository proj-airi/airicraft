package ai.moeru.airicraft.agent.equipment;

import java.util.List;
import java.util.Objects;

/**
 * The planner's say over automatic armor: off entirely, or on with item ids it keeps in the
 * inventory (armor meant for another player, say). Lasts until agent session reset.
 */
public record ArmorPolicy(boolean enabled, List<String> reservedItemIds) {
	public static final int MAX_RESERVED = 16;
	private static final ArmorPolicy DEFAULT = new ArmorPolicy(true, List.of());

	public ArmorPolicy {
		reservedItemIds = List.copyOf(Objects.requireNonNull(reservedItemIds, "reservedItemIds")).stream().distinct().toList();
		if (reservedItemIds.size() > MAX_RESERVED) throw new IllegalArgumentException("at most " + MAX_RESERVED + " reservedItemIds");
	}

	public static ArmorPolicy defaults() {
		return DEFAULT;
	}

	boolean reserves(String itemId) {
		return reservedItemIds.contains(itemId);
	}
}
