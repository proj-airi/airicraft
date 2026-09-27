package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SayToolProviderTest {
	@Test
	void sayIsOneTextLineThatDefaultsToNow() {
		var provider = new SayToolProvider(PlannerChatSink.NO_OP);
		@SuppressWarnings("unchecked")
		var function = (Map<String, Object>) provider.openAiTools().getFirst().get("function");
		@SuppressWarnings("unchecked")
		var parameters = (Map<String, Object>) function.get("parameters");

		assertEquals("say", function.get("name"));
		assertEquals(List.of("text"), parameters.get("required"));
		assertTrue(SayToolProvider.isSayNow(call("{\"text\":\"hi\"}")));
		assertTrue(SayToolProvider.isSayNow(call("{\"text\":\"hi\",\"when\":\"now\"}")));
		assertFalse(SayToolProvider.isSayNow(call("{\"text\":\"hi\",\"when\":\"queued\"}")));
		assertFalse(SayToolProvider.isSayNow(new PlannerToolCall("other", "mine_blocks", args("{\"text\":\"hi\"}"), null)));
		assertTrue(provider.isReadTool("say"), "Chat changes nothing in the world and needs no work receipt");
	}

	@Test
	void rejectsMalformedArgumentsBeforeSpeaking() {
		var said = new ArrayList<String>();
		var provider = new SayToolProvider(said::add);
		for (String json : List.of("{}", "{\"text\":\" \"}", "{\"text\":4}", "{\"text\":\"hi\",\"when\":\"later\"}",
			"{\"text\":\"hi\",\"narration\":\"old habit\"}")) {
			assertThrows(JsonParseException.class, () -> provider.validateArguments("say", args(json)), json);
		}
		assertThrows(JsonParseException.class, () -> provider.execute(call("{\"text\":\"\"}")));
		assertEquals(List.of(), said);
	}

	@Test
	void executingSaySendsItsTextOnce() {
		var said = new ArrayList<String>();
		var provider = new SayToolProvider(said::add);

		String result = provider.execute(call("{\"text\":\"found iron!!\",\"when\":\"queued\"}")).join();

		assertEquals(List.of("found iron!!"), said);
		assertEquals("Said in chat.", result);
	}

	private static PlannerToolCall call(String json) {
		return new PlannerToolCall("say_call", SayToolProvider.SAY, args(json), null);
	}

	private static JsonObject args(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}
}
