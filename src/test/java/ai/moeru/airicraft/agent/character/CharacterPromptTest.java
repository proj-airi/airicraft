package ai.moeru.airicraft.agent.character;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CharacterPromptTest {
	@Test
	void builtInCharacterTakesTheInGameNameAndStatesPriorities() {
		String prompt = CharacterPrompt.render(CharacterCard.defaults(), "Airi");

		assertTrue(prompt.startsWith("You are Airi, one of the players in this Minecraft world."));
		assertFalse(prompt.contains("Players see you in game as"), "The in-game name is already the character's name");
		assertTrue(prompt.contains("In your free time you enjoy: exploring caves and ravines;"));
		assertTrue(prompt.contains("Mischief: mild."));
		assertTrue(prompt.contains("never pretend, stall on purpose or quietly sabotage the request"));
		assertTrue(prompt.contains("never damage other players' builds, take their items or hurt them"));
		assertTrue(prompt.contains("Airi: ok that creeper was NOT part of the plan"), "Examples use the character's name");
		assertTrue(prompt.contains("Player: can you get some wood?"));
		assertFalse(prompt.contains("<START>"));
		assertFalse(prompt.contains("{{"));
	}

	@Test
	void namedCardsKeepTheirNameAndMentionTheAccountName() {
		CharacterCard card = new CharacterCard("Mochi", "{{char}} bakes for {{user}}.", "", "", "", "", List.of(), List.of(),
			List.of("mmm, bread"), CharacterCard.Chattiness.QUIET, CharacterCard.Mischief.NONE, Map.of(), "test");

		String prompt = CharacterPrompt.render(card, "AiricraftTest");

		assertTrue(prompt.startsWith("You are Mochi,"));
		assertTrue(prompt.contains("Players see you in game as AiricraftTest."));
		assertTrue(prompt.contains("Mochi bakes for the other player."));
		assertTrue(prompt.contains("Catchphrases (sparingly, never in every message): mmm, bread."));
		assertTrue(prompt.contains("you are on the quiet side"));
		assertTrue(prompt.contains("Mischief: none."));
		assertFalse(prompt.contains("In your free time"), "Empty sections are left out");
	}
}
