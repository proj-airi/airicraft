package ai.moeru.airicraft.agent.character;

import ai.moeru.airicraft.agent.dialogue.DialogueCore;
import ai.moeru.airicraft.agent.dialogue.DialogueMessages;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Reads Character Card V3 (or V2) JSON, as exported by AIRI, into the fields Airicraft uses. */
public final class CharacterCards {
	public static final String BUILT_IN_RESOURCE = "/config/airicraft/character.json.example";
	static final int MAX_NAME_LENGTH = 64;
	static final int MAX_TEXT_LENGTH = 4000;
	/** Every card field enters both planner prompts, so the whole character stays small. */
	static final int MAX_TOTAL_TEXT_LENGTH = 8000;
	static final int MAX_LIST_ITEMS = 12;
	static final int MAX_ITEM_LENGTH = 200;
	static final int MAX_MESSAGE_LENGTH = 200;
	private static final Set<String> SPECS = Set.of("chara_card_v3", "chara_card_v2");
	private static final Set<String> EXTENSION_KEYS = Set.of("interests", "dislikes", "catchphrases", "chattiness", "mischief", "messages");
	private static volatile CharacterCard builtIn;

	private CharacterCards() {
	}

	public static CharacterCard builtInDefault() {
		CharacterCard card = builtIn;
		if (card != null) return card;
		try (InputStream stream = CharacterCards.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
			if (stream == null) throw new IllegalStateException("Missing embedded character card: " + BUILT_IN_RESOURCE);
			card = parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8), "built-in default");
		}
		catch (IOException exception) {
			throw new IllegalStateException("Failed to read embedded character card: " + BUILT_IN_RESOURCE, exception);
		}
		builtIn = card;
		return card;
	}

	/** Other card fields (greetings, lorebooks, tags, AIRI's own extension) are accepted and ignored. */
	public static CharacterCard parse(String json, String source) {
		JsonElement root;
		try {
			root = JsonParser.parseString(json);
		}
		catch (JsonParseException exception) {
			throw invalid("not valid JSON (" + exception.getMessage() + ")");
		}
		if (root == null || !root.isJsonObject()) throw invalid("the card must be a JSON object");
		JsonObject card = root.getAsJsonObject();
		String spec = string(card, "spec", "spec");
		if (!SPECS.contains(spec)) throw invalid("spec must be chara_card_v3 or chara_card_v2");
		JsonObject data = object(card, "data", "data");
		if (data == null) throw invalid("data is required");

		String name = string(data, "name", "data.name").strip();
		if (name.length() > MAX_NAME_LENGTH) throw invalid("data.name is longer than " + MAX_NAME_LENGTH + " characters");
		if (name.contains("{{")) throw invalid("data.name cannot contain {{macros}}");
		String description = text(data, "description");
		String personality = text(data, "personality");
		String scenario = text(data, "scenario");
		String systemPrompt = text(data, "system_prompt");
		String messageExamples = text(data, "mes_example");
		int total = description.length() + personality.length() + scenario.length() + systemPrompt.length() + messageExamples.length();
		if (total > MAX_TOTAL_TEXT_LENGTH)
			throw invalid("description, personality, scenario, system_prompt and mes_example together exceed " + MAX_TOTAL_TEXT_LENGTH + " characters");

		JsonObject extensions = object(data, "extensions", "data.extensions");
		JsonObject airicraft = extensions == null ? null : object(extensions, "airicraft", "data.extensions.airicraft");
		if (airicraft == null) {
			return new CharacterCard(name, description, personality, scenario, systemPrompt, messageExamples,
				List.of(), List.of(), List.of(), null, null, Map.of(), source);
		}
		for (String key : airicraft.keySet()) {
			if (!EXTENSION_KEYS.contains(key)) throw invalid("unknown key data.extensions.airicraft." + key);
		}
		return new CharacterCard(name, description, personality, scenario, systemPrompt, messageExamples,
			list(airicraft, "interests"), list(airicraft, "dislikes"), list(airicraft, "catchphrases"),
			choice(airicraft, "chattiness", CharacterCard.Chattiness.class), choice(airicraft, "mischief", CharacterCard.Mischief.class),
			messages(airicraft), source);
	}

	private static String text(JsonObject data, String key) {
		String value = string(data, key, "data." + key).strip();
		if (value.length() > MAX_TEXT_LENGTH) throw invalid("data." + key + " is longer than " + MAX_TEXT_LENGTH + " characters");
		return value;
	}

	private static List<String> list(JsonObject airicraft, String key) {
		String path = "data.extensions.airicraft." + key;
		JsonElement element = airicraft.get(key);
		if (element == null || element.isJsonNull()) return List.of();
		if (!element.isJsonArray()) throw invalid(path + " must be a list of strings");
		if (element.getAsJsonArray().size() > MAX_LIST_ITEMS) throw invalid(path + " has more than " + MAX_LIST_ITEMS + " entries");
		List<String> values = new ArrayList<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw invalid(path + " must be a list of strings");
			String value = item.getAsString().strip();
			if (value.isEmpty()) throw invalid(path + " cannot contain empty entries");
			if (value.length() > MAX_ITEM_LENGTH) throw invalid(path + " entries must be at most " + MAX_ITEM_LENGTH + " characters");
			values.add(value);
		}
		return List.copyOf(values);
	}

	private static <E extends Enum<E>> E choice(JsonObject airicraft, String key, Class<E> type) {
		String path = "data.extensions.airicraft." + key;
		String value = string(airicraft, key, path);
		if (value.isBlank()) return null;
		try {
			return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException exception) {
			List<String> allowed = new ArrayList<>();
			for (E constant : type.getEnumConstants()) allowed.add(constant.name().toLowerCase(Locale.ROOT));
			throw invalid(path + " must be one of " + String.join(", ", allowed));
		}
	}

	private static Map<String, String> messages(JsonObject airicraft) {
		JsonObject messages = object(airicraft, "messages", "data.extensions.airicraft.messages");
		if (messages == null) return Map.of();
		Map<String, String> values = new LinkedHashMap<>();
		for (String key : messages.keySet()) {
			String path = "data.extensions.airicraft.messages." + key;
			if (!DialogueMessages.KEYS.contains(key))
				throw invalid("unknown message " + path + "; expected one of " + String.join(", ", DialogueMessages.KEYS));
			String value = string(messages, key, path).strip();
			if (value.isEmpty()) throw invalid(path + " cannot be empty");
			if (value.length() > MAX_MESSAGE_LENGTH) throw invalid(path + " must be at most " + MAX_MESSAGE_LENGTH + " characters");
			if (value.contains("\n") || value.contains("\r")) throw invalid(path + " must be a single chat line");
			values.put(key, value);
		}
		// Operators and testers recover a stuck planner with this command; a custom line must still name it.
		String degraded = values.get("degraded");
		if (degraded != null && !degraded.toLowerCase(Locale.ROOT).contains(DialogueCore.RESET_COMMAND))
			throw invalid("data.extensions.airicraft.messages.degraded must tell players to send '" + DialogueCore.RESET_COMMAND + "'");
		return values;
	}

	private static String string(JsonObject object, String key, String path) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) return "";
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw invalid(path + " must be a string");
		return element.getAsString();
	}

	private static JsonObject object(JsonObject object, String key, String path) {
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) return null;
		if (!element.isJsonObject()) throw invalid(path + " must be an object");
		return element.getAsJsonObject();
	}

	private static IllegalArgumentException invalid(String detail) {
		return new IllegalArgumentException("Invalid character card: " + detail);
	}
}
