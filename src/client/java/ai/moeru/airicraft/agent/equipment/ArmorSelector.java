package ai.moeru.airicraft.agent.equipment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Picks carried armor to wear, the way a player would: the best piece per slot by protection,
 * then by remaining durability. Pure, so the choice is testable without a client.
 */
public final class ArmorSelector {
	/** Pieces this close to breaking are left in the inventory rather than swapped in. */
	static final int MIN_REMAINING_DURABILITY = 4;

	private ArmorSelector() {
	}

	/**
	 * @param slot          equipment slot name: head, chest, legs or feet
	 * @param inventorySlot backing inventory index, or -1 for the piece currently worn
	 * @param toughness     armor toughness plus one per level of the Protection enchantment, which reduces
	 *                      damage about as much as one armor point
	 * @param remaining     remaining durability; Integer.MAX_VALUE for unbreakable pieces
	 * @param locked        worn piece that cannot come off (curse of binding), or a cursed carried piece
	 */
	public record Piece(String slot, int inventorySlot, String itemId, double armor, double toughness, int remaining, boolean locked) {
		boolean worn() {
			return inventorySlot < 0;
		}
	}

	public record Swap(String slot, int inventorySlot, String itemId, String replacedItemId) {
	}

	/** Swaps the policy allows: none when disabled, and never a carried piece it reserves. */
	public static List<Swap> choose(List<Piece> pieces, ArmorPolicy policy) {
		if (!policy.enabled()) return List.of();
		return choose(pieces.stream().filter(piece -> piece.worn() || !policy.reserves(piece.itemId())).toList());
	}

	/** One swap per slot whose best carried piece protects more than what is worn there. */
	public static List<Swap> choose(List<Piece> pieces) {
		Map<String, Piece> worn = new LinkedHashMap<>();
		Map<String, Piece> best = new LinkedHashMap<>();
		for (Piece piece : pieces) {
			if (piece.worn()) {
				worn.put(piece.slot(), piece);
				continue;
			}
			if (piece.locked() || piece.armor() <= 0 || piece.remaining() < MIN_REMAINING_DURABILITY) continue;
			best.merge(piece.slot(), piece, (a, b) -> ORDER.compare(a, b) >= 0 ? a : b);
		}
		List<Swap> swaps = new ArrayList<>();
		for (Piece candidate : best.values()) {
			Piece current = worn.get(candidate.slot());
			// A worn zero-armor wearable (elytra, carved pumpkin) was put on for its use; leave it.
			if (current != null && (current.locked() || current.armor() <= 0 || protection(candidate) <= protection(current))) continue;
			swaps.add(new Swap(candidate.slot(), candidate.inventorySlot(), candidate.itemId(), current == null ? null : current.itemId()));
		}
		return List.copyOf(swaps);
	}

	private static double protection(Piece piece) {
		return piece.armor() + piece.toughness();
	}

	private static final Comparator<Piece> ORDER = Comparator.comparingDouble(ArmorSelector::protection)
		.thenComparingInt(Piece::remaining);
}
