package ai.moeru.airicraft.agent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Calls lifecycle participants in registration order. */
final class LifecycleDispatcher {
	@FunctionalInterface
	interface BoundaryHandler {
		void handle(LifecycleBoundary boundary, long tick);
	}

	private record Participant(String id, EnumSet<LifecycleBoundary> boundaries, BoundaryHandler handler) {}

	private final List<Participant> participants = new ArrayList<>();

	void register(String id, EnumSet<LifecycleBoundary> boundaries, BoundaryHandler handler) {
		if (participants.stream().anyMatch(participant -> participant.id().equals(id))) {
			throw new IllegalArgumentException("Duplicate lifecycle participant: " + id);
		}
		participants.add(new Participant(id, boundaries.clone(), handler));
	}

	void dispatch(LifecycleBoundary boundary, long tick) {
		for (Participant participant : participants) {
			if (participant.boundaries().contains(boundary)) participant.handler().handle(boundary, tick);
		}
	}

	/**
	 * Dispatches to the named participants only, in registration order. Every target must be registered for
	 * {@code boundary}: a misspelled id or a participant that ignores the boundary would otherwise be a
	 * silent no-op that skips a reset.
	 */
	void dispatch(LifecycleBoundary boundary, long tick, Set<String> targets) {
		for (String target : targets) {
			var participant = participants.stream().filter(candidate -> candidate.id().equals(target)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown lifecycle participant: " + target));
			if (!participant.boundaries().contains(boundary)) {
				throw new IllegalArgumentException("Lifecycle participant " + target + " does not handle " + boundary);
			}
		}
		for (Participant participant : participants) {
			if (targets.contains(participant.id())) participant.handler().handle(boundary, tick);
		}
	}

	/** Participant ids and their boundaries, in registration order. */
	Map<String, Set<LifecycleBoundary>> table() {
		var table = new LinkedHashMap<String, Set<LifecycleBoundary>>();
		for (Participant participant : participants) table.put(participant.id(), Set.copyOf(participant.boundaries()));
		return table;
	}
}
