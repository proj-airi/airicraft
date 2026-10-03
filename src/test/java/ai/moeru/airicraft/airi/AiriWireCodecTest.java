package ai.moeru.airicraft.airi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiriWireCodecTest {
	private static final AiriModuleIdentity IDENTITY = new AiriModuleIdentity("airicraft", "airicraft-1", "airicraft");

	@Test
	void encodesPlainJsonWithAPluginSourceAndAnEventId() {
		JsonObject data = new JsonObject();
		data.addProperty("token", "secret");

		JsonObject envelope = JsonParser.parseString(AiriWireCodec.encode("module:authenticate", data, IDENTITY, "event-1")).getAsJsonObject();

		assertEquals("module:authenticate", envelope.get("type").getAsString());
		assertEquals("secret", envelope.getAsJsonObject("data").get("token").getAsString());
		JsonObject source = envelope.getAsJsonObject("metadata").getAsJsonObject("source");
		assertEquals("plugin", source.get("kind").getAsString());
		assertEquals("airicraft-1", source.get("id").getAsString());
		assertEquals("airicraft", source.getAsJsonObject("extension").get("id").getAsString());
		assertEquals("airicraft", source.getAsJsonObject("plugin").get("id").getAsString());
		assertEquals("event-1", envelope.getAsJsonObject("metadata").getAsJsonObject("event").get("id").getAsString());
	}

	@Test
	void decodesTheSuperJsonWrapperThatTheServerWrites() {
		String text = """
			{"json":{"type":"module:authenticated","data":{"authenticated":true},"metadata":{"event":{"id":"e"}}}}""";

		AiriEvent event = AiriWireCodec.decode(text);

		assertEquals("module:authenticated", event.type());
		assertTrue(event.data().get("authenticated").getAsBoolean());
		assertEquals("e", event.metadata().getAsJsonObject("event").get("id").getAsString());
	}

	@Test
	void decodesPlainJson() {
		AiriEvent event = AiriWireCodec.decode("{\"type\":\"spark:command\",\"data\":{\"intent\":\"action\"}}");

		assertEquals("spark:command", event.type());
		assertEquals("action", event.data().get("intent").getAsString());
		assertEquals(0, event.metadata().size());
	}

	@Test
	void rejectsTextThatIsNotAnEvent() {
		assertThrows(IllegalArgumentException.class, () -> AiriWireCodec.decode("ping"));
		assertThrows(IllegalArgumentException.class, () -> AiriWireCodec.decode("[]"));
		assertThrows(IllegalArgumentException.class, () -> AiriWireCodec.decode("{\"data\":{}}"));
		assertThrows(IllegalArgumentException.class, () -> AiriWireCodec.decode("{\"type\":\"error\",\"data\":[]}"));
	}
}
