package ai.moeru.airicraft.airi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AiriSparkCommandTest {
	private static JsonObject json(String text) {
		return JsonParser.parseString(text).getAsJsonObject();
	}

	@Test void aCommandBecomesPlannerGuidance() {
		var command = AiriSparkCommand.parse(json("""
			{"id":"e1","commandId":"c1","interrupt":"soft","priority":"high","intent":"action",
			 "guidance":{"type":"instruction","options":[{"label":"Come back to the user","steps":["Walk to Rin","Wait there"],"rationale":"Rin asked"}]},
			 "contexts":[{"id":"x","contextId":"y","strategy":"append-self","text":"Rin is at the house"}],
			 "destinations":["airicraft"]}
			"""));

		assertEquals("c1", command.commandId());
		assertEquals("""
			Command from AIRI, intent action, priority high, interrupt soft.
			Come back to the user
			- Walk to Rin
			- Wait there
			Why: Rin asked
			Context: Rin is at the house""", command.plannerText());
	}

	@Test void severalOptionsAreNumbered() {
		var command = AiriSparkCommand.parse(json("""
			{"commandId":"c2","interrupt":false,"intent":"proposal",
			 "guidance":{"type":"proposal","options":[{"label":"Mine iron","steps":[]},{"label":"Build a shelter","steps":["Use cobblestone"]}]}}
			"""));

		assertEquals("""
			Command from AIRI, intent proposal.
			Option 1: Mine iron
			Option 2: Build a shelter
			- Use cobblestone""", command.plannerText());
	}

	@Test void aCommandWithoutAnIdIsRejected() {
		assertThrows(IllegalArgumentException.class, () -> AiriSparkCommand.parse(json("{\"intent\":\"action\"}")));
	}

	@Test void longGuidanceIsCut() {
		var data = json("{\"commandId\":\"c3\",\"guidance\":{\"options\":[{\"label\":\"x\",\"steps\":[]}]}}");
		data.getAsJsonObject("guidance").getAsJsonArray("options").get(0).getAsJsonObject().addProperty("label", "a".repeat(5_000));

		String text = AiriSparkCommand.parse(data).plannerText();

		assertEquals(AiriSparkCommand.MAX_TEXT_LENGTH, text.length());
		assertTrue(text.endsWith("..."));
	}
}
