package ai.moeru.airicraft.control;

import ai.moeru.airicraft.control.ChannelIntent.Hotbar;
import ai.moeru.airicraft.control.ChannelIntent.Locomotion;
import ai.moeru.airicraft.control.ControlArbiter.Acquisition;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlArbiterTest {
	private static final Locomotion FORWARD = new Locomotion(true, false, false, false, false, false, false);
	private static final Locomotion BACKWARD = new Locomotion(false, true, false, false, false, false, false);
	private static final Set<Channel> MOVE = Set.of(Channel.LOCOMOTION, Channel.LOOK);

	private final ControlArbiter arbiter = new ControlArbiter();

	@Test
	void anUnheldPlayerBelongsToTheHuman() {
		ControlFrame frame = arbiter.frame();

		assertTrue(frame.owners().isEmpty());
		assertNull(frame.locomotion(), "no lease, no key writes");
		assertNull(frame.look());
		assertEquals(-1, frame.hotbarSlot());
	}

	@Test
	void aSubmittedIntentAppliesEveryTickUntilReplaced() {
		ControlLease lease = grant("nav", Priority.FOREGROUND, MOVE);
		arbiter.submit(lease, FORWARD);

		assertEquals(FORWARD, arbiter.frame().locomotion());
		assertEquals(FORWARD, arbiter.frame().locomotion(), "sticky, like a held key");

		arbiter.submit(lease, BACKWARD);
		assertEquals(BACKWARD, arbiter.frame().locomotion());
	}

	@Test
	void aHeldChannelWithoutAnIntentPressesNothing() {
		grant("nav", Priority.FOREGROUND, MOVE);

		ControlFrame frame = arbiter.frame();

		assertEquals("nav", frame.owner(Channel.LOCOMOTION));
		assertEquals(Locomotion.NONE, frame.locomotion());
	}

	@Test
	void releasingClearsTheChannelsInTheNextFrameOnly() {
		ControlLease lease = grant("nav", Priority.FOREGROUND, MOVE);
		arbiter.submit(lease, FORWARD);
		arbiter.frame();

		arbiter.release(lease);
		ControlFrame frame = arbiter.frame();

		assertFalse(frame.held(Channel.LOCOMOTION));
		assertEquals(Set.of(Channel.LOCOMOTION, Channel.LOOK), frame.released());
		assertEquals(Locomotion.NONE, frame.locomotion(), "the release edge clears the keys once");
		assertNull(arbiter.frame().locomotion(), "then the player is the human's again");
	}

	@Test
	void releaseIsIdempotent() {
		ControlLease lease = grant("nav", Priority.FOREGROUND, MOVE);

		arbiter.release(lease);
		arbiter.release(lease);

		assertSame(ControlArbiter.Status.RELEASED, arbiter.status(lease));
	}

	@Test
	void aHigherPriorityAcquireRevokesTheHolderInTheSameCall() {
		ControlLease nav = grant("nav", Priority.FOREGROUND, MOVE);
		arbiter.submit(nav, FORWARD);

		Acquisition result = arbiter.acquire("reflex", Priority.REFLEX, Set.of(Channel.LOCOMOTION));

		ControlLease reflex = assertInstanceOf(Acquisition.Granted.class, result).lease();
		assertEquals(List.of(nav), ((Acquisition.Granted) result).displaced());
		ControlArbiter.Status.Revoked revoked = assertInstanceOf(ControlArbiter.Status.Revoked.class, arbiter.status(nav));
		assertSame(reflex, revoked.by());
		assertFalse(arbiter.submit(nav, BACKWARD), "a revoked holder cannot write");
		assertNull(arbiter.frame().look(), "the whole lease went, including its unchallenged look channel");
	}

	@Test
	void theRevokedHoldersOtherChannelsAreClearedNotInherited() {
		ControlLease nav = grant("nav", Priority.FOREGROUND, MOVE);
		arbiter.submit(nav, new ChannelIntent.Look(1, 2, 3, "nav"));
		arbiter.frame();

		grant("reflex", Priority.REFLEX, Set.of(Channel.LOCOMOTION));
		ControlFrame frame = arbiter.frame();

		assertEquals("reflex", frame.owner(Channel.LOCOMOTION));
		assertFalse(frame.held(Channel.LOOK));
		assertTrue(frame.released().contains(Channel.LOOK), "look is freed for the human or the next lease");
		assertFalse(frame.released().contains(Channel.LOCOMOTION), "locomotion was handed over, not freed");
		assertNull(frame.look(), "the revoked look target does not survive");
	}

	@Test
	void aLowerPriorityAcquireIsDeniedAndChangesNothing() {
		ControlLease reflex = grant("reflex", Priority.REFLEX, Set.of(Channel.LOCOMOTION));
		arbiter.submit(reflex, FORWARD);

		Acquisition result = arbiter.acquire("nav", Priority.FOREGROUND, MOVE);

		assertSame(reflex, assertInstanceOf(Acquisition.Denied.class, result).holder());
		assertFalse(arbiter.holder(Channel.LOOK).isPresent(), "a denied acquire takes no channel, not even a free one");
		assertEquals(FORWARD, arbiter.frame().locomotion());
	}

	@Test
	void separateChannelsAreLeasedSeparately() {
		ControlLease nav = grant("nav", Priority.FOREGROUND, MOVE);
		ControlLease eating = grant("eat", Priority.BACKGROUND, Set.of(Channel.HOTBAR));
		arbiter.submit(nav, FORWARD);
		arbiter.submit(eating, new Hotbar(4));

		ControlFrame frame = arbiter.frame();

		assertEquals(FORWARD, frame.locomotion());
		assertEquals(4, frame.hotbarSlot());
		assertSame(ControlArbiter.Status.HELD, arbiter.status(nav));
		assertSame(ControlArbiter.Status.HELD, arbiter.status(eating));
	}

	@Test
	void equalPriorityTakesOverSoAStaleHolderCannotLockOutItsSuccessor() {
		ControlLease stale = grant("old executor", Priority.FOREGROUND, MOVE);
		arbiter.submit(stale, FORWARD);

		ControlLease next = grant("new executor", Priority.FOREGROUND, MOVE);

		assertInstanceOf(ControlArbiter.Status.Revoked.class, arbiter.status(stale));
		assertEquals(Locomotion.NONE, arbiter.frame().locomotion(), "the successor starts from nothing pressed");
		assertTrue(arbiter.submit(next, BACKWARD));
	}

	@Test
	void releasingARevokedLeaseDoesNotDisturbTheNewHolder() {
		ControlLease nav = grant("nav", Priority.FOREGROUND, MOVE);
		ControlLease reflex = grant("reflex", Priority.REFLEX, Set.of(Channel.LOCOMOTION));
		arbiter.submit(reflex, FORWARD);

		arbiter.release(nav);

		assertSame(ControlArbiter.Status.RELEASED, arbiter.status(nav), "release acknowledges the revocation");
		assertEquals(FORWARD, arbiter.frame().locomotion());
		assertSame(ControlArbiter.Status.HELD, arbiter.status(reflex));
	}

	@Test
	void aSameTickHandoverIsNotReportedAsARelease() {
		ControlLease nav = grant("nav", Priority.FOREGROUND, Set.of(Channel.LOCOMOTION));
		arbiter.submit(nav, FORWARD);
		arbiter.frame();

		arbiter.release(nav);
		grant("next", Priority.FOREGROUND, Set.of(Channel.LOCOMOTION));
		ControlFrame frame = arbiter.frame();

		assertTrue(frame.released().isEmpty());
		assertEquals("next", frame.owner(Channel.LOCOMOTION));
	}

	@Test
	void anIntentOutsideTheLeaseIsAProgrammingError() {
		ControlLease lease = grant("nav", Priority.FOREGROUND, Set.of(Channel.LOCOMOTION));

		assertThrows(IllegalArgumentException.class, () -> arbiter.submit(lease, new Hotbar(1)));
	}

	@Test
	void revocationRecordsAreBounded() {
		ControlLease first = grant("first", Priority.FOREGROUND, MOVE);
		for (int i = 0; i < 200; i++) grant("later " + i, Priority.FOREGROUND, MOVE);

		assertSame(ControlArbiter.Status.RELEASED, arbiter.status(first), "an unread revocation is eventually forgotten");
	}

	@Test
	void locomotionResolvesKeysTheWayAKeyboardDoes() {
		Locomotion contradictory = new Locomotion(true, true, true, true, false, false, true);
		Locomotion sneakingSprint = new Locomotion(true, false, false, false, false, true, true);
		Locomotion strafingSprint = new Locomotion(false, false, true, false, false, false, true);

		assertTrue(contradictory.idle(), "opposite keys cancel");
		assertFalse(sneakingSprint.sprint(), "sneaking stops a sprint");
		assertFalse(strafingSprint.sprint(), "sprinting needs forward");
	}

	@Test
	void channelSetsAreCopiedSoALeaseCannotBeWidenedAfterward() {
		Set<Channel> mutable = EnumSet.of(Channel.LOCOMOTION);
		ControlLease lease = grant("nav", Priority.FOREGROUND, mutable);
		mutable.add(Channel.HOTBAR);

		assertFalse(lease.covers(Channel.HOTBAR));
	}

	@Test
	void aHotbarSlotOutsideTheBarIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> new Hotbar(9));
		assertThrows(IllegalArgumentException.class, () -> new Hotbar(-1));
	}

	private ControlLease grant(String owner, Priority priority, Set<Channel> channels) {
		return assertInstanceOf(Acquisition.Granted.class, arbiter.acquire(owner, priority, channels)).lease();
	}
}
