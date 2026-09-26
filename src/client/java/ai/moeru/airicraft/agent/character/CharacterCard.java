package ai.moeru.airicraft.agent.character;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The companion's character: AIRI's Character Card V3 fields plus the {@code extensions.airicraft} block.
 * An empty name means the companion goes by its in-game player name.
 */
public record CharacterCard(
	String name,
	String description,
	String personality,
	String scenario,
	String systemPrompt,
	String messageExamples,
	List<String> interests,
	List<String> dislikes,
	List<String> catchphrases,
	Chattiness chattiness,
	Mischief mischief,
	Map<String, String> messages,
	String source
) {
	public enum Chattiness { QUIET, NORMAL, CHATTY }

	/** How much the character plays around on its own time; never licence to harm players or their things. */
	public enum Mischief { NONE, MILD, PLAYFUL }

	public CharacterCard {
		name = name == null ? "" : name.strip();
		description = text(description);
		personality = text(personality);
		scenario = text(scenario);
		systemPrompt = text(systemPrompt);
		messageExamples = text(messageExamples);
		interests = interests == null ? List.of() : List.copyOf(interests);
		dislikes = dislikes == null ? List.of() : List.copyOf(dislikes);
		catchphrases = catchphrases == null ? List.of() : List.copyOf(catchphrases);
		chattiness = chattiness == null ? Chattiness.NORMAL : chattiness;
		mischief = mischief == null ? Mischief.MILD : mischief;
		messages = messages == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(messages));
		source = source == null || source.isBlank() ? "unknown" : source;
	}

	/** The built-in generic Minecraft player shipped as {@code character.json.example}. */
	public static CharacterCard defaults() {
		return CharacterCards.builtInDefault();
	}

	public String displayName(String inGameName) {
		if (!name.isEmpty()) return name;
		return inGameName == null || inGameName.isBlank() ? "the companion" : inGameName.strip();
	}

	private static String text(String value) {
		return value == null ? "" : value.strip();
	}
}
