package ai.moeru.airicraft.agent.tasks;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.control.CameraController;
import ai.moeru.airicraft.agent.control.MovementController;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minecraft adapter shared by underwater harvesting and drowning reflexes.
 * One recovery generation memoizes only incrementally inspected world cells
 * and never retries a failed target, even across recovery-mode changes.
 */
public final class MinecraftUnderwaterEscapeController {
	private final MovementController movement;
	private final CameraController camera;
	private final BaritoneFacade baritone;
	private final UnderwaterEscapeNavigator navigator;
	private Minecraft activeClient;

	private UnderwaterEscapeSearch.SearchMode mode;
	private UnderwaterEscapeSearch.SearchSession searchSession;
	private List<UnderwaterEscapeSearch.Candidate> candidates = List.of();
	private UnderwaterEscapeSearch.SearchStatus searchStatus = UnderwaterEscapeSearch.SearchStatus.SEARCHING;
	private boolean waitingForBaritoneRelease;

	public MinecraftUnderwaterEscapeController(
		BaritoneFacade baritone,
		MovementController movement,
		CameraController camera
	) {
		this.baritone = baritone;
		this.movement = Objects.requireNonNull(movement, "movement");
		this.camera = Objects.requireNonNull(camera, "camera");
		this.navigator = new UnderwaterEscapeNavigator(baritone, new UnderwaterEscapeNavigator.WaypointDriver() {
			@Override
			public void moveToward(UnderwaterEscapeSearch.Position waypoint, long tick) {
				Minecraft minecraft = activeClient;
				LocalPlayer player = minecraft == null ? null : minecraft.player;
				if (player == null) {
					MinecraftUnderwaterEscapeController.this.movement.stop(minecraft);
					return;
				}
				Vec3 target = new Vec3(waypoint.x() + 0.5D, waypoint.y() + 1.0D, waypoint.z() + 0.5D);
				MinecraftUnderwaterEscapeController.this.camera.lookAt(minecraft, target);
				boolean ascend = waypoint.y() > player.getBlockY();
				MinecraftUnderwaterEscapeController.this.movement.moveDirectional(
					minecraft,
					true,
					false,
					false,
					false,
					false,
					ascend,
					tick
				);
			}

			@Override
			public void stop() {
				MinecraftUnderwaterEscapeController.this.movement.stop(activeClient);
			}
		});
	}

	public Snapshot tick(
		Minecraft minecraft,
		UnderwaterEscapeSearch.SearchMode requestedMode,
		int remainingAirTicks,
		long tick,
		boolean targetSatisfied
	) {
		LocalPlayer player = minecraft == null ? null : minecraft.player;
		if (minecraft == null || minecraft.level == null || player == null) {
			reset(minecraft);
			return snapshot();
		}
		activeClient = minecraft;
		if (searchSession != null
			&& navigator.snapshot().phase() == UnderwaterEscapeNavigator.Phase.REACHED
			&& !targetSatisfied) {
			restartSearch(minecraft);
			activeClient = minecraft;
		}
		if (searchSession == null || mode != requestedMode) {
			begin(minecraft, requestedMode, remainingAirTicks);
		}
		if (searchSession != null && searchStatus == UnderwaterEscapeSearch.SearchStatus.SEARCHING) {
			UnderwaterEscapeSearch.SearchUpdate update = searchSession.advance(
				UnderwaterEscapeSearch.DEFAULT_CELL_INSPECTIONS_PER_TICK
			);
			candidates = update.candidates();
			searchStatus = update.status();
		}
		if (waitingForBaritoneRelease) {
			waitingForBaritoneRelease = !BaritoneReleaseBarrier.releaseAndDrain(baritone);
			if (waitingForBaritoneRelease) {
				movement.stop(minecraft);
				return snapshot();
			}
		}
		UnderwaterEscapeNavigator.Snapshot navigation = navigator.tick(
			candidates,
			new UnderwaterEscapeNavigator.Observation(
				player.getX(),
				player.getY(),
				player.getZ(),
				tick,
				targetSatisfied
			)
		);
		if (navigation.phase() == UnderwaterEscapeNavigator.Phase.RESEARCH_REQUIRED) {
			// Baritone left the player in a connected cell outside every route
			// computed from the old origin. Re-anchor the bounded search here;
			// navigator.restartSearch() deliberately retains attempted/failed targets.
			begin(minecraft, requestedMode, remainingAirTicks);
			movement.stop(minecraft);
			return snapshot();
		}
		return new Snapshot(searchStatus, candidates.size(), navigation, false);
	}

	public void reset(Minecraft minecraft) {
		navigator.reset();
		movement.stop(minecraft);
		activeClient = null;
		mode = null;
		searchSession = null;
		candidates = List.of();
		searchStatus = UnderwaterEscapeSearch.SearchStatus.SEARCHING;
		waitingForBaritoneRelease = false;
	}

	public Snapshot snapshot() {
		return new Snapshot(searchStatus, candidates.size(), navigator.snapshot(), waitingForBaritoneRelease);
	}

