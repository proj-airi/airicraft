package ai.moeru.airicraft.agent.tasks;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure policy for local, environment-aware underwater harvesting.
 */
public final class UnderwaterHarvestPolicy {
	public static final int HORIZONTAL_RADIUS = 20;
	public static final int VERTICAL_RADIUS = 12;
	public static final int TARGET_BATCH_SIZE = 4;
	public static final int AIR_RESERVE_TICKS = 180;
	public static final int AIR_RESUME_MARGIN_TICKS = 20;
	public static final int APPROACH_TIMEOUT_TICKS = 100;
	public static final int APPROACH_PROGRESS_WINDOW_TICKS = 40;
	public static final double APPROACH_MINIMUM_PROGRESS_BLOCKS = 0.25D;
	public static final int OBSTACLE_ASCENT_TICKS = 12;
	public static final int GROUNDING_TIMEOUT_TICKS = 20;

	private UnderwaterHarvestPolicy() {
	}

	public static Optional<SourceEnvironment> classify(
		boolean sourceContainsFluid,
		boolean dryStandingApproach,
		boolean adjacentFluid
	) {
		if (sourceContainsFluid) {
			return Optional.of(SourceEnvironment.FLUID_CONTAINED);
		}
		if (adjacentFluid) {
			return Optional.of(SourceEnvironment.WATER_ADJACENT);
		}
		if (dryStandingApproach) {
			return Optional.of(SourceEnvironment.DRY);
		}
		return Optional.empty();
	}

	public static boolean shouldSurface(boolean submerged, int remainingAir, int maxAir) {
		return submerged && remainingAir <= Math.min(AIR_RESERVE_TICKS, Math.max(1, maxAir - AIR_RESUME_MARGIN_TICKS));
	}

	public static boolean mayResumeHarvest(boolean submerged, int remainingAir, int maxAir) {
		return !submerged && remainingAir >= Math.max(0, maxAir - AIR_RESUME_MARGIN_TICKS);
	}

	public static PreSourceAction preSourceAction(
		boolean surfacing,
		boolean airRecoveryRequired,
		boolean completionPending,
		boolean airRecoveryComplete,
		boolean pickupPending
	) {
		if (surfacing || airRecoveryRequired || (completionPending && !airRecoveryComplete)) {
			return PreSourceAction.RECOVER_AIR;
		}
		if (completionPending) {
			return PreSourceAction.COMPLETE;
		}
		return pickupPending ? PreSourceAction.PICK_UP : PreSourceAction.SOURCE_ACTION;
	}

	public static List<Target> selectBatch(List<Target> candidates, Position origin) {
		if (candidates == null || candidates.isEmpty() || origin == null) {
			return List.of();
		}
		return candidates.stream()
			.filter(target -> target != null && inBounds(target.position(), origin))
			.sorted(Comparator
				.comparingInt((Target target) -> environmentPriority(target.environment()))
				.thenComparingDouble(target -> squaredDistance(target.position(), origin))
				.thenComparingInt(target -> target.position().y())
				.thenComparingInt(target -> target.position().x())
				.thenComparingInt(target -> target.position().z()))
			.limit(TARGET_BATCH_SIZE)
			.toList();
	}

	public static TerminalDecision terminalDecision(int inventoryCount, int targetCount, int remainingTargets) {
		if (inventoryCount >= targetCount) {
			return TerminalDecision.COMPLETE;
		}
		return remainingTargets <= 0 ? TerminalDecision.RESOURCE_NOT_FOUND_NEARBY : TerminalDecision.CONTINUE;
	}

	public static boolean hasUnderwaterCandidate(List<Target> candidates) {
		return candidates != null && candidates.stream()
			.filter(Objects::nonNull)
			.anyMatch(target -> target.environment() != null && target.environment().underwater());
	}

	public static PositioningMode positioningMode(SourceEnvironment environment) {
		return environment == SourceEnvironment.DRY ? PositioningMode.NAVIGATION : PositioningMode.DIRECT;
	}

	public static boolean recoveryComplete(
		boolean airMarginReached,
		boolean escapeTargetReached,
		boolean escapeOwnsNavigation,
		boolean navigationReleased
	) {
		return airMarginReached
			&& escapeTargetReached
			&& !escapeOwnsNavigation
			&& navigationReleased;
	}

	public static SourceExhaustion sourceExhaustion(
		int discoveredSourceCount,
		int excludedSourceCount,
		boolean everyDiscoveredSourceExcluded
	) {
		return discoveredSourceCount > 0
			&& excludedSourceCount > 0
			&& everyDiscoveredSourceExcluded
			? SourceExhaustion.RESOURCE_UNREACHABLE_NEARBY
			: SourceExhaustion.RESOURCE_NOT_FOUND_NEARBY;
	}

	public static ApproachProgress beginApproach(double distanceBlocks) {
		return new ApproachProgress(0, 0, normalizedDistance(distanceBlocks));
	}

