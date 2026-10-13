package ai.moeru.airicraft.agent.durability;

/**
 * One damageable item in the player's inventory. {@code kind} groups interchangeable tools ({@code pickaxe},
 * {@code axe}, {@code shovel}, {@code hoe}, {@code sword}, {@code shears}); anything else is {@code other}.
 */
public record ToolStack(int slot, String itemId, String kind, int remaining, int maximum) {
	public static final String OTHER = "other";

	public boolean tool() {
		return !OTHER.equals(kind);
	}
}
