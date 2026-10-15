package ai.moeru.airicraft.agent.tasks;

/** Fuel committed to a batch, not merely the number of inventory items consumed. */
public final class SmeltingFuelCost {
	private SmeltingFuelCost() {}

	public static int compare(String leftId, int leftQuantity, int leftTicks,
		String rightId, int rightQuantity, int rightTicks) {
		// A whole log is the run's tool and table material; in a random-seed playtest one ingot burned the last
		// logs while eleven coal sat in the inventory. Logs burn only when no other fuel covers the batch.
		int logs = Boolean.compare(wholeLog(leftId), wholeLog(rightId));
		if (logs != 0) return logs;
		return Long.compare((long) leftQuantity * leftTicks, (long) rightQuantity * rightTicks);
	}

	private static boolean wholeLog(String id) {
		return id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae")
			|| id.equals("minecraft:bamboo_block") || id.equals("minecraft:stripped_bamboo_block");
	}
}
