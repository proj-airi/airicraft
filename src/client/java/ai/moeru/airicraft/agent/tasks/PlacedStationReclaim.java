package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.agent.navigation.NavigationFacade;
import ai.moeru.airicraft.agent.navigation.NavigationOptions;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Takes back a crafting table or furnace that a task placed from the inventory: breaks it through a break-blocks child
 * task, waits for the drop to be picked up and walks onto it only when it stays out of reach (walk only). A playtest
 * body crafted four tables and four furnaces in an hour, left each where it was used, and ran out of planks underground.
 * The outcome is text for the task result: {@code tableReclaimed=x,y,z} or {@code tableLeftAt=x,y,z reason=...}.
 */
final class PlacedStationReclaim {
	/** A dropped item cannot be picked up for 10 ticks; one inside the pickup box is taken right after. */
	static final int PICKUP_WAIT_TICKS = 15;
	/** No drop in sight and nothing gained for this long after the break: it is gone. */
	static final int DROP_VANISHED_TICKS = 30;
	static final int DROP_TIMEOUT_TICKS = 200;
	/** A drop farther than this, horizontally, is not walked to. Stations are used from at most 4.5 blocks away. */
	static final double DROP_WALK_RADIUS = 8.0D;
	static final double DROP_SEARCH_RADIUS = 4.0D;
	/** Gives up on a walk before the dispatcher's 100-tick stall watchdog would end the parent task. */
	static final long NAVIGATION_STALL_TICKS = 70;
	static final double FEET_Y_SLACK = 0.2D;

	private final String label;
	private final BlockPos pos;
	private final Block block;
	private final Item item;
	private final WorldTaskRequest breakRequest;
	private final WorldTaskExecutor breakExecutor;
	private final Supplier<Minecraft> clientSupplier;
	private final NavigationFacade navigation;
	private final NavigationStallWatchdog navigationStall = new NavigationStallWatchdog(NAVIGATION_STALL_TICKS);

	private Phase phase = Phase.START;
	private int phaseTicks;
	private long activeTicks;
	private int itemsBefore;
	private boolean navigationOwned;
	private GoalPosition navigationGoal;
	private String outcome;

	PlacedStationReclaim(
		String label,
		BlockPos pos,
		Block block,
		Item item,
		WorldTaskRequest parent,
		WorldTaskExecutor breakExecutor,
		Supplier<Minecraft> clientSupplier,
		NavigationFacade navigation
	) {
		this.label = Objects.requireNonNull(label, "label");
		this.pos = Objects.requireNonNull(pos, "pos").immutable();
		this.block = Objects.requireNonNull(block, "block");
		this.item = Objects.requireNonNull(item, "item");
		this.breakRequest = breakRequest(parent, label, pos, BuiltInRegistries.BLOCK.getKey(block).toString());
		this.breakExecutor = Objects.requireNonNull(breakExecutor, "breakExecutor");
		this.clientSupplier = Objects.requireNonNull(clientSupplier, "clientSupplier");
		this.navigation = navigation;
	}

	BlockPos pos() {
		return pos;
	}

	/** The outcome text once the reclaim is over, otherwise empty. */
	Optional<String> tick(SessionSnapshot session) {
		if (outcome != null) return Optional.of(outcome);
		Minecraft minecraft = clientSupplier.get();
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		ClientLevel level = minecraft == null ? null : minecraft.level;
		if (player == null || level == null || minecraft.gameMode == null) return finish(leftAt(label, pos, "world_unavailable"));
		activeTicks++;
		phaseTicks++;
		return switch (phase) {
			case START -> start(session, player, level);
			case BREAK -> breakStation(session);
			case COLLECT -> collect(player, level);
		};
	}

	private Optional<String> start(SessionSnapshot session, LocalPlayer player, ClientLevel level) {
		if (!level.hasChunkAt(pos) || !level.getBlockState(pos).is(block)) return finish(leftAt(label, pos, "block_changed"));
		itemsBefore = inventoryCount(player, item);
		enter(Phase.BREAK);
		return breakStation(session);
	}

	private Optional<String> breakStation(SessionSnapshot session) {
		Optional<TaskTerminalEvent> terminal = breakExecutor.tick(session, Optional.of(breakRequest));
		if (terminal.isEmpty()) return Optional.empty();
		breakExecutor.tick(session, Optional.empty());
		if (terminal.get().terminalState() != TaskExecutionState.COMPLETED)
			return finish(leftAt(label, pos, "break_failed:" + firstToken(terminal.get().message())));
		enter(Phase.COLLECT);
		return Optional.empty();
	}

