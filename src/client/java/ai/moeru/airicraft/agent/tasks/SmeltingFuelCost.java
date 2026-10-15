package ai.moeru.airicraft.agent.tasks;

/** Fuel committed to a batch, not merely the number of inventory items consumed. */
public final class SmeltingFuelCost {
	private SmeltingFuelCost() {}

	public static int compare(String leftId, int leftQuantity, int leftTicks,
		String rightId, int rightQuantity, int rightTicks) {
		// Wood is building and tool material; in a random-seed playtest one ingot burned the last logs while
		// eleven coal sat in the inventory. Coal and other spare fuels go first, planks next, whole logs last.
		int material = Integer.compare(woodTier(leftId), woodTier(rightId));
		if (material != 0) return material;
		return Long.compare((long) leftQuantity * leftTicks, (long) rightQuantity * rightTicks);
	}

	static int woodTier(String id) {
		if (wholeLog(id)) return 2;
		return id.endsWith("_planks") ? 1 : 0;
	}

	private static boolean wholeLog(String id) {
		return id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae")
			|| id.equals("minecraft:bamboo_block") || id.equals("minecraft:stripped_bamboo_block");
	}
}
