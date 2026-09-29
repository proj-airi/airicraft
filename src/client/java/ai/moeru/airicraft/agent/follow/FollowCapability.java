package ai.moeru.airicraft.agent.follow;

import ai.moeru.airicraft.agent.events.EventPublisher;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.social.NearbyPlayerSnapshot;
import ai.moeru.airicraft.agent.social.NearbyPlayerTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class FollowCapability {
	private static final String SOURCE = "FollowCapability";
	private FollowState state = FollowState.idle();
	private String acquiredTargetPlayer;

	public FollowState tick(
		Minecraft minecraft,
		SessionSnapshot sessionSnapshot,
		Optional<GoalSnapshot> activeGoal,
		NearbyPlayerTracker nearbyPlayerTracker,
		long tick,
		EventPublisher eventBuffer
	) {
		Objects.requireNonNull(sessionSnapshot, "sessionSnapshot");
		Objects.requireNonNull(activeGoal, "activeGoal");
		Objects.requireNonNull(nearbyPlayerTracker, "nearbyPlayerTracker");
		Objects.requireNonNull(eventBuffer, "eventBuffer");

		if (!sessionSnapshot.worldLoaded() || activeGoal.isEmpty() || activeGoal.get().type() != GoalType.FOLLOW_PLAYER) {
			emitTargetLostIfNeeded(tick, eventBuffer);
			state = FollowState.idle();
			return state;
		}

		String targetPlayer = activeGoal.get().targetPlayer();
		Optional<NearbyPlayerSnapshot> targetSnapshot = nearbyPlayerTracker.findByName(targetPlayer);
		if (targetSnapshot.isEmpty()) {
			emitTargetLostIfNeeded(tick, eventBuffer);
			state = new FollowState(true, targetPlayer, false, true, 0.0D, 0.0D, 0.0D, 0.0D);
			return state;
		}

		NearbyPlayerSnapshot target = targetSnapshot.get();
		if (!targetPlayer.equals(acquiredTargetPlayer)) {
			acquiredTargetPlayer = targetPlayer;
			eventBuffer.from(SOURCE).publish(tick, "follow.target_acquired", Map.of(
				"player", targetPlayer
			));
		}

		double distance = distanceToTarget(minecraft, target);
		state = new FollowState(
			true,
			targetPlayer,
			true,
			true,
			distance,
			target.x(),
			target.y(),
			target.z()
		);
		return state;
	}

	public FollowState state() {
		return state;
	}

	public void clear() {
		acquiredTargetPlayer = null;
		state = FollowState.idle();
	}

	private void emitTargetLostIfNeeded(long tick, EventPublisher eventBuffer) {
		if (acquiredTargetPlayer == null) {
			acquiredTargetPlayer = null;
			return;
		}

		eventBuffer.from(SOURCE).publish(tick, "follow.target_lost", Map.of(
			"player", acquiredTargetPlayer
		));
		acquiredTargetPlayer = null;
	}

	private static double distanceToTarget(Minecraft minecraft, NearbyPlayerSnapshot target) {
		if (minecraft == null || minecraft.player == null) {
			return 0.0D;
		}
		Vec3 playerPos = new Vec3(minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ());
		Vec3 targetPos = new Vec3(target.x(), target.y(), target.z());
		return playerPos.distanceTo(targetPos);
	}
}