	private Optional<String> collect(LocalPlayer player, ClientLevel level) {
		boolean pickedUp = inventoryCount(player, item) > itemsBefore;
		Optional<Vec3> drop = pickedUp ? Optional.empty() : nearestDrop(level);
		Double distance = drop.map(position -> horizontalDistance(player.position(), position)).orElse(null);
		switch (dropStep(phaseTicks, pickedUp, distance)) {
			case DONE -> {
				return finish(reclaimed(label, pos));
			}
			case WALK -> {
				if (navigation == null || !navigation.isLoaded()) {
					if (phaseTicks > DROP_TIMEOUT_TICKS) return finish(leftAt(label, pos, "drop_out_of_reach"));
					return Optional.empty();
				}
				GoalPosition goal = new GoalPosition((int) Math.floor(drop.get().x), (int) Math.floor(drop.get().y + FEET_Y_SLACK),
					(int) Math.floor(drop.get().z), true);
				if (!navigate(goal)) return finish(leftAt(label, pos, "drop_unreachable"));
				return Optional.empty();
			}
			case WAIT -> {
				return Optional.empty();
			}
			case LOST -> {
				return finish(leftAt(label, pos, distance == null ? "drop_vanished" : "drop_not_picked_up"));
			}
		}
		return Optional.empty();
	}

	private Optional<Vec3> nearestDrop(ClientLevel level) {
		Vec3 center = Vec3.atCenterOf(pos);
		return level.getEntitiesOfClass(ItemEntity.class, new AABB(center, center).inflate(DROP_SEARCH_RADIUS),
				entity -> entity.isAlive() && entity.getItem().is(item)).stream()
			.min(Comparator.comparingDouble(entity -> entity.distanceToSqr(center)))
			.map(ItemEntity::position);
	}

	/** False once the walk cannot go on. */
	private boolean navigate(GoalPosition goal) {
		if (!navigationOwned || !goal.equals(navigationGoal)) {
			stopNavigation();
			NavigationRelease.release(navigation);
			navigation.pollPathEvent();
			navigation.startNavigate(goal, NavigationOptions.WALK_ONLY);
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

	private void stopNavigation() {
		if (navigationOwned && navigation != null && navigation.isLoaded()) navigation.cancel();
		navigationOwned = false;
		navigationGoal = null;
		navigationStall.clear();
	}

	/** While the parent is paused (a reflex holds the body): drop the walk, so it restarts from wherever the body ends up. */
	void pause() {
		stopNavigation();
	}

	/** Stops the child break and any walk; the station or its drop stays where it is. */
	void cancel() {
		stopNavigation();
		breakExecutor.onWorldLeave();
	}

	private Optional<String> finish(String text) {
		stopNavigation();
		breakExecutor.onWorldLeave();
		outcome = text;
		return Optional.of(text);
	}

	private void enter(Phase next) {
		phase = next;
		phaseTicks = 0;
	}

	private static int inventoryCount(LocalPlayer player, Item item) {
		int count = 0;
		for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
			ItemStack stack = player.getInventory().getItem(index);
			if (stack.is(item)) count += stack.getCount();
		}
		return count;
	}

	// ---- Pure decisions ----

	/**
	 * What to do about the drop {@code phaseTicks} after the break. {@code dropDistance}: how far the nearest dropped station
	 * item lies from the feet, horizontally, or null when none is in sight.
	 */
	static DropStep dropStep(int phaseTicks, boolean pickedUp, Double dropDistance) {
		if (pickedUp) return DropStep.DONE;
		if (phaseTicks > DROP_TIMEOUT_TICKS) return DropStep.LOST;
		if (dropDistance == null) return phaseTicks > DROP_VANISHED_TICKS ? DropStep.LOST : DropStep.WAIT;
		if (phaseTicks <= PICKUP_WAIT_TICKS) return DropStep.WAIT;
		return dropDistance <= DROP_WALK_RADIUS ? DropStep.WALK : DropStep.LOST;
	}

	static WorldTaskRequest breakRequest(WorldTaskRequest parent, String label, BlockPos pos, String blockId) {
		return WorldTaskRequest.breakBlocks(
			parent.taskId() + ":reclaim-" + label,
			parent.sourceJobId(),
			new BlockBreakStepArgs(List.of(new BlockBreakStepArgs.Target(
				new GoalPosition(pos.getX(), pos.getY(), pos.getZ(), true), List.of(blockId))))
		);
	}

	static String reclaimed(String label, BlockPos pos) {
		return label + "Reclaimed=" + compactPos(pos);
	}

	static String leftAt(String label, BlockPos pos, String reason) {
		return label + "LeftAt=" + compactPos(pos) + " reason=" + reason;
	}

	static String firstToken(String message) {
		if (message == null || message.isBlank()) return "unknown";
		return message.trim().split("\\s+", 2)[0];
	}

	static double horizontalDistance(Vec3 feet, Vec3 position) {
		double dx = position.x - feet.x, dz = position.z - feet.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static String compactPos(BlockPos pos) {
		return pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	enum Phase { START, BREAK, COLLECT }

	enum DropStep { WAIT, WALK, DONE, LOST }
}
