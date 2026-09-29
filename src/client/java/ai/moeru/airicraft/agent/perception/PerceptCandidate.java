package ai.moeru.airicraft.agent.perception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Something the player could actually perceive, with its evidence, offered once to the salience rules. The record
 * is plain data: the rules never see live world objects, so they cannot notice what the player could not.
 *
 * @param id stable per thing, for example {@code block:minecraft:diamond_ore@3,40,-7} or {@code item:<uuid>}
 * @param kind {@code block}, {@code entity}, {@code entity_lost}, {@code item} or {@code environment}
 */
public record PerceptCandidate(String id, String kind, Map<String, Object> fields) {
	public PerceptCandidate {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(kind, "kind");
		fields = fields == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(fields));
	}

	/** The JSON shape handed to a salience step: {@code {id, kind, ...fields}}. */
	public Map<String, Object> toInput() {
		var input = new LinkedHashMap<String, Object>();
		input.put("id", id);
		input.put("kind", kind);
		input.putAll(fields);
		return input;
	}
}
