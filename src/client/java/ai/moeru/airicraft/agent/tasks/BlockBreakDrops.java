package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Picks up what the planner's {@code break_blocks} job broke, as a player does: a playtest body broke its bed to carry it
 * to a portal, walked off and lost the bed and its respawn point with it. Only item entities that were not lying near the
 * targets when the job started and that settle near a broken cell count. It waits for the pickup box to take them, then
 * walks (walk only, falls of at most {@link #MAX_SAFE_FALL}) onto each one that stays out of reach, nearest first. A drop
 * at lava or fire, farther than {@link DropPickup#DROP_WALK_RADIUS} from the broken cell, that the inventory cannot hold,
 * that no route reaches or that is not picked up in time is left where it lies. The outcome is text for the job result:
 * {@code picked=<item xN,...>} and, for drops left behind, {@code dropsLeft=<item@x,y,z,...> reason=...}.
 */
final class BlockBreakDrops {
	/** How far below and above a broken cell a drop may come to rest and still count as its drop. */
	static final int SEARCH_BELOW = 6;
	static final int SEARCH_ABOVE = 2;
	static final int MAX_SAFE_FALL = BlockBreakTaskExecutor.SAFE_DROP;
	static final NavigationOptions OPTIONS = NavigationOptions.WALK_ONLY.withMaxSafeFall(MAX_SAFE_FALL);
	static final int TIMEOUT_PER_EXTRA_CELL_TICKS = 40;
	static final int MAX_TIMEOUT_TICKS = 600;

	private final Supplier<Minecraft> clientSupplier;
	private final DropPickup pickup;
	private final Set<Integer> preexisting;
	private final Map<String, Integer> inventoryBefore;
	private List<BlockPos> broken = List.of();
	/** Item entities counted as drops of the break, by entity id: their item id. */
	private final Map<Integer, String> tracked = new LinkedHashMap<>();
	private final Map<Integer, Left> left = new LinkedHashMap<>();
	private int phaseTicks;
	private long activeTicks;
	private int walkingId = -1;
	private int walkTicks;
	private String outcome;

	private BlockBreakDrops(Supplier<Minecraft> clientSupplier, NavigationFacade navigation, Set<Integer> preexisting,
		Map<String, Integer> inventoryBefore) {
		this.clientSupplier = clientSupplier;
		this.pickup = new DropPickup(navigation, OPTIONS);
		this.preexisting = Set.copyOf(preexisting);
		this.inventoryBefore = Map.copyOf(inventoryBefore);
	}

	/**
	 * Notes, before anything is broken, which items already lie near the targets and what the inventory holds; empty
	 * when no world is loaded.
	 */
	static Optional<BlockBreakDrops> capture(Supplier<Minecraft> clientSupplier, NavigationFacade navigation, List<BlockPos> targets) {
		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (player == null || level == null || targets.isEmpty()) return Optional.empty();
		Set<Integer> lying = new HashSet<>();
		for (BlockPos target : targets) {
			level.getEntitiesOfClass(ItemEntity.class, searchBox(target), Entity::isAlive).forEach(item -> lying.add(item.getId()));
		}
		return Optional.of(new BlockBreakDrops(clientSupplier, navigation, lying, inventoryCounts(player.getInventory())));
	}

	/** Starts collecting after the break; {@code brokenCells} are the cells the job broke. */
	void begin(List<BlockPos> brokenCells) {
		broken = List.copyOf(brokenCells);
		phaseTicks = 0;
	}

	/** The outcome text once collecting is over, otherwise empty. */
	Optional<String> tick() {
		if (outcome != null) return Optional.of(outcome);
		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (player == null || level == null) return finish("picked=unknown dropsLeft=unknown reason=world_unavailable");
		activeTicks++;
		phaseTicks++;
		for (BlockPos cell : broken) {
			for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, searchBox(cell), Entity::isAlive)) {
				if (!tracked.containsKey(item.getId()) && counts(item.getId(), item.position(), preexisting, broken))
					tracked.put(item.getId(), itemId(item.getItem()));
			}
		}
		List<Drop> live = new ArrayList<>();
		for (var iterator = tracked.entrySet().iterator(); iterator.hasNext(); ) {
			var entry = iterator.next();
			if (!(level.getEntity(entry.getKey()) instanceof ItemEntity item) || !item.isAlive()) {
				iterator.remove();
				continue;
			}
			if (left.containsKey(item.getId())) {
				left.put(item.getId(), left.get(item.getId()).at(item.blockPosition()));
				continue;
			}
			BlockPos cell = BlockPos.containing(item.position().add(0.0D, 0.1D, 0.0D));
			live.add(new Drop(item.getId(), entry.getValue(), item.position(), item.onGround() || item.isInWater(),
				level.hasChunkAt(cell) && CoverCells.hazardNear(level, cell, 1), holdable(player.getInventory(), item.getItem()),
				DropPickup.horizontalDistance(player.position(), item.position()), distanceFromBroken(item.position(), broken)));
		}
		left.keySet().retainAll(tracked.keySet());
		int timeout = timeoutTicks(broken.size());
		while (true) {
			Decision decision = decide(phaseTicks, timeout, live);
			switch (decision.kind()) {
				case DONE -> {
					return finish(outcomeText(gains(inventoryBefore, inventoryCounts(player.getInventory())), List.copyOf(left.values())));
				}
				case WAIT -> {
					stopWalking();
					return Optional.empty();
				}
				case LEAVE -> leave(decision.drop(), decision.reason(), live);
				case WALK -> {
					Drop drop = decision.drop();
					if (!pickup.available()) {
						leave(drop, "no_navigation", live);
						continue;
					}
					if (walkingId != drop.id()) {
						walkingId = drop.id();
						walkTicks = 0;
					}
					if (++walkTicks > DropPickup.DROP_TIMEOUT_TICKS) {
						leave(drop, "not_picked_up", live);
						continue;
					}
					if (!pickup.walkTo(drop.position(), activeTicks)) {
						leave(drop, walkFailure(player.getY() - drop.position().y), live);
						continue;
					}
					return Optional.empty();
				}
			}
		}
	}

	private void leave(Drop drop, String reason, List<Drop> live) {
		if (drop.id() == walkingId) stopWalking();
		left.put(drop.id(), new Left(drop.itemId(), BlockPos.containing(drop.position()), reason));
		live.remove(drop);
	}

	private void stopWalking() {
		pickup.stop();
		walkingId = -1;
		walkTicks = 0;
	}

	/** Progress text for the task snapshot. */
	String status() {
		return "collecting_drops tracked=" + tracked.size() + " left=" + left.size();
	}

	/** While the job is paused (a reflex holds the body): drop the walk, so it restarts from wherever the body ends up. */
	void pause() {
		stopWalking();
	}

	/** Stops any walk; the drops stay where they are. */
	void cancel() {
		stopWalking();
	}

	private Optional<String> finish(String text) {
		stopWalking();
		outcome = text;
		return Optional.of(text);
	}

	private static AABB searchBox(BlockPos cell) {
		double radius = DropPickup.DROP_SEARCH_RADIUS;
		return new AABB(cell.getX() + 0.5D - radius, cell.getY() - SEARCH_BELOW, cell.getZ() + 0.5D - radius,
			cell.getX() + 0.5D + radius, cell.getY() + 1 + SEARCH_ABOVE, cell.getZ() + 0.5D + radius);
	}

	private static boolean holdable(Inventory inventory, ItemStack stack) {
		return inventory.getFreeSlot() >= 0 || inventory.getSlotWithRemainingSpace(stack) >= 0;
	}

	private static Map<String, Integer> inventoryCounts(Inventory inventory) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (int index = 0; index < inventory.getContainerSize(); index++) {
			ItemStack stack = inventory.getItem(index);
			if (!stack.isEmpty()) counts.merge(itemId(stack), stack.getCount(), Integer::sum);
		}
		return counts;
	}

	private static String itemId(ItemStack stack) {
		return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	// ---- Pure decisions ----

	/**
	 * A live drop. {@code settled}: resting on the ground or floating in water (not still flying); {@code hazard}: lava,
	 * fire or magma beside or under it; {@code holdable}: the inventory has room for it; {@code fromFeet} and
	 * {@code fromBroken}: horizontal distance from the body's feet and from the nearest broken cell's centre.
	 */
	record Drop(int id, String itemId, Vec3 position, boolean settled, boolean hazard, boolean holdable, double fromFeet,
		double fromBroken) {}

	/** A drop left behind, where it lies and why. */
	record Left(String itemId, BlockPos pos, String reason) {
		Left at(BlockPos where) {
			return new Left(itemId, where.immutable(), reason);
		}
	}

	enum Kind { WAIT, WALK, LEAVE, DONE }

	record Decision(Kind kind, Drop drop, String reason) {
		static final Decision WAIT = new Decision(Kind.WAIT, null, null);
		static final Decision DONE = new Decision(Kind.DONE, null, null);

		static Decision walk(Drop drop) {
			return new Decision(Kind.WALK, drop, null);
		}

		static Decision leave(Drop drop, String reason) {
			return new Decision(Kind.LEAVE, drop, reason);
		}
	}

	/**
	 * Whether an item entity counts as a drop of the break: it was not lying near the targets when the job started and it
	 * is within {@link DropPickup#DROP_SEARCH_RADIUS} blocks, horizontally, of a broken cell, from {@link #SEARCH_BELOW}
	 * below it to {@link #SEARCH_ABOVE} above it.
	 */
	static boolean counts(int entityId, Vec3 position, Set<Integer> preexisting, List<BlockPos> brokenCells) {
		if (preexisting.contains(entityId)) return false;
		for (BlockPos cell : brokenCells) {
			if (position.y < cell.getY() - SEARCH_BELOW || position.y > cell.getY() + 1 + SEARCH_ABOVE) continue;
			if (DropPickup.horizontalDistance(Vec3.atCenterOf(cell), position) <= DropPickup.DROP_SEARCH_RADIUS) return true;
		}
		return false;
	}

	/**
	 * What to do {@code phaseTicks} after the break about the live drops not yet left behind. The pickup box gets the
	 * first {@link DropPickup#PICKUP_WAIT_TICKS}; after that each drop that cannot be walked to is left, one per call, and
	 * the nearest settled one is walked to. Past {@code timeoutTicks} every drop still lying is left.
	 */
	static Decision decide(int phaseTicks, int timeoutTicks, List<Drop> live) {
		List<Drop> nearestFirst = live.stream().sorted(Comparator.comparingDouble(Drop::fromFeet)).toList();
		if (phaseTicks > timeoutTicks) return nearestFirst.isEmpty() ? Decision.DONE : Decision.leave(nearestFirst.getFirst(), "timeout");
		if (nearestFirst.isEmpty()) return phaseTicks > DropPickup.PICKUP_WAIT_TICKS ? Decision.DONE : Decision.WAIT;
		if (phaseTicks <= DropPickup.PICKUP_WAIT_TICKS) return Decision.WAIT;
		for (Drop drop : nearestFirst) {
			if (!drop.holdable()) return Decision.leave(drop, "inventory_full");
			if (!drop.settled()) continue;
			if (drop.hazard()) return Decision.leave(drop, "lava_or_fire");
			if (drop.fromBroken() > DropPickup.DROP_WALK_RADIUS) return Decision.leave(drop, "too_far");
		}
		return nearestFirst.stream().filter(Drop::settled).findFirst().map(Decision::walk).orElse(Decision.WAIT);
	}

	/** Why no walk reached a drop lying {@code belowFeet} blocks below the feet: routes never fall more than 3. */
	static String walkFailure(double belowFeet) {
		return belowFeet > MAX_SAFE_FALL + DropPickup.FEET_Y_SLACK ? "deep_drop" : "unreachable";
	}

	/** The whole collecting phase: {@link DropPickup#DROP_TIMEOUT_TICKS} plus a little per further broken cell. */
	static int timeoutTicks(int brokenCells) {
		return Math.min(MAX_TIMEOUT_TICKS, DropPickup.DROP_TIMEOUT_TICKS + TIMEOUT_PER_EXTRA_CELL_TICKS * Math.max(0, brokenCells - 1));
	}

	static double distanceFromBroken(Vec3 position, List<BlockPos> brokenCells) {
		double nearest = Double.MAX_VALUE;
		for (BlockPos cell : brokenCells) nearest = Math.min(nearest, DropPickup.horizontalDistance(Vec3.atCenterOf(cell), position));
		return nearest;
	}

	/** What the inventory gained, by item id, in the order the after-counts list them. */
	static Map<String, Integer> gains(Map<String, Integer> before, Map<String, Integer> after) {
		Map<String, Integer> gained = new LinkedHashMap<>();
		after.forEach((item, count) -> {
			int delta = count - before.getOrDefault(item, 0);
			if (delta > 0) gained.put(item, delta);
		});
		return gained;
	}

	/** {@code picked=<item xN,...>} (or {@code picked=none}) and, when drops were left, {@code dropsLeft=... reason=...}. */
	static String outcomeText(Map<String, Integer> picked, List<Left> leftBehind) {
		StringBuilder text = new StringBuilder("picked=");
		text.append(picked.isEmpty() ? "none" : picked.entrySet().stream()
			.map(entry -> entry.getKey() + " x" + entry.getValue()).collect(Collectors.joining(",")));
		if (!leftBehind.isEmpty()) {
			text.append(" dropsLeft=").append(leftBehind.stream()
				.map(drop -> drop.itemId() + "@" + drop.pos().getX() + "," + drop.pos().getY() + "," + drop.pos().getZ())
				.collect(Collectors.joining(",")));
			Set<String> reasons = new LinkedHashSet<>();
			leftBehind.forEach(drop -> reasons.add(drop.reason()));
			text.append(" reason=").append(String.join(",", reasons));
		}
		return text.toString();
	}
}
