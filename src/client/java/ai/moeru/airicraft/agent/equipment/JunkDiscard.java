package ai.moeru.airicraft.agent.equipment;

import ai.moeru.airicraft.modded.ItemGroups;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Throws away surplus throwaway blocks (netherrack, cobblestone, dirt and the like) that route tunnelling
 * picks up, as a player empties their pockets on the move: ten minutes of Nether travel filled eight
 * slots with netherrack. Keeps two stacks of each for bridging and pillaring, one when the inventory is
 * nearly full, and throws only while travelling so the stack is left behind before its pickup delay
 * ends; dropping it standing still just picks it back up. Mining and gathering jobs keep everything.
 * Works through the player's own inventory menu, like {@link ArmorAutoEquip}. Client thread only.
 */
public final class JunkDiscard {
	static final int KEEP = 128;
	static final int KEEP_WHEN_FULL = 64;
	/** At or below this many free main-inventory slots the inventory counts as nearly full. */
	static final int NEARLY_FULL_FREE_SLOTS = 3;
	private static final long CHECK_INTERVAL_TICKS = 20L;
	/** Walking pace: a body slowed by digging a route stays near what it throws and picks it back up. */
	private static final double MIN_TRAVEL_SPEED_SQUARED = 0.18 * 0.18;

	private long nextCheckTick;

	public void reset() {
		nextCheckTick = 0L;
	}

	/** One stack, from {@code inventorySlot} of the backing inventory, with {@code kept} left of that item. */
	public record Discard(int inventorySlot, String itemId, int count, int kept) {
	}

	record Stack(int inventorySlot, String itemId, int count) {
	}

	/** Throws at most one stack per check while {@code travelling}; empty when nothing was thrown. */
	public Optional<Discard> tick(Minecraft minecraft, long tick, boolean travelling) {
		if (!travelling || tick < nextCheckTick) return Optional.empty();
		nextCheckTick = tick + CHECK_INTERVAL_TICKS;
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (player == null || minecraft.gameMode == null || player.isDeadOrDying() || player.isSpectator()) return Optional.empty();
		InventoryMenu menu = player.inventoryMenu;
		if (player.containerMenu != menu || !menu.getCarried().isEmpty()) return Optional.empty();
		var motion = player.getDeltaMovement();
		if (motion.x * motion.x + motion.z * motion.z < MIN_TRAVEL_SPEED_SQUARED || ai.moeru.airicraft.agent.control.Actuator.breakingForNavigation()
			|| minecraft.gameMode.isDestroying()) return Optional.empty();
		List<Stack> stacks = new ArrayList<>();
		for (int index = 0; index < Inventory.INVENTORY_SIZE; index++) {
			ItemStack stack = player.getInventory().getItem(index);
			stacks.add(new Stack(index, stack.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
		}
		Optional<Discard> discard = choose(stacks, player.getInventory().getSelectedSlot(),
			id -> ItemGroups.contains(ItemGroups.Group.THROWAWAY_BLOCKS, id));
		if (discard.isEmpty()) return Optional.empty();
		int before = player.getInventory().getItem(discard.get().inventorySlot()).getCount();
		minecraft.gameMode.handleInventoryMouseClick(menu.containerId, ArmorAutoEquip.menuSlot(discard.get().inventorySlot()), 1, ClickType.THROW, player);
		return player.getInventory().getItem(discard.get().inventorySlot()).getCount() < before ? discard : Optional.empty();
	}

	/**
	 * The stack to throw: of the junk item with the most over its allowance, the largest stack that fits
	 * within that surplus, never the selected hotbar slot. Empty when nothing is over its allowance.
	 */
	static Optional<Discard> choose(List<Stack> inventory, int selectedSlot, Predicate<String> junk) {
		int free = 0;
		Map<String, Integer> totals = new HashMap<>();
		for (Stack stack : inventory) {
			if (stack.itemId() == null || stack.count() <= 0) {
				if (stack.inventorySlot() >= 0) free++;
				continue;
			}
			if (junk.test(stack.itemId())) totals.merge(stack.itemId(), stack.count(), Integer::sum);
		}
		int keep = free <= NEARLY_FULL_FREE_SLOTS ? KEEP_WHEN_FULL : KEEP;
		Discard best = null;
		int bestSurplus = 0;
		for (var entry : totals.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
			int surplus = entry.getValue() - keep;
			if (surplus <= bestSurplus) continue;
			Stack chosen = null;
			for (Stack stack : inventory) {
				if (!entry.getKey().equals(stack.itemId()) || stack.inventorySlot() == selectedSlot || stack.count() > surplus) continue;
				if (chosen == null || stack.count() > chosen.count()) chosen = stack;
			}
			if (chosen == null) continue;
			best = new Discard(chosen.inventorySlot(), entry.getKey(), chosen.count(), entry.getValue() - chosen.count());
			bestSurplus = surplus;
		}
		return Optional.ofNullable(best);
	}
}
