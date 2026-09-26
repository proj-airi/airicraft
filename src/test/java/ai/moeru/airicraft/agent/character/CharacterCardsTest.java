package ai.moeru.airicraft.agent.character;

import ai.moeru.airicraft.agent.dialogue.DialogueMessages;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CharacterCardsTest {
	@Test
	void builtInCardIsAGenericPlayerThatAdoptsTheInGameName() {
		CharacterCard card = CharacterCard.defaults();

		assertEquals("", card.name());
		assertEquals("built-in default", card.source());
		assertEquals("Airi", card.displayName("Airi"));
		assertEquals(CharacterCard.Chattiness.NORMAL, card.chattiness());
		assertEquals(CharacterCard.Mischief.MILD, card.mischief());
		assertFalse(card.interests().isEmpty());
		assertTrue(card.personality().contains("Minecraft") || card.description().contains("Minecraft"));
		assertEquals(DialogueMessages.KEYS.size(), card.messages().size(), "The built-in card voices every fixed line");
		assertTrue(card.messages().get("degraded").contains("@agent reset"));
		assertDoesNotThrow(() -> DialogueMessages.DEFAULTS.withOverrides(card.messages()));
	}

	@Test
	void readsAiriExportedV3AndV2CardsIgnoringFieldsAiricraftDoesNotUse() {
		String v3 = """
			{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Mochi","description":"A baker.",
			"personality":"Cheerful.","scenario":"A village.","first_mes":"hi","tags":["x"],
			"extensions":{"airi":{"modules":{}},"airicraft":{"interests":["baking bread"],"mischief":"playful","chattiness":"chatty"}}}}""";
		CharacterCard card = CharacterCards.parse(v3, "test");
		assertEquals("Mochi", card.name());
		assertEquals("A baker.", card.description());
		assertEquals(List.of("baking bread"), card.interests());
		assertEquals(CharacterCard.Mischief.PLAYFUL, card.mischief());
		assertEquals(CharacterCard.Chattiness.CHATTY, card.chattiness());
		assertEquals("Mochi", card.displayName("AiricraftTest"));

		CharacterCard v2 = CharacterCards.parse("{\"spec\":\"chara_card_v2\",\"data\":{\"name\":\"Old\"}}", "test");
		assertEquals("Old", v2.name());
		assertEquals(CharacterCard.Mischief.MILD, v2.mischief());
		assertEquals(Map.of(), v2.messages());
	}

	@Test
	void rejectsMalformedCardsWithTheOffendingField() {
		Map<String, String> cases = Map.of(
			"[]", "JSON object",
			"{\"spec\":\"chara_card_v1\",\"data\":{}}", "spec must be",
			"{\"spec\":\"chara_card_v3\"}", "data is required",
			"{\"spec\":\"chara_card_v3\",\"data\":{\"name\":5}}", "data.name must be a string",
			"{\"spec\":\"chara_card_v3\",\"data\":{\"name\":\"{{char}}\"}}", "data.name cannot contain",
			card("{\"mischief\":\"chaotic\"}"), "mischief must be one of none, mild, playful",
			card("{\"interest\":[\"typo\"]}"), "unknown key data.extensions.airicraft.interest",
			card("{\"interests\":\"caves\"}"), "interests must be a list of strings",
			card("{\"messages\":{\"hello\":\"hi\"}}"), "unknown message data.extensions.airicraft.messages.hello",
			card("{\"messages\":{\"reset\":\"two\\nlines\"}}"), "single chat line");
		cases.forEach((json, expected) -> {
			IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CharacterCards.parse(json, "test"), json);
			assertTrue(error.getMessage().contains(expected), error.getMessage() + " should mention " + expected);
		});
	}

	@Test
	void aCustomDegradedLineMustStillNameTheResetCommand() {
		IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
			() -> CharacterCards.parse(card("{\"messages\":{\"degraded\":\"ugh, brain lag\"}}"), "test"));
		assertTrue(error.getMessage().contains("@agent reset"));
		assertEquals("brain lag, send @agent reset", CharacterCards.parse(
			card("{\"messages\":{\"degraded\":\"brain lag, send @agent reset\"}}"), "test").messages().get("degraded"));
	}

	@Test
	void boundsTheTextThatEntersBothPlannerPrompts() {
		String longText = "x".repeat(CharacterCards.MAX_TEXT_LENGTH + 1);
		assertThrows(IllegalArgumentException.class,
			() -> CharacterCards.parse("{\"spec\":\"chara_card_v3\",\"data\":{\"description\":\"" + longText + "\"}}", "test"));
		String half = "y".repeat(CharacterCards.MAX_TEXT_LENGTH);
		IllegalArgumentException total = assertThrows(IllegalArgumentException.class, () -> CharacterCards.parse(
			"{\"spec\":\"chara_card_v3\",\"data\":{\"description\":\"" + half + "\",\"personality\":\"" + half + "\",\"scenario\":\"z\"}}", "test"));
		assertTrue(total.getMessage().contains("together exceed"));
		String many = "[" + "\"a\",".repeat(CharacterCards.MAX_LIST_ITEMS) + "\"a\"]";
		assertThrows(IllegalArgumentException.class, () -> CharacterCards.parse(card("{\"dislikes\":" + many + "}"), "test"));
	}

	private static String card(String airicraftExtension) {
		return "{\"spec\":\"chara_card_v3\",\"data\":{\"name\":\"Test\",\"extensions\":{\"airicraft\":" + airicraftExtension + "}}}";
	}
}
