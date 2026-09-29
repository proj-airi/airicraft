package ai.moeru.airicraft.agent.perception;

import ai.moeru.airicraft.rules.RuleEngine;
import ai.moeru.airicraft.rules.RuleModule;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The bundled salience module in the real sandbox, with its state threaded through consecutive steps. */
class SalienceRulesTest {
	private static RuleEngine engine;

	@BeforeAll static void warm() throws Exception {
		engine = RuleEngine.shared(RuleModule.bundledSalience());
		engine.awaitReady(Duration.ofSeconds(60));
	}

	private String state = "{}";

	private JsonObject step(long tick, String context, String... candidates) throws Exception {
		var result = engine.step("{\"tick\":" + tick + ",\"seed\":" + tick + ",\"context\":" + context
			+ ",\"candidates\":[" + String.join(",", candidates) + "]}", state);
		state = result.stateJson();
		var out = new JsonObject();
		out.add("percepts", result.percepts());
		out.add("drops", result.drops());
		return out;
	}

	private static String block(String id, int x, int y, int z, double distance) {
		return "{\"id\":\"block:" + id + "@" + x + "," + y + "," + z + "\",\"kind\":\"block\",\"blockId\":\"" + id + "\",\"x\":" + x
			+ ",\"y\":" + y + ",\"z\":" + z + ",\"distance\":" + distance + ",\"direction\":\"north\",\"exposedFaces\":[\"up\"],"
			+ "\"dimension\":\"minecraft:overworld\"}";
	}

	private static String item(String uuid, String itemId, String extra) {
		return "{\"id\":\"item:" + uuid + "\",\"kind\":\"item\",\"itemId\":\"" + itemId + "\",\"count\":2,\"x\":1,\"y\":64,\"z\":2,"
			+ "\"distance\":3.5,\"attribution\":\"unknown\",\"offered\":false,\"itemEntityUuid\":\"" + uuid + "\"" + extra + "}";
	}

	private static String entity(String uuid, String type, String extra) {
		return "{\"id\":\"entity:" + uuid + "\",\"kind\":\"entity\",\"entityType\":\"" + type + "\",\"uuid\":\"" + uuid
			+ "\",\"name\":\"" + uuid + "\",\"named\":false,\"tamed\":false,\"baby\":false,\"hostile\":false,\"reflexTracked\":false,"
			+ "\"distance\":9,\"direction\":\"east\",\"x\":5,\"y\":64,\"z\":0" + extra + "}";
	}

	private static List<String> reasons(JsonObject out) {
		var reasons = new ArrayList<String>();
		for (var drop : out.getAsJsonArray("drops")) reasons.add(drop.getAsJsonObject().get("reason").getAsString());
		return reasons;
	}

	private static JsonObject only(JsonObject out) {
		JsonArray percepts = out.getAsJsonArray("percepts");
		assertEquals(1, percepts.size(), out.toString());
		return percepts.get(0).getAsJsonObject();
	}

	@Test void adjacentOreFormsOneVeinAndLaterBlocksOfTheSameVeinAreDropped() throws Exception {
		var out = step(10, "{}", block("minecraft:diamond_ore", 3, 40, 7, 7.5), block("minecraft:diamond_ore", 4, 40, 7, 6.2),
			block("minecraft:diamond_ore", 4, 41, 8, 7.9), block("minecraft:chest", 20, 40, 7, 11));
		var percepts = out.getAsJsonArray("percepts");
		assertEquals(2, percepts.size(), out.toString());
		var vein = percepts.get(0).getAsJsonObject();
		assertEquals("perception.block_noticed", vein.get("type").getAsString());
		assertEquals(3, vein.getAsJsonObject("payload").get("count").getAsInt());
		assertEquals(4, vein.getAsJsonObject("payload").getAsJsonObject("nearest").get("x").getAsInt(), "nearest block leads the vein");
		assertEquals(3, vein.getAsJsonArray("candidateIds").size());
		assertEquals("minecraft:chest", percepts.get(1).getAsJsonObject().getAsJsonObject("payload").get("blockId").getAsString());

		assertEquals(List.of("same_vein"), reasons(step(40, "{}", block("minecraft:diamond_ore", 5, 41, 8, 5))));
		assertEquals(1, step(1300, "{}", block("minecraft:diamond_ore", 5, 42, 8, 5)).getAsJsonArray("percepts").size(),
			"a vein is forgotten after 1200 ticks");
	}

	@Test void aRunningMiningJobOwnsItsTargetBlocks() throws Exception {
		var context = "{\"activeJobType\":\"MINE_BLOCKS\",\"activeJobTargets\":[\"minecraft:diamond_ore\"]}";
		var out = step(5, context, block("minecraft:diamond_ore", 0, 30, 0, 4), block("minecraft:emerald_ore", 9, 30, 0, 9));
		assertEquals(List.of("owned_by_active_job"), reasons(out));
		assertEquals("minecraft:emerald_ore", only(out).getAsJsonObject("payload").get("blockId").getAsString());
	}

