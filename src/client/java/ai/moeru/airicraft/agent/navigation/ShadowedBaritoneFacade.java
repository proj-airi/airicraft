package ai.moeru.airicraft.agent.navigation;

import ai.moeru.airicraft.agent.baritone.BaritoneFacade;
import ai.moeru.airicraft.agent.goals.GoalPosition;
import ai.moeru.airicraft.navigation.BodyState;
import ai.moeru.airicraft.navigation.Goal;
import ai.moeru.airicraft.navigation.GridPos;
import ai.moeru.airicraft.navigation.MovementPolicy;
import ai.moeru.airicraft.navigation.Path;
import ai.moeru.airicraft.navigation.SearchBudget;
import ai.moeru.airicraft.navigation.SearchResult;
import net.minecraft.client.MinecraftClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Baritone with in-house shadow planning: every navigation request is also planned by
 * navigation-core against the same goal, and the outcome lands in the task's diagnostics. Nothing
 * the shadow plan does actuates.
 */
public final class ShadowedBaritoneFacade implements BaritoneFacade {
	private static final SearchBudget BUDGET = new SearchBudget(250_000, 1_500_000_000L, 2.0);
	private final BaritoneFacade delegate;
	private final NavigationPlanner planner;
	private NavigationPlanner.Pending shadow;
	private double captureMillis;
	private String shadowSkipped;

	public ShadowedBaritoneFacade(BaritoneFacade delegate, NavigationPlanner planner) {
		this.delegate = delegate;
		this.planner = planner;
	}

	@Override
	public void startNavigate(GoalPosition position) {
		delegate.startNavigate(position);
		if (position == null) return;
		shadow(position.exactY() ? new Goal.Block(position.x(), position.y(), position.z()) : new Goal.XZ(position.x(), position.z()),
			position, position.exactY());
	}

	@Override
	public void startNavigateNear(GoalPosition position, int radiusBlocks) {
		delegate.startNavigateNear(position, radiusBlocks);
		if (position == null) return;
		shadow(new Goal.Near(position.x(), position.y(), position.z(), Math.max(1, radiusBlocks)), position, true);
	}

	@Override
	public void startFollow(String playerName) {
		clearShadow();
		delegate.startFollow(playerName);
	}

	private void shadow(Goal goal, GoalPosition position, boolean hasY) {
		clearShadow();
		MinecraftClient client = MinecraftClient.getInstance();
		if (client == null || !client.isOnThread() || client.player == null || client.world == null) {
			shadowSkipped = "no_client";
			return;
		}
		try {
			MovementPolicy policy = NavigationPolicies.forPlayer(client, delegate.walkOnWaterPenalty());
			if (policy == null) {
				shadowSkipped = "travel_policy_unavailable";
				return;
			}
			var player = client.player;
			GridPos start = new BodyState(player.getX(), player.getY(), player.getZ(), 0, true, false, false, false, 0).feet();
			GridPos target = new GridPos(position.x(), hasY ? position.y() : start.y(), position.z());
			WorldTerrainSnapshot snapshot = WorldTerrainSnapshot.capture(client.world, start, target, hasY,
				MinecraftCellClassifier.forPlayer(player));
			captureMillis = snapshot.captureMillis();
			shadow = planner.submit(snapshot, policy, start, goal, BUDGET);
		}
		catch (RuntimeException exception) {
			shadowSkipped = "shadow_error " + exception.getClass().getSimpleName();
		}
	}

	private void clearShadow() {
		if (shadow != null) shadow.cancel();
		shadow = null;
		shadowSkipped = null;
	}

	@Override
	public Map<String, Object> navigationDiagnostics() {
		Map<String, Object> diagnostics = new LinkedHashMap<>();
		diagnostics.put("backend", "baritone");
		Map<String, Object> summary = new LinkedHashMap<>();
		if (shadowSkipped != null) summary.put("outcome", "skipped " + shadowSkipped);
		else if (shadow == null) return diagnostics;
		else if (!shadow.done()) summary.put("outcome", "pending");
		else {
			SearchResult result = shadow.result();
			if (result == null) summary.put("outcome", "error");
			else {
				summary.put("outcome", result.outcome());
				if (result instanceof SearchResult.Partial partial) summary.put("reason", partial.reason().name());
				if (result instanceof SearchResult.Unreachable unreachable) summary.put("reason", unreachable.reason().name());
				Path path = result instanceof SearchResult.Found found ? found.path()
					: result instanceof SearchResult.Partial partial ? partial.path() : null;
				if (path != null) {
					summary.put("steps", path.steps().size());
					summary.put("cost", Math.round(path.cost() * 100) / 100.0);
					summary.put("length", Math.round(path.length() * 100) / 100.0);
					summary.put("breaks", path.breaks());
					summary.put("places", path.places());
				}
				summary.put("expanded", result.stats().expanded());
				summary.put("searchMillis", Math.round(result.stats().millis() * 100) / 100.0);
			}
		}
		summary.put("captureMillis", Math.round(captureMillis * 100) / 100.0);
		diagnostics.put("shadow", summary);
		return diagnostics;
	}

	@Override public boolean isLoaded() { return delegate.isLoaded(); }
	@Override public void applySettings() { delegate.applySettings(); }
	@Override public double walkOnWaterPenalty() { return delegate.walkOnWaterPenalty(); }
	@Override public void setWalkOnWaterPenalty(double value) { delegate.setWalkOnWaterPenalty(value); }
	@Override public boolean processActive() { return delegate.processActive(); }
	@Override public boolean cancel() { return delegate.cancel(); }
	@Override public boolean cancellationPending() { return delegate.cancellationPending(); }
	@Override public long cancellationAcknowledgement() { return delegate.cancellationAcknowledgement(); }
	@Override public Optional<String> activeProcessName() { return delegate.activeProcessName(); }
	@Override public Optional<Double> estimatedTicksToGoal() { return delegate.estimatedTicksToGoal(); }
	@Override public Optional<String> pollPathEvent() { return delegate.pollPathEvent(); }
	@Override public Optional<NavigationProgress> navigationProgress() { return delegate.navigationProgress(); }
	@Override public boolean navigationGoalReached(GoalPosition position) { return delegate.navigationGoalReached(position); }
}
