package ai.moeru.airicraft.agent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
		participants.add(new Participant(id, boundaries.clone(), handler));
	}

	void dispatch(LifecycleBoundary boundary, long tick) {
		for (Participant participant : participants) {
			if (participant.boundaries().contains(boundary)) participant.handler().handle(boundary, tick);
		}
	}

	void dispatch(LifecycleBoundary boundary, long tick, Set<String> targets) {
		for (Participant participant : participants) {
			if (targets.contains(participant.id()) && participant.boundaries().contains(boundary)) {
				participant.handler().handle(boundary, tick);
			}
		}
	}
}
