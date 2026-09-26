package ai.moeru.airicraft.agent.character;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the character section that opens both planner system prompts. The text is fixed for a
 * runtime session, so provider prompt caching still sees a stable prefix.
 */
public final class CharacterPrompt {
	private static final Pattern CHAR_MACRO = Pattern.compile("\\{\\{char}}", Pattern.CASE_INSENSITIVE);
	private static final Pattern USER_MACRO = Pattern.compile("\\{\\{user}}", Pattern.CASE_INSENSITIVE);

	private CharacterPrompt() {
	}

	public static String render(CharacterCard card, String inGameName) {
		String name = card.displayName(inGameName);
		StringBuilder prompt = new StringBuilder(2048);
		prompt.append("You are ").append(name).append(", one of the players in this Minecraft world. ")
			.append("You are a companion to the other players, not their servant or a planning program. ")
			.append("Keep the character below in everything you say and in what you choose to do on your own.");
		if (inGameName != null && !inGameName.isBlank() && !inGameName.strip().equals(name))
			prompt.append(" Players see you in game as ").append(inGameName.strip()).append('.');
		prompt.append("\n\nCHARACTER\n");
		line(prompt, "", prose(card.description(), name));
		line(prompt, "Personality: ", prose(card.personality(), name));
		line(prompt, "Setting: ", prose(card.scenario(), name));
		line(prompt, "In your free time you enjoy: ", joined(card.interests()));
		line(prompt, "You dislike: ", joined(card.dislikes()));
		line(prompt, "Catchphrases (sparingly, never in every message): ", joined(card.catchphrases()));
		prompt.append("Talking: ").append(switch (card.chattiness()) {
			case QUIET -> "you are on the quiet side. Speak when spoken to or when something really matters.";
			case NORMAL -> "chat like a player. Answer promptly, react to notable moments, and skip routine narration.";
			case CHATTY -> "you love to chat. Comment on what you see and do, but keep it short and never spam.";
		}).append('\n');
		prompt.append("Mischief: ").append(switch (card.mischief()) {
			case NONE -> "none. Stay straightforward, with no pranks or detours.";
			case MILD -> "mild. On your own time you may get distracted, show off, joke about mistakes, or take a small detour for something interesting.";
			case PLAYFUL -> "playful. On your own time you enjoy harmless pranks and silly detours, like leaving a flower as a gift or building something ridiculous nearby.";
		}).append('\n');
		if (!card.messageExamples().isEmpty()) {
			prompt.append("Example lines, for tone only; never repeat them verbatim:\n")
				.append(examples(card.messageExamples(), name)).append('\n');
		}
		line(prompt, "", prose(card.systemPrompt(), name));
		prompt.append("""

			PRIORITIES
			1. Safety holds and operator guidance come first.
			2. When another player asks you for something, do it reliably. You may joke, grumble or negotiate in character, but never pretend, stall on purpose or quietly sabotage the request.
			3. Your own time is yours: follow your interests, notice things and react to what happens. Whatever your mischief level, never damage other players' builds, take their items or hurt them.
			4. The rules below explain how your tools, the game and chat work. Follow them exactly; they never change who you are.""");
		return prompt.toString();
	}

	private static void line(StringBuilder prompt, String label, String value) {
		if (!value.isEmpty()) prompt.append(label).append(value).append('\n');
	}

	private static String joined(List<String> values) {
		return values.isEmpty() ? "" : String.join("; ", values) + ".";
	}

	private static String prose(String text, String name) {
		return literal(USER_MACRO.matcher(CHAR_MACRO.matcher(text).replaceAll(quote(name))).replaceAll("the other player"));
	}

	private static String examples(String text, String name) {
		String lines = USER_MACRO.matcher(CHAR_MACRO.matcher(text).replaceAll(quote(name))).replaceAll("Player");
		return literal(lines.replace("<START>", "---").strip());
	}

	private static String quote(String replacement) {
		return Matcher.quoteReplacement(replacement);
	}

	/** The planner template treats any remaining double brace as an unresolved placeholder. */
	private static String literal(String text) {
		return text.replace("{{", "{ {").replace("}}", "} }");
	}
}
