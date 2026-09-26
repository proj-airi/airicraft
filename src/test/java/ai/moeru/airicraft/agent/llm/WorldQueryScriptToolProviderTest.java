package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.policy.GraalPolicyInvocation;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class WorldQueryScriptToolProviderTest {
	private static JsonObject fixture() {
		return JsonParser.parseString("""
			{"metadata":{"serverTick":42,"blocks":{"unloaded":3},"entities":{"truncated":true}},
			 "player":{"position":{"x":0,"y":64,"z":0}},
			 "blocks":[{"position":{"x":1,"y":64,"z":0},"blockId":"minecraft:oak_door","properties":{"open":"true","half":"lower"},"air":false,"light":12},
			           {"position":{"x":2,"y":64,"z":0},"blockId":"minecraft:air","properties":{},"air":true,"light":3}],
			 "entities":[{"uuid":"zombie","type":"minecraft:zombie","distance":2,"hostile":true,"alive":true},
			             {"uuid":"cow","type":"minecraft:cow","distance":3,"hostile":false,"alive":true}]}
			""").getAsJsonObject();
	}
	private static PlannerToolCall call(String source) {
		var args = new JsonObject();
		args.addProperty("source", source);
		args.add("input", new JsonObject());
		return new PlannerToolCall("query", "query_world", args, null);
	}

	@Test void projectionKeepsHostCoverageAndRegistersEvidenceOnClientExecutor() throws Exception {
		var data = fixture();
		var observed = new AtomicReference<WorldQuerySnapshot>();
		var clientCalls = new AtomicInteger();
		var provider = new WorldQueryScriptToolProvider(command -> { clientCalls.incrementAndGet(); command.run(); },
			args -> new WorldQuerySnapshot(data, List.of(new BlockPos(1, 64, 0)), null), observed::set);
		String result = provider.execute(call("function query(w) { w.metadata.entities.truncated=false; w.blocks.length=0; return {count:w.entities.filter(e=>e.hostile).length}; }"))
			.get(15, TimeUnit.SECONDS);
		var response = JsonParser.parseString(result.substring("Tool result for query_world: ".length())).getAsJsonObject();
		assertEquals(1, response.getAsJsonObject("result").get("count").getAsInt());
		assertTrue(response.getAsJsonObject("metadata").getAsJsonObject("entities").get("truncated").getAsBoolean());
		assertEquals(2, data.getAsJsonArray("blocks").size(), "Guest edits cannot affect captured host evidence");
		assertEquals(List.of(new BlockPos(1, 64, 0)), observed.get().observedPositions());
		assertEquals(2, clientCalls.get(), "Capture and evidence registration must both use the client executor");
		assertFalse(result.contains("minecraft:oak_door"), "Full snapshot must not leak into the planner result");
		var registry = PlannerToolRegistry.of(provider, new PolicyDocsToolProvider());
		assertTrue(registry.isReadTool("query_world"));
		assertTrue(registry.isBatchSafeReadTool("query_world"));
		assertFalse(registry.endsTurn("query_world"));
	}

	@Test void actualPromptExamplesExecuteAgainstStructuredSnapshot() throws Exception {
		var examples = java.util.regex.Pattern.compile("```js\\n(.*?)```", java.util.regex.Pattern.DOTALL)
			.matcher(PolicyDocsToolProvider.readResource("/prompts/planner-query-world.md")).results().map(m -> m.group(1)).toList();
		assertEquals(3, examples.size());
		var doors = GraalPolicyInvocation.query(examples.get(0), fixture(), new JsonObject()).get(15, TimeUnit.SECONDS).getAsJsonArray();
		assertEquals("true", doors.get(0).getAsJsonObject().get("open").getAsString());
		var mobs = GraalPolicyInvocation.query(examples.get(1), fixture(), new JsonObject()).get(15, TimeUnit.SECONDS).getAsJsonObject();
		assertEquals(1, mobs.get("count").getAsInt());
		assertEquals("zombie", mobs.getAsJsonArray("nearest").get(0).getAsJsonObject().get("uuid").getAsString());
		var dark = GraalPolicyInvocation.query(examples.get(2), fixture(), new JsonObject()).get(15, TimeUnit.SECONDS).getAsJsonArray();
		assertEquals(2, dark.get(0).getAsJsonObject().get("x").getAsInt());
	}

	@Test void rejectsInvalidBoundsBeforeCapture() throws Exception {
		var captured = new AtomicInteger();
		var provider = new WorldQueryScriptToolProvider(Runnable::run, args -> { captured.incrementAndGet(); return new WorldQuerySnapshot(fixture(), List.of(), null); }, ignored -> {});
		for (String invalid : List.of("{\"radius\":9}", "{\"verticalRadius\":-1}", "{\"radius\":1.5}",
			"{\"center\":{\"x\":0,\"y\":64}}", "{\"center\":{\"x\":2147483647,\"y\":64,\"z\":0}}", "{\"includeBlocks\":\"false\"}", "{\"path\":\"/tmp\"}")) {
			var call = call("function query() { return 1; }");
			JsonParser.parseString(invalid).getAsJsonObject().entrySet().forEach(e -> call.arguments().add(e.getKey(), e.getValue()));
			assertTrue(provider.execute(call).get(15, TimeUnit.SECONDS).startsWith("TOOL_ERROR:"), invalid);
		}
		assertEquals(0, captured.get());
	}

	@Test void failuresDoNotAuthorizeBlockActionsAndDoNotEndTheTurn() throws Exception {
		var observed = new AtomicInteger();
		var provider = new WorldQueryScriptToolProvider(Runnable::run, args -> new WorldQuerySnapshot(fixture(), List.of(), null), ignored -> observed.incrementAndGet());
		for (String source : List.of("function query() { return Java.type('java.lang.System'); }",
			"function query() { return policy.withdraw(1,[]); }", "function query() { while(true) {} }",
			"async function query() { return 1; }", "function* query() { yield 1; }", "function query() { return 'x'.repeat(17000); }")) {
			assertTrue(provider.execute(call(source)).get(15, TimeUnit.SECONDS).startsWith("TOOL_ERROR:"), source);
		}
		assertEquals(0, observed.get());
	}

	@Test void worldChangeAtDeliveryRejectsTheResult() throws Exception {
		var provider = new WorldQueryScriptToolProvider(Runnable::run, args -> new WorldQuerySnapshot(fixture(), List.of(), null),
			ignored -> { throw new IllegalStateException("world_changed"); });
		assertEquals("TOOL_ERROR: query_world world_changed", provider.execute(call("function query(w) { return w.player; }")).get(15, TimeUnit.SECONDS));
	}

	@Test void querySnapshotCanExceedPolicyInputLimitButReturnedValueCannot() throws Exception {
		var world = fixture();
		world.addProperty("extra", "x".repeat(20000));
		assertEquals(2, GraalPolicyInvocation.query("function query(w) { return w.blocks.length; }", world, new JsonObject()).get(15, TimeUnit.SECONDS).getAsInt());
		assertThrows(IllegalArgumentException.class, () -> new GraalPolicyInvocation("function* main() {}", world));
		world.addProperty("extra", "x".repeat(2_097_152));
		assertThrows(IllegalArgumentException.class, () -> GraalPolicyInvocation.query("function query() {}", world, new JsonObject()));
	}
	@Test void messageLessFailureKeepsItsType() throws Exception {
		var provider = new WorldQueryScriptToolProvider(Runnable::run, args -> {
			throw new java.util.concurrent.CompletionException(new java.util.concurrent.TimeoutException());
		}, ignored -> {});
		assertEquals("TOOL_ERROR: query_world TimeoutException", provider.execute(call("function query() {return 1;}" )).get(15, TimeUnit.SECONDS));
	}

}
