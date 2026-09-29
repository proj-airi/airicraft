package ai.moeru.airicraft.agent.events;

import java.util.Map;

public interface EventPublisher {
	SemanticEvent publish(long tick, String type, Map<String, Object> payload, String source, EventCause cause);

	default Bound from(String source) {
		return (tick, type, payload, cause) -> publish(tick, type, payload, source, cause);
	}

	interface Bound {
		SemanticEvent publish(long tick, String type, Map<String, Object> payload, EventCause cause);

		default SemanticEvent publish(long tick, String type, Map<String, Object> payload) {
			return publish(tick, type, payload, null);
		}
	}
}
