package ai.moeru.airicraft.control;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Decides who controls the player. Pure and single-threaded: the client thread calls it.
 *
 * <p>Priority order is {@link Priority#REFLEX} over {@link Priority#FOREGROUND} over
 * {@link Priority#BACKGROUND}. An acquire takes every requested channel whose holder has the same or
 * lower priority, and revokes those holders' whole leases in the same call: no holder waits for
 * another to acknowledge. If any requested channel is held at a higher priority, the acquire is
 * denied and nothing changes. Equal priority takes over, which matches "last writer wins" for the
 * one foreground executor and lets a stale holder that never released be replaced.
 *
 * <p>A revoked holder finds out from {@link #status} on its next tick, and its handle ends
 * preempted. Releasing is idempotent and clears the channels for the next frame.
 */
public final class ControlArbiter {
	/** Revocation records kept for holders that have not looked yet. */
	private static final int REVOKED_LIMIT = 64;

	private final Map<Channel, ControlLease> holders = new EnumMap<>(Channel.class);
	private final Map<Channel, ChannelIntent> intents = new EnumMap<>(Channel.class);
	private final Set<Channel> released = EnumSet.noneOf(Channel.class);
	private final Map<Long, Revocation> revoked = new LinkedHashMap<>();
	private long nextId = 1;

	/** Takes {@code channels} for {@code owner}, revoking weaker holders, or names the holder that blocks it. */
	public Acquisition acquire(String owner, Priority priority, Set<Channel> channels) {
		ControlLease request = new ControlLease(nextId, owner, priority, channels);
		for (Channel channel : request.channels()) {
			ControlLease holder = holders.get(channel);
			if (holder != null && holder.priority().compareTo(priority) > 0) return new Acquisition.Denied(holder);
		}
		nextId++;
		List<ControlLease> displaced = new ArrayList<>();
		for (Channel channel : request.channels()) {
			ControlLease holder = holders.get(channel);
			if (holder != null && !displaced.contains(holder)) displaced.add(holder);
		}
		for (ControlLease holder : displaced) {
			drop(holder);
			revoked.put(holder.id(), new Revocation(request));
			while (revoked.size() > REVOKED_LIMIT) revoked.remove(revoked.keySet().iterator().next());
		}
		for (Channel channel : request.channels()) {
			holders.put(channel, request);
			// A takeover in the same tick is a handover, not a release: the new holder's intents apply.
			released.remove(channel);
		}
		return new Acquisition.Granted(request, List.copyOf(displaced));
	}

	/** Ends a lease and forgets a revocation. Safe to call twice, or on a revoked lease. */
	public void release(ControlLease lease) {
		revoked.remove(lease.id());
		if (status(lease) instanceof Status.Held) drop(lease);
	}

	public Status status(ControlLease lease) {
		if (holds(lease)) return Status.HELD;
		Revocation revocation = revoked.get(lease.id());
		return revocation == null ? Status.RELEASED : new Status.Revoked(revocation.by());
	}

	/**
	 * Records what {@code lease} wants from a channel it holds; it applies from the next frame on.
	 * A lease that is no longer held is ignored, so a revoked holder cannot write.
	 *
	 * @return whether the intent was accepted
	 */
	public boolean submit(ControlLease lease, ChannelIntent intent) {
		if (!lease.covers(intent.channel())) {
			throw new IllegalArgumentException(lease.owner() + " has no lease on " + intent.channel());
		}
		if (holders.get(intent.channel()) != lease) return false;
		intents.put(intent.channel(), intent);
		return true;
	}

	public Optional<ControlLease> holder(Channel channel) {
		return Optional.ofNullable(holders.get(channel));
	}

	/** Merges the held channels into this tick's frame and forgets which channels were just released. */
	public ControlFrame frame() {
		Map<Channel, String> owners = new EnumMap<>(Channel.class);
		holders.forEach((channel, lease) -> owners.put(channel, lease.owner()));
		ControlFrame frame = new ControlFrame(owners, intents, released);
		released.clear();
		return frame;
	}

	private boolean holds(ControlLease lease) {
		for (Channel channel : lease.channels()) {
			if (holders.get(channel) == lease) return true;
		}
		return false;
	}

	private void drop(ControlLease lease) {
		for (Channel channel : lease.channels()) {
			if (holders.get(channel) != lease) continue;
			holders.remove(channel);
			intents.remove(channel);
			released.add(channel);
		}
	}

	public sealed interface Acquisition {
		/** @param displaced leases revoked by this acquire, weakest first */
		record Granted(ControlLease lease, List<ControlLease> displaced) implements Acquisition { }

		/** @param holder the higher-priority lease in the way */
		record Denied(ControlLease holder) implements Acquisition { }
	}

	public sealed interface Status {
		Held HELD = new Held();
		Released RELEASED = new Released();

		record Held() implements Status { }

		record Released() implements Status { }

		/** @param by the lease that took the channels */
		record Revoked(ControlLease by) implements Status { }
	}

	private record Revocation(ControlLease by) { }
}
