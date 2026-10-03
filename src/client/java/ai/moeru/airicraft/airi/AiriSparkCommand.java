package ai.moeru.airicraft.airi;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One {@code spark:command} from AIRI, reduced to the fields that the planner reads.
 * The planner gets the command as one block of text. It decides the actions itself.
 */
public record AiriSparkCommand(String commandId, String intent, String priority, String interrupt, List<Option> options,
	List<String> contexts) {
	static final int MAX_TEXT_LENGTH = 1_200;
	/** The planner prompt does not know AIRI, so each command says how to treat it. */
	static final String INSTRUCTION = "AIRI is your own self outside the game. Follow this above player requests and below "
		+ "safety needs. Act with tools. Do not answer AIRI in game chat.";

	public record Option(String label, List<String> steps, String rationale) {}

	public AiriSparkCommand {
		options = List.copyOf(options);
		contexts = List.copyOf(contexts);
	}

	/** Reads the event data. Throws {@link IllegalArgumentException} when {@code commandId} is missing. */
	public static AiriSparkCommand parse(JsonObject data) {
		String commandId = string(data, "commandId");
		if (commandId.isBlank()) {
			throw new IllegalArgumentException("spark:command has no commandId");
		}
		List<Option> options = new ArrayList<>();
		if (data.get("guidance") instanceof JsonObject guidance && guidance.get("options") instanceof JsonArray values) {
			for (JsonElement value : values) {
				if (value instanceof JsonObject option) {
					options.add(new Option(string(option, "label"), strings(option.get("steps")), string(option, "rationale")));
				}
			}
		}
		List<String> contexts = new ArrayList<>();
		if (data.get("contexts") instanceof JsonArray values) {
			for (JsonElement value : values) {
				if (value instanceof JsonObject context && !string(context, "text").isBlank()) {
					contexts.add(string(context, "text"));
				}
			}
		}
		JsonElement interrupt = data.get("interrupt");
		String interruptText = interrupt != null && interrupt.isJsonPrimitive() ? interrupt.getAsString() : "false";
		return new AiriSparkCommand(commandId, string(data, "intent"), string(data, "priority"), interruptText, options, contexts);
	}

	/** The guidance text for the planner. It is at most {@value #MAX_TEXT_LENGTH} characters. */
	public String plannerText() {
		StringBuilder text = new StringBuilder();
		text.append("Command from AIRI");
		if (!intent.isBlank()) text.append(", intent ").append(intent);
		if (!priority.isBlank()) text.append(", priority ").append(priority);
		if (!interrupt.isBlank() && !"false".equals(interrupt)) text.append(", interrupt ").append(interrupt);
		text.append('.');
		for (int index = 0; index < options.size(); index++) {
			Option option = options.get(index);
			text.append(options.size() == 1 ? "\n" : "\nOption " + (index + 1) + ": ");
			text.append(option.label().isBlank() ? "(no label)" : option.label());
			for (String step : option.steps()) {
				text.append("\n- ").append(step);
			}
			if (!option.rationale().isBlank()) text.append("\nWhy: ").append(option.rationale());
		}
		for (String context : contexts) {
			text.append("\nContext: ").append(context);
		}
		text.append('\n').append(INSTRUCTION);
		if (text.length() > MAX_TEXT_LENGTH) {
			String tail = "...\n" + INSTRUCTION;
			return text.substring(0, MAX_TEXT_LENGTH - tail.length()) + tail;
		}
		return text.toString();
	}

	private static String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString().strip() : "";
	}

	private static List<String> strings(JsonElement value) {
		List<String> result = new ArrayList<>();
		if (value instanceof JsonArray values) {
			for (JsonElement item : values) {
				if (item.isJsonPrimitive() && !item.getAsString().isBlank()) result.add(item.getAsString().strip());
			}
		}
		return result;
	}
}
