package ai.moeru.airicraft.agent.baritone;

import ai.moeru.airicraft.agent.goals.GoalMineSpec;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.process.IBaritoneProcess;
import net.minecraft.util.math.BlockPos;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class LiveBaritoneFacade implements BaritoneFacade {
	private final IBaritone baritone;
	private final Runnable settingsApplier;
	private final java.util.function.BooleanSupplier arrivalSupported;
	private final ConcurrentLinkedQueue<PathEvent> pathEvents = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Long> expectedCancellations = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Long> deferredCancellations = new ConcurrentLinkedQueue<>();
	private final AtomicLong cancellationSequence = new AtomicLong();
	private final AtomicLong cancellationAcknowledgement = new AtomicLong();
	private long operationGeneration;
	private long cancelledOperationGeneration = Long.MIN_VALUE;

	public LiveBaritoneFacade() {
		this(BaritoneAPI.getProvider().getPrimaryBaritone(), BaritoneSettingsProfile::apply);
	}

	LiveBaritoneFacade(IBaritone baritone, Runnable settingsApplier) {
		this(baritone, settingsApplier, null);
	}

	LiveBaritoneFacade(IBaritone baritone, Runnable settingsApplier, java.util.function.BooleanSupplier arrivalSupported) {
		this.arrivalSupported = arrivalSupported;
		this.baritone = baritone;
		this.settingsApplier = Objects.requireNonNull(settingsApplier, "settingsApplier");
		if (baritone != null) {
			baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
				@Override
				public void onTick(TickEvent event) {
					if (event.getType() == TickEvent.Type.IN
						&& event.getState() == EventState.PRE)
						NavigationDoorInteraction.restorePassedDoor(baritone.getPlayerContext());
				}

				@Override
				public void onPathEvent(PathEvent event) {
					if (event == PathEvent.CANCELED) {
						Long expectedSequence = expectedCancellations.poll();
						if (expectedSequence == null) {
							expectedSequence = deferredCancellations.poll();
						}
						if (expectedSequence != null) {
							cancellationAcknowledgement.accumulateAndGet(expectedSequence, Math::max);
							return;
						}
					}
					pathEvents.add(event);
				}
			});
		}
	}

	@Override
	public boolean isLoaded() {
		return baritone != null;
	}

	@Override
	public void applySettings() {
		settingsApplier.run();
	}

	@Override
	public double walkOnWaterPenalty() {
		return BaritoneAPI.getSettings().walkOnWaterOnePenalty.value;
	}

	@Override
	public void setWalkOnWaterPenalty(double value) {
		BaritoneAPI.getSettings().walkOnWaterOnePenalty.value = value;
	}

	@Override
	public void startFollow(String playerName) {
		if (!isLoaded() || playerName == null || playerName.isBlank()) {
			return;
		}
		beginOperation();
		baritone.getFollowProcess().follow(entity ->
			entity != null
				&& entity.getName() != null
				&& playerName.equalsIgnoreCase(entity.getName().getString())
		);
	}

	@Override
	public void startNavigate(GoalPosition position) {
		if (!isLoaded() || position == null) {
			return;
		}
		beginOperation();
		baritone.getCustomGoalProcess().setGoalAndPath(position.exactY()
			? new GoalBlock(position.x(), position.y(), position.z())
			: new GoalXZ(position.x(), position.z()));
	}

	@Override
	public void startNavigateNear(GoalPosition position, int radiusBlocks) {
		if (!isLoaded() || position == null) {
			return;
		}
		beginOperation();
		baritone.getCustomGoalProcess().setGoalAndPath(new GoalNear(
			new BlockPos(position.x(), position.y(), position.z()),
			Math.max(1, radiusBlocks)
		));
	}

	@Override
	public void startMine(GoalMineSpec spec) {
		if (!isLoaded() || spec == null) {
			return;
		}
		beginOperation();
		baritone.getMineProcess().mineByName(spec.quantity(), spec.blockIds().toArray(String[]::new));
	}

	@Override
	public boolean mineProcessActive() {
		return isLoaded() && baritone.getMineProcess().isActive();
	}

	@Override
	public boolean processActive() {
		if (!isLoaded()) {
			return false;
		}
		IPathingBehavior pathing = baritone.getPathingBehavior();
		return baritone.getMineProcess().isActive()
			|| baritone.getCustomGoalProcess().isActive()
			|| baritone.getFollowProcess().isActive()
			|| (pathing != null && (pathing.isPathing() || pathing.getInProgress().isPresent()))
			|| baritone.getPathingControlManager().mostRecentInControl()
				.filter(IBaritoneProcess::isActive)
				.isPresent();
	}

	@Override
	public boolean cancel() {
		if (!isLoaded()) {
			return false;
		}
		if (cancelledOperationGeneration == operationGeneration) {
			return cancellationPending();
		}
		if (!processActive()) {
			return cancellationPending();
		}
		pathEvents.clear();
		boolean cancellationQueued = baritone.getPathingBehavior().cancelEverything();
		cancelledOperationGeneration = operationGeneration;
		long sequence = cancellationSequence.incrementAndGet();
		if (cancellationQueued) {
			expectedCancellations.add(sequence);
		}
		else {
			// A later null-command tick may emit CANCELED, while a replacement
			// process may soft-cancel without one. Suppress it if it arrives, but
			// do not make release ownership depend on an optional event.
			deferredCancellations.add(sequence);
		}
		return cancellationQueued;
	}

	@Override
	public boolean cancellationPending() {
		return !expectedCancellations.isEmpty();
	}

	@Override
	public long cancellationAcknowledgement() {
		return cancellationAcknowledgement.get();
	}

	private void beginOperation() {
		operationGeneration++;
		pathEvents.clear();
		deferredCancellations.clear();
	}

	@Override
	public Optional<String> activeProcessName() {
		if (!isLoaded()) {
			return Optional.empty();
		}
		return baritone.getPathingControlManager()
			.mostRecentInControl()
			.map(IBaritoneProcess::displayName0);
	}

	@Override
	public Optional<Double> estimatedTicksToGoal() {
		if (!isLoaded()) {
			return Optional.empty();
		}
		IPathingBehavior pathingBehavior = baritone.getPathingBehavior();
		return pathingBehavior == null ? Optional.empty() : pathingBehavior.estimatedTicksToGoal();
	}

	@Override
	public Optional<String> pollPathEvent() {
		PathEvent event = pathEvents.poll();
		return event == null ? Optional.empty() : Optional.of(event.name());
	}

	@Override public Optional<NavigationProgress> navigationProgress() {
		if (!processActive()) return Optional.empty();
		var context = baritone.getPlayerContext();
		var player = context.player();
		if (player == null) return Optional.empty();
		var goal = baritone.getPathingBehavior().getGoal();
		boolean supported = player.isOnGround() || player.isTouchingWater() || player.isClimbing();
		if (goal != null && supported && goal.isInGoal(context.playerFeet())) return Optional.empty();
		var manager = context.minecraft().interactionManager;
		String breakingTarget = null;
		float progress = 0;
		if (manager != null) {
			var breaking = (ai.moeru.airicraft.mixin.client.ClientPlayerInteractionManagerAccessor) manager;
			if (breaking.airicraft$breakingBlock()) {
				breakingTarget = breaking.airicraft$currentBreakingPos().toShortString();
				progress = breaking.airicraft$currentBreakingProgress();
			}
		}
		return Optional.of(new NavigationProgress(player.getX(), player.getY(), player.getZ(), supported, breakingTarget, progress));
	}

	@Override
	public boolean navigationGoalReached(GoalPosition position) {
		if (position == null) {
			return false;
		}
		if (baritone == null || baritone.getPlayerContext() == null) {
			return false;
		}

		var player = baritone.getPlayerContext().player();
		boolean supported = arrivalSupported != null ? arrivalSupported.getAsBoolean()
			: player != null && (player.isOnGround() || player.isTouchingWater() || player.isClimbing());
		if (!supported) return false;
		BlockPos playerBlockPos = baritone.getPlayerContext().playerFeet();
		if (playerBlockPos == null) return false;
		if (position.exactY()) {
			return playerBlockPos.getX() == position.x()
				&& playerBlockPos.getY() == position.y()
				&& playerBlockPos.getZ() == position.z();
		}
		return playerBlockPos.getX() == position.x()
			&& playerBlockPos.getZ() == position.z();
	}
}