	private void begin(
		Minecraft minecraft,
		UnderwaterEscapeSearch.SearchMode requestedMode,
		int remainingAirTicks
	) {
		navigator.restartSearch();
		waitingForBaritoneRelease = true;
		mode = Objects.requireNonNull(requestedMode, "requestedMode");
		LocalPlayer player = minecraft.player;
		UnderwaterEscapeSearch.Position start = new UnderwaterEscapeSearch.Position(
			player.getBlockX(),
			player.getBlockY(),
			player.getBlockZ()
		);
		UnderwaterEscapeSearch.SearchRequest request = UnderwaterEscapeSearch.SearchRequest.forRemainingAir(
			start,
			requestedMode,
			remainingAirTicks
		);
		searchSession = UnderwaterEscapeSearch.begin(request, new LiveCellView(minecraft, start, request.maxPathSteps()));
		candidates = List.of();
		searchStatus = searchSession.status();
	}

	private static UnderwaterEscapeSearch.Cell observeCell(
		Minecraft minecraft,
		UnderwaterEscapeSearch.Position position
	) {
		BlockPos feetPos = new BlockPos(position.x(), position.y(), position.z());
		BlockPos headPos = feetPos.above();
		BlockPos supportPos = feetPos.below();
		if (!minecraft.level.isInWorldBounds(feetPos)
			|| !minecraft.level.isInWorldBounds(headPos)
			|| !minecraft.level.isInWorldBounds(supportPos)
			|| !minecraft.level.hasChunkAt(feetPos)
			|| !minecraft.level.hasChunkAt(headPos)
			|| !minecraft.level.hasChunkAt(supportPos)) {
			return UnderwaterEscapeSearch.Cell.blocked();
		}
		BlockState feet = minecraft.level.getBlockState(feetPos);
		BlockState head = minecraft.level.getBlockState(headPos);
		BlockState support = minecraft.level.getBlockState(supportPos);
		boolean collisionFree = feet.getCollisionShape(minecraft.level, feetPos).isEmpty()
			&& head.getCollisionShape(minecraft.level, headPos).isEmpty();
		if (!collisionFree) {
			return UnderwaterEscapeSearch.Cell.blocked();
		}
		boolean waterAtFeet = minecraft.level.getFluidState(feetPos).is(FluidTags.WATER);
		boolean waterAtHead = minecraft.level.getFluidState(headPos).is(FluidTags.WATER);
		boolean breathable = minecraft.level.getFluidState(headPos).isEmpty();
		boolean safeStanding = breathable
			&& minecraft.level.getFluidState(feetPos).isEmpty()
			&& support.isFaceSturdy(minecraft.level, supportPos, Direction.UP);
		return new UnderwaterEscapeSearch.Cell(
			true,
			waterAtFeet,
			waterAtHead,
			breathable,
			safeStanding
		);
	}

	/** Predicate shared with idle-drowning resolution; unlike surface memory it rejects water. */
	public static boolean isSafeStandingPosition(Minecraft minecraft, BlockPos feetPos) {
		if (minecraft == null || minecraft.level == null || feetPos == null) {
			return false;
		}
		return observeCell(minecraft, new UnderwaterEscapeSearch.Position(
			feetPos.getX(),
			feetPos.getY(),
			feetPos.getZ()
		)).safeStanding();
	}

	private void restartSearch(Minecraft minecraft) {
		navigator.restartSearch();
		movement.stop(minecraft);
		mode = null;
		searchSession = null;
		candidates = List.of();
		searchStatus = UnderwaterEscapeSearch.SearchStatus.SEARCHING;
		waitingForBaritoneRelease = true;
	}

	private static final class LiveCellView implements UnderwaterEscapeSearch.CellView {
		private final Minecraft minecraft;
		private final UnderwaterEscapeSearch.Position origin;
		private final int maxPathSteps;
		private final Map<UnderwaterEscapeSearch.Position, UnderwaterEscapeSearch.Cell> observed = new HashMap<>();

		private LiveCellView(
			Minecraft minecraft,
			UnderwaterEscapeSearch.Position origin,
			int maxPathSteps
		) {
			this.minecraft = minecraft;
			this.origin = origin;
			this.maxPathSteps = maxPathSteps;
		}

		@Override
		public UnderwaterEscapeSearch.Cell cellAt(UnderwaterEscapeSearch.Position position) {
			int distance = Math.abs(position.x() - origin.x())
				+ Math.abs(position.y() - origin.y())
				+ Math.abs(position.z() - origin.z());
			if (distance > maxPathSteps) {
				return UnderwaterEscapeSearch.Cell.blocked();
			}
			return observed.computeIfAbsent(position, candidate -> observeCell(minecraft, candidate));
		}
	}

	public record Snapshot(
		UnderwaterEscapeSearch.SearchStatus searchStatus,
		int candidateCount,
		UnderwaterEscapeNavigator.Snapshot navigation,
		boolean waitingForBaritoneRelease
	) {
		public Snapshot {
			Objects.requireNonNull(searchStatus, "searchStatus");
			Objects.requireNonNull(navigation, "navigation");
		}
	}
}
