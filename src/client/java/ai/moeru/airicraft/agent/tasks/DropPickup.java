package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/**
 * Walking onto a dropped item that a task made, as a player does after breaking a block: the timings every pickup
 * shares and the walk itself (no edits, owned navigation, its own stall limit). Used by {@link PlacedStationReclaim}
 * and {@link BlockBreakDrops}. Client thread only.
 */
final class DropPickup {
	/** A dropped item cannot be picked up for 10 ticks; one inside the pickup box is taken right after. */
	static final int PICKUP_WAIT_TICKS = 15;
	/** No drop in sight and nothing gained for this long after the break: it is gone. */
	static final int DROP_VANISHED_TICKS = 30;
	static final int DROP_TIMEOUT_TICKS = 200;
	/** A drop farther than this, horizontally, is not walked to. Blocks are broken from at most 4.5 blocks away. */
	static final double DROP_WALK_RADIUS = 8.0D;
	static final double DROP_SEARCH_RADIUS = 4.0D;
	/** Gives up on a walk before the dispatcher's 100-tick stall watchdog would end the parent task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	static final double FEET_Y_SLACK = 0.2D;

	private final NavigationFacade navigation;
	private final NavigationOptions options;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);
	private boolean navigationOwned;
	private GoalPosition navigationGoal;

	DropPickup(NavigationFacade navigation, NavigationOptions options) {
		this.navigation = navigation;
		this.options = Objects.requireNonNull(options, "options");
	}

	/** Whether there is a navigator to walk with. */
	boolean available() {
		return navigation != null && navigation.isLoaded();
	}

	/** Walks one tick toward the drop at {@code drop}; false once the walk cannot go on. */
	boolean walkTo(Vec3 drop, long activeTicks) {
		GoalPosition goal = goal(drop);
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stop();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigate(goal, options);
			navigationOwned = true;
			navigationGoal = goal;
			navigationStall.clear();
		}
		String event = navigation.pollPathEvent().orElse("");
		var progress = navigation.navigationProgress();
		if (progress.isEmpty()) navigationStall.clear();
		else if (navigationStall.observe(activeTicks, progress.get())) return false;
		return !event.contains("CALC_FAILED") && !event.equals("CANCELED");
	}

	/** Drops the walk, if this pickup started one. */
	void stop() {
		if (navigationOwned && navigation != null && navigation.isLoaded()) navigation.cancel();
		navigationOwned = false;
		navigationGoal = null;
		navigationStall.clear();
	}

	// ---- Pure helpers ----

	/** The cell the body walks to so its pickup box takes the drop lying at {@code drop}. */
	static GoalPosition goal(Vec3 drop) {
		return new GoalPosition((int) Math.floor(drop.x), (int) Math.floor(drop.y + FEET_Y_SLACK), (int) Math.floor(drop.z), true);
	}

	static double horizontalDistance(Vec3 feet, Vec3 position) {
		double dx = position.x - feet.x, dz = position.z - feet.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	static int inventoryCount(Player player, Item item) {
		Inventory inventory = player.getInventory();
		int count = 0;
		for (int index = 0; index < inventory.getContainerSize(); index++) {
			ItemStack stack = inventory.getItem(index);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}
}
