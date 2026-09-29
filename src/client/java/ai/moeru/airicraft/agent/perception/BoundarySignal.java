package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.agent.LifecycleBoundary;
import java.util.Set;

/** Lets a sensor report a boundary it detected (world change, player unavailable) to named lifecycle participants. */
@FunctionalInterface
public interface BoundarySignal {
	void signal(LifecycleBoundary boundary, Set<String> participants);
}
