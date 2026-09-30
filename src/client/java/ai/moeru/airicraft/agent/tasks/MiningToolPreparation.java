package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.control.Actuator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;

import java.util.List;
import java.util.Set;

/** Called by the current breaking owner, never by a background inventory task. */
public final class MiningToolPreparation {
	private MiningToolPreparation() {}

	public static Result ensureSelected(Minecraft minecraft, Actuator actuator, LocalPlayer player, List<BlockState> targets) {
		return ensureSelected(minecraft, actuator, player, targets, List.of());
	}

	public static Result ensureSelected(
		Minecraft minecraft, Actuator actuator, LocalPlayer player, List<BlockState> targets, List<String> requiredToolItemIds
	) {
		return ensureSelected(minecraft, actuator, player, targets, requiredToolItemIds, true, true);
	}

	public static Result ensureSelectedForClearance(Minecraft minecraft, Actuator actuator, LocalPlayer player,
		List<BlockState> targets) {
		return ensureSelectedForClearance(minecraft, actuator, player, targets, true);
	}

	/** Tool choice for clearing a path; {@code allowInventory} false limits the choice to the hotbar. */
	public static Result ensureSelectedForClearance(Minecraft minecraft, Actuator actuator, LocalPlayer player,
		List<BlockState> targets, boolean allowInventory) {
		return ensureSelected(minecraft, actuator, player, targets, List.of(), false, allowInventory);
	}

	private static Result ensureSelected(Minecraft minecraft, Actuator actuator, LocalPlayer player, List<BlockState> targets,
		List<String> requiredToolItemIds, boolean requireDrops, boolean allowInventory) {
		if (player.containerMenu != player.inventoryMenu
			|| !player.containerMenu.getCarried().isEmpty() || player.isUsingItem()) {
			return Result.failed("inventory_unavailable_for_tool_selection");
		}
		Set<String> required = requiredToolItemIds == null ? Set.of() : Set.copyOf(requiredToolItemIds);
		var inventory = player.getInventory();
		int selectedSlot = inventory.getSelectedSlot();
		java.util.function.IntFunction<MiningToolSelection.Score> scores = slot -> !allowInventory && slot >= 9
			? new MiningToolSelection.Score(false, 0.0F)
			: score(inventory.getItem(slot), targets, required);
		int sourceSlot = requireDrops ? MiningToolSelection.preferredSlot(selectedSlot, scores)
			: MiningToolSelection.preferredClearanceSlot(selectedSlot, scores);
		if (sourceSlot < 0) {
			return Result.failed(required.isEmpty()
				? "missing_suitable_tool blockIds=" + blockIds(targets)
				: "missing_required_harvest_tool itemIds=" + required);
		}
		MiningToolSelection.Score expected = score(inventory.getItem(sourceSlot), targets, required);
		if (sourceSlot != selectedSlot) {
			if (sourceSlot < 9) {
				selectedSlot = sourceSlot;
				if (!actuator.selectHotbarAndSync(minecraft, selectedSlot)) return Result.failed("hotbar_held_by_another_owner");
			} else {
				// Main inventory indices 9..35 equal the player screen's slot IDs.
				minecraft.gameMode.handleInventoryMouseClick(player.inventoryMenu.containerId, sourceSlot,
					selectedSlot, ClickType.SWAP, player);
				if (!actuator.syncHotbar(minecraft, selectedSlot)) return Result.failed("hotbar_held_by_another_owner");
			}
		}
		MiningToolSelection.Score actual = score(inventory.getSelectedItem(), targets, required);
		return (requireDrops ? actual.eligible() && actual.speed() >= expected.speed()
			: MiningToolSelection.clearanceSpeed(actual) >= MiningToolSelection.clearanceSpeed(expected))
			? Result.success() : Result.failed("tool_selection_failed blockIds=" + blockIds(targets));
	}

	private static MiningToolSelection.Score score(ItemStack stack, List<BlockState> targets, Set<String> required) {
		boolean eligible = required.isEmpty() || required.contains(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
		float speed = Float.MAX_VALUE;
		for (BlockState state : targets) {
			eligible &= !state.requiresCorrectToolForDrops() || !stack.isEmpty() && stack.isCorrectToolForDrops(state);
			speed = Math.min(speed, stack.isEmpty() ? 1.0F : stack.getDestroySpeed(state));
		}
		return new MiningToolSelection.Score(eligible, speed == Float.MAX_VALUE ? 1.0F : speed);
	}

	private static List<String> blockIds(List<BlockState> targets) {
		return targets.stream().map(state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString()).distinct().toList();
	}

	public record Result(boolean ok, String message) {
		static Result success() { return new Result(true, ""); }
		static Result failed(String message) { return new Result(false, message); }
	}
}
