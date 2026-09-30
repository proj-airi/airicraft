package ai.moeru.airicraft.control;

import java.util.Set;

/**
 * A holder's claim on some channels. The arbiter issues it; whether it is still valid is the
 * arbiter's answer ({@link ControlArbiter#status}), not the lease's.
 */
public record ControlLease(long id, String owner, Priority priority, Set<Channel> channels) {
	public ControlLease {
		if (channels.isEmpty()) throw new IllegalArgumentException("a lease needs at least one channel");
		channels = Set.copyOf(channels);
	}

	public boolean covers(Channel channel) {
		return channels.contains(channel);
	}
}
