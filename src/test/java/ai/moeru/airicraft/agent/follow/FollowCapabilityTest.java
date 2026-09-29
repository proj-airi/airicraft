package ai.moeru.airicraft.agent.follow;

import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.goals.GoalSnapshot;
import ai.moeru.airicraft.agent.goals.GoalType;
import ai.moeru.airicraft.agent.session.SessionMode;
import ai.moeru.airicraft.agent.session.SessionSnapshot;
import ai.moeru.airicraft.agent.social.NearbyPlayerTracker;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FollowCapabilityTest {
	@Test
	void preservesTargetTrackingInSingleplayerLocalWhileActuationIsBlocked() {
		FollowCapability capability = new FollowCapability();
		NearbyPlayerTracker tracker = new NearbyPlayerTracker();
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(16);
		SessionSnapshot session = new SessionSnapshot(
			SessionMode.SINGLEPLAYER_LOCAL,
			true,
			true,
			"minecraft:overworld",
			false,
			-1,
			10L
		);
		GoalSnapshot goal = new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alice", 10L, "test");

		assertFalse(session.companionActuationAllowed());

		tracker.injectPlayerNearby("Alice", new Vec3(5.0D, 64.0D, 0.0D), 10L, eventBuffer);
		FollowState acquired = capability.tick(null, session, Optional.of(goal), tracker, 11L, eventBuffer);

		assertTrue(acquired.goalActive());
		assertTrue(acquired.targetNearby());
		assertTrue(eventBuffer.containsTypeForPlayer("follow.target_acquired", "Alice"));

		tracker.injectPlayerDisconnect("Alice", 12L, eventBuffer);
		FollowState lost = capability.tick(null, session, Optional.of(goal), tracker, 13L, eventBuffer);

		assertTrue(lost.goalActive());
		assertFalse(lost.targetNearby());
		assertEquals("Alice", lost.targetPlayer());
		assertTrue(eventBuffer.containsTypeForPlayer("follow.target_lost", "Alice"));
	}

	@Test
	void refreshesFollowTargetCoordinatesAfterPlayerMoves() {
		FollowCapability capability = new FollowCapability();
		NearbyPlayerTracker tracker = new NearbyPlayerTracker();
		SemanticEventBuffer eventBuffer = new SemanticEventBuffer(16);
		SessionSnapshot session = new SessionSnapshot(
			SessionMode.REMOTE_MULTIPLAYER,
			true,
			true,
			"minecraft:overworld",
			false,
			0,
			10L
		);
		GoalSnapshot goal = new GoalSnapshot(GoalType.FOLLOW_PLAYER, "Alice", 10L, "test");

		tracker.injectPlayerNearby("Alice", new Vec3(5.0D, 64.0D, 0.0D), 10L, eventBuffer);
		FollowState initial = capability.tick(null, session, Optional.of(goal), tracker, 11L, eventBuffer);
		tracker.injectPlayerMove("Alice", new Vec3(9.0D, 65.0D, -3.0D), 12L, eventBuffer);
		FollowState moved = capability.tick(null, session, Optional.of(goal), tracker, 13L, eventBuffer);

		assertEquals(5.0D, initial.targetX());
		assertEquals(64.0D, initial.targetY());
		assertEquals(0.0D, initial.targetZ());
		assertEquals(9.0D, moved.targetX());
		assertEquals(65.0D, moved.targetY());
		assertEquals(-3.0D, moved.targetZ());
	}
}
