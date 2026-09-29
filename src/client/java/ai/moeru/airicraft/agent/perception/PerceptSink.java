package ai.moeru.airicraft.agent.perception;

import java.util.Map;

/** Where a sensor's output goes: fact-shaped percepts straight to the event bus, noticing candidates to salience. */
public interface PerceptSink {
	void publish(String type, Map<String, Object> payload);

	void candidate(PerceptCandidate candidate);
}
