package ai.moeru.airicraft.agent.memory;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlaceMemoryToolProviderTest {
	@TempDir Path directory;

	@Test
	void capturesCurrentPositionAndRecallsItAfterMovementDimensionChangeAndPlannerRecreation() {
		var context = new AtomicReference<>(new PlaceMemoryToolProvider.Context(directory, "minecraft:overworld", 10, 70, -5));
		var provider = new PlaceMemoryToolProvider(context::get, Runnable::run);
		assertTrue(call(provider, "remember_place", "{\"name\":\"entrance\",\"position\":\"current\",\"note\":\"return here\"}").contains("\"y\":70"));
		context.set(new PlaceMemoryToolProvider.Context(directory, "minecraft:the_nether", 900, 25, 20));
		provider = new PlaceMemoryToolProvider(context::get, Runnable::run);
		String recalled = call(provider, "recall_place", "{\"name\":\"entrance\"}");
		assertTrue(recalled.contains("currentDimension=minecraft:the_nether"));
		assertTrue(recalled.contains("\"dimension\":\"minecraft:overworld\""));
		assertTrue(recalled.contains("\"x\":10,\"y\":70,\"z\":-5"));
		assertTrue(recalled.contains("return here"));
	}

	@Test
	void explicitCoordinatesAndDeleteUseTheSamePersistentMemory() {
		var provider = provider();
		assertTrue(call(provider, "remember_place", """
			{"name":"home","position":{"x":284,"y":65,"z":-140,"dimension":"minecraft:overworld"}}
			""").contains("\"x\":284,\"y\":65,\"z\":-140"));
		assertTrue(call(provider(), "list_places", "{}").contains("home"));
		assertTrue(call(provider(), "forget_place", "{\"name\":\"home\"}").contains("\"deleted\":true"));
		assertTrue(call(provider(), "recall_place", "{\"name\":\"home\"}").contains("place_not_found"));
	}

	@Test
	void preservedAreaIsPublishedOnlyAfterSuccessfulWritesAndRemovedOnForget() {
		var changes = new java.util.concurrent.atomic.AtomicInteger();
		var provider = new PlaceMemoryToolProvider(() -> new PlaceMemoryToolProvider.Context(directory, "minecraft:overworld", 0, 64, 0),
			Runnable::run, changes::incrementAndGet);
		String result = call(provider, "remember_place", """
			{"name":"home","preserveArea":{"x1":254,"y1":62,"z1":478,"x2":258,"y2":65,"z2":482}}
			""");
		assertTrue(result.contains("preserveArea"));
		assertEquals(1, changes.get());
		assertTrue(call(provider(), "recall_place", "{\"name\":\"home\"}").contains("\"y1\":62"));
		for (String area : List.of("null", "{}", "{\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":-1,\"y2\":1,\"z2\":1}",
			"{\"x1\":0.5,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1}")) {
			assertTrue(call(provider, "remember_place", "{\"name\":\"home\",\"preserveArea\":" + area + "}").contains("TOOL_ERROR"));
		}
		assertEquals(1, changes.get());
		call(provider, "forget_place", "{\"name\":\"home\"}");
		assertEquals(2, changes.get());
		call(provider, "forget_place", "{\"name\":\"home\"}");
		assertEquals(2, changes.get());
	}

	@Test
	void invalidCoordinatesCannotSilentlySaveAnotherPosition() {
		for (String arguments : List.of(
			"{\"name\":\"home\",\"position\":{\"x\":1,\"y\":2}}",
			"{\"name\":\"home\",\"position\":{\"x\":1.5,\"y\":2,\"z\":3}}",
			"{\"name\":\"home\",\"position\":{\"x\":4294967296,\"y\":2,\"z\":3}}",
			"{\"name\":\"home\",\"position\":{\"x\":1,\"y\":2,\"z\":3,\"dimension\":\"bad dimension\"}}",
			"{\"name\":\"home\",\"x\":123}",
			"{\"name\":\"\"}", "{\"name\":\"home\",\"position\":null}")) {
			assertThrows(JsonParseException.class, () -> provider().validateArguments("remember_place", JsonParser.parseString(arguments).getAsJsonObject()));
		}
		assertTrue(call(provider(), "list_places", "{}").contains("result=[]"));
	}

	@Test
	void memoryIsDiscoverableWithoutMapIntegrationAndWritesCannotEnterReadBatches() {
		var registry = PlannerToolRegistry.of(provider());
		for (String name : List.of("remember_place", "recall_place", "list_places", "forget_place")) {
			assertTrue(registry.isActiveTool(name), name);
		}
		assertTrue(registry.isBatchSafeReadTool("recall_place"));
		assertTrue(registry.isBatchSafeReadTool("list_places"));
		assertFalse(registry.isReadTool("remember_place"));
		assertFalse(registry.isBatchSafeReadTool("forget_place"));
		assertTrue(registry.promptInstructions().contains("not fresh evidence"));
	}

	@Test
	void toolsAcceptIdsAndRejectAmbiguousSelectors() {
		var provider = provider();
		call(provider, "remember_place", "{\"name\":\"home\"}");
		String result = call(provider, "list_places", "{}");
		String id = JsonParser.parseString(result.substring(result.indexOf("result=") + 7)).getAsJsonArray().get(0).getAsJsonObject().get("id").getAsString();
		assertTrue(call(provider, "remember_place", "{\"id\":\"" + id + "\",\"name\":\"base\"}").contains("base"));
		assertTrue(call(provider, "recall_place", "{\"id\":\"" + id + "\"}").contains("base"));
		assertTrue(call(provider, "recall_place", "{\"id\":\"" + id + "\",\"name\":\"base\"}").contains("TOOL_ERROR"));
		assertTrue(call(provider, "forget_place", "{\"id\":\"" + id + "\"}").contains("\"deleted\":true"));
	}

	@Test
	void missingWorldReturnsAnErrorRatherThanCreatingGlobalMemory() {
		var provider = new PlaceMemoryToolProvider(() -> { throw new IllegalStateException("world_not_loaded"); }, Runnable::run);
		assertTrue(call(provider, "remember_place", "{\"name\":\"home\"}").contains("TOOL_ERROR: remember_place world_not_loaded"));
	}

	private PlaceMemoryToolProvider provider() {
		return new PlaceMemoryToolProvider(() -> new PlaceMemoryToolProvider.Context(directory, "minecraft:overworld", 0, 64, 0), Runnable::run);
	}

	private static String call(PlaceMemoryToolProvider provider, String name, String args) {
		return provider.execute(new PlannerToolCall("test-place", name, JsonParser.parseString(args).getAsJsonObject(), null, null)).join();
	}
}