	@Test void garbageIsContextualAndOffersAndOwnDropsAreNotNoticedTwice() throws Exception {
		assertEquals(List.of("garbage", "offer_percept", "owned_by_active_job"), reasons(step(1, "{}",
			item("a", "minecraft:cobblestone", ""), item("b", "minecraft:bread", ",\"offered\":true"),
			item("c", "minecraft:diamond", ",\"attribution\":\"own_mining_drop\""))));
		assertTrue(only(step(2, "{\"wanted\":[\"minecraft:cobblestone\"]}", item("d", "minecraft:cobblestone", "")))
			.getAsJsonObject("payload").get("wanted").getAsBoolean());
		state = "{}";
		assertEquals(1, step(3, "{\"objective\":\"gather cobblestone for a furnace\"}", item("e", "minecraft:cobblestone", ""))
			.getAsJsonArray("percepts").size(), "an item the goal names is not garbage");
		var bread = only(step(4, "{}", item("f", "minecraft:bread", "")));
		assertEquals("perception.item_noticed", bread.get("type").getAsString());
		assertEquals(List.of("cooldown"), reasons(step(100, "{}", item("g", "minecraft:bread", ""))));
		assertEquals(1, step(205, "{}", item("h", "minecraft:bread", "")).getAsJsonArray("percepts").size());
	}

	@Test void entitiesByCategoryAndLossOnlyForTrackedOnes() throws Exception {
		var out = step(1, "{}", entity("zombie", "minecraft:zombie", ",\"hostile\":true,\"reflexTracked\":true"),
			entity("skeleton", "minecraft:skeleton", ",\"hostile\":true"), entity("cow", "minecraft:cow", ""),
			entity("alex", "minecraft:player", ""), entity("rex", "minecraft:wolf", ",\"tamed\":true"));
		assertEquals(List.of("reflex_owns_threat", "hostile_left_to_reflex", "common_entity"), reasons(out));
		var categories = new ArrayList<String>();
		for (var percept : out.getAsJsonArray("percepts")) categories.add(percept.getAsJsonObject().getAsJsonObject("payload").get("category").getAsString());
		assertEquals(List.of("player", "named_or_tamed"), categories);
		assertEquals("food_source", only(step(2, "{\"wanted\":[\"minecraft:beef\"]}", entity("cow2", "minecraft:cow", "")))
			.getAsJsonObject("payload").get("category").getAsString());

		var lost = "{\"id\":\"lost:alex\",\"kind\":\"entity_lost\",\"entityType\":\"minecraft:player\",\"uuid\":\"alex\",\"name\":\"alex\"}";
		var never = "{\"id\":\"lost:cow\",\"kind\":\"entity_lost\",\"entityType\":\"minecraft:cow\",\"uuid\":\"cow\",\"name\":\"cow\"}";
		var out2 = step(3, "{}", lost, never);
		assertEquals("perception.entity_lost", only(out2).get("type").getAsString());
		assertEquals(List.of("not_tracked"), reasons(out2));
	}

	@Test void environmentChangesAreBudgeted() throws Exception {
		var dusk = "{\"id\":\"env:1\",\"kind\":\"environment\",\"change\":\"dusk\",\"timeOfDay\":12500}";
		var percept = only(step(1, "{}", dusk));
		assertEquals("perception.environment_changed", percept.get("type").getAsString());
		assertEquals("{\"change\":\"dusk\",\"timeOfDay\":12500}", percept.getAsJsonObject("payload").toString());
		assertEquals(List.of("cooldown"), reasons(step(600, "{}", dusk.replace("env:1", "env:2"))));
	}

	@Test void anHourlyCapLimitsItemNotices() throws Exception {
		var candidates = new ArrayList<String>();
		for (int index = 0; index < 41; index++) candidates.add(item("u" + index, "minecraft:item_" + index, ""));
		var out = step(1, "{}", candidates.toArray(String[]::new));
		assertEquals(40, out.getAsJsonArray("percepts").size());
		assertEquals(List.of("hourly_cap"), reasons(out));
	}

	@Test void stepsAreDeterministicAndStateStaysSmall() throws Exception {
		var input = new String[] {block("minecraft:diamond_ore", 1, 20, 1, 6), item("x", "minecraft:bread", ""), entity("p", "minecraft:player", "")};
		var first = step(10, "{}", input);
		String firstState = state;
		state = "{}";
		assertEquals(first, step(10, "{}", input));
		assertEquals(firstState, state);
		for (int tick = 20; tick < 20_000; tick += 50) {
			step(tick, "{}", block("minecraft:diamond_ore", tick % 97, 20, tick % 89, 6), item("i" + tick, "minecraft:bread", ""),
				entity("e" + tick, "minecraft:villager", ""));
		}
		assertTrue(state.length() < 16 * 1024, "state " + state.length() + " bytes");
		assertTrue(JsonParser.parseString(state).isJsonObject());
	}
}