	public static ApproachUpdate observeApproach(ApproachProgress previous, double distanceBlocks) {
		ApproachProgress current = previous == null ? beginApproach(distanceBlocks) : previous;
		double distance = normalizedDistance(distanceBlocks);
		int activeTicks = current.activeTicks() + 1;
		int windowTicks = current.windowTicks() + 1;
		double windowStartDistance = current.windowStartDistance();
		if (windowStartDistance - distance >= APPROACH_MINIMUM_PROGRESS_BLOCKS) {
			windowTicks = 0;
			windowStartDistance = distance;
		}
		ApproachProgress next = new ApproachProgress(activeTicks, windowTicks, windowStartDistance);
		boolean timedOut = activeTicks >= APPROACH_TIMEOUT_TICKS;
		boolean stalled = windowTicks >= APPROACH_PROGRESS_WINDOW_TICKS;
		return new ApproachUpdate(timedOut || stalled ? ApproachDecision.EXCLUDE_TARGET : ApproachDecision.CONTINUE, next);
	}

	public static VerticalMotion underwaterVerticalMotion(
		double targetDeltaFromEye,
		boolean horizontalCollision,
		boolean obstacleAscentActive,
		boolean ascentClear,
		boolean descentClear
	) {
		if (ascentClear && (horizontalCollision || obstacleAscentActive || targetDeltaFromEye > 0.25D)) {
			return VerticalMotion.ASCEND;
		}
		if (descentClear && targetDeltaFromEye < -0.75D) {
			return VerticalMotion.DESCEND;
		}
		return VerticalMotion.LEVEL;
	}

	public static GroundingDecision groundingDecision(
		boolean underwaterTarget,
		boolean submerged,
		boolean onGround,
		boolean solidSupportDirectlyBelow,
		int activeTicks
	) {
		if (!underwaterTarget || !submerged || onGround || !solidSupportDirectlyBelow) {
			return GroundingDecision.SKIP;
		}
		return activeTicks < GROUNDING_TIMEOUT_TICKS
			? GroundingDecision.DESCEND
			: GroundingDecision.GIVE_UP;
	}

	public static PickupDecision pickupDecision(
		int inventoryBeforeBreak,
		int inventoryCount,
		int remainingPickupTicks,
		boolean matchingDropPresent
	) {
		if (inventoryCount > inventoryBeforeBreak) {
			return PickupDecision.COLLECTED;
		}
		if (remainingPickupTicks <= 0) {
			return PickupDecision.UNREACHABLE;
		}
		return matchingDropPresent ? PickupDecision.APPROACH : PickupDecision.WAIT;
	}

	public static PickupTarget pickupTarget(double itemX, double itemY, double itemZ) {
		return new PickupTarget(
			Math.floor(itemX) + 0.5D,
			Math.floor(itemY) + 0.5D,
			Math.floor(itemZ) + 0.5D
		);
	}

	public static int pickupTicksAfterTick(int remainingPickupTicks, boolean recoveringAir) {
		return recoveringAir ? remainingPickupTicks : Math.max(-1, remainingPickupTicks - 1);
	}

	private static boolean inBounds(Position target, Position origin) {
		return Math.abs(target.x() - origin.x()) <= HORIZONTAL_RADIUS
			&& Math.abs(target.z() - origin.z()) <= HORIZONTAL_RADIUS
			&& Math.abs(target.y() - origin.y()) <= VERTICAL_RADIUS;
	}

	private static double squaredDistance(Position left, Position right) {
		double dx = left.x() - right.x();
		double dy = left.y() - right.y();
		double dz = left.z() - right.z();
		return dx * dx + dy * dy + dz * dz;
	}

	private static int environmentPriority(SourceEnvironment environment) {
		return switch (environment) {
			case DRY -> 0;
			case WATER_ADJACENT -> 1;
			case FLUID_CONTAINED -> 2;
		};
	}

	private static double normalizedDistance(double distanceBlocks) {
		return Double.isFinite(distanceBlocks) && distanceBlocks >= 0.0D
			? distanceBlocks
			: Double.POSITIVE_INFINITY;
	}

	public enum SourceEnvironment {
		DRY,
		WATER_ADJACENT,
		FLUID_CONTAINED;

		public boolean underwater() {
			return this != DRY;
		}
	}

	public enum PositioningMode {
		NAVIGATION,
		DIRECT
	}

	public enum PreSourceAction {
		RECOVER_AIR,
		COMPLETE,
		PICK_UP,
		SOURCE_ACTION
	}

	public enum TerminalDecision {
		CONTINUE,
		COMPLETE,
		RESOURCE_NOT_FOUND_NEARBY
	}

	public enum PickupDecision {
		APPROACH,
		WAIT,
		COLLECTED,
		UNREACHABLE
	}

	public enum SourceExhaustion {
		RESOURCE_NOT_FOUND_NEARBY,
		RESOURCE_UNREACHABLE_NEARBY
	}

	public enum ApproachDecision {
		CONTINUE,
		EXCLUDE_TARGET
	}

	public enum VerticalMotion {
		ASCEND,
		LEVEL,
		DESCEND
	}

	public enum GroundingDecision {
		DESCEND,
		SKIP,
		GIVE_UP
	}

	public record Position(int x, int y, int z) {
	}

	public record PickupTarget(double x, double y, double z) {
	}

	public record ApproachProgress(int activeTicks, int windowTicks, double windowStartDistance) {
		public ApproachProgress {
			if (activeTicks < 0 || windowTicks < 0) {
				throw new IllegalArgumentException("approach tick counts must be non-negative");
			}
		}
	}

	public record ApproachUpdate(ApproachDecision decision, ApproachProgress progress) {
		public ApproachUpdate {
			Objects.requireNonNull(decision, "decision");
			Objects.requireNonNull(progress, "progress");
		}
	}

	public record Target(Position position, String blockId, SourceEnvironment environment) {
	}
}
