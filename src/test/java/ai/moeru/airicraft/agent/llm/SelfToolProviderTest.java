package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelfToolProviderTest {
	private static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }
	private static PlannerToolCall call(String name, JsonObject args) { return new PlannerToolCall("test",name,args,null); }
	private static String run(SelfToolProvider p,String name,JsonObject args) throws Exception { return p.execute(call(name,args)).get(15,TimeUnit.SECONDS); }
	private static String geometry(JsonObject world, JsonObject input) throws Exception {
		String source=PolicyDocsToolProvider.readResource("/airicraft/policies/survey.js")
			+ "\nfunction query(world,input) {return survey(world,input);}";
		var result=ai.moeru.airicraft.policy.GraalPolicyInvocation.query(source,world,input).get(15,TimeUnit.SECONDS);
		var response=new JsonObject();response.add("result",result);return response.toString();
	}
	private static JsonObject definition() {
		return json("""
			{"name":"custom_count","description":"Count matching blocks","parameters":{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false},
			"source":"function query(w,i) { return w.blocks.filter(b=>b.blockId===i.id).length; }","capture":{"radius":4}}
			""");
	}
	private static WorldQueryScriptToolProvider queries(JsonObject data) {
		return new WorldQueryScriptToolProvider(Runnable::run,args->new WorldQuerySnapshot(data,List.of(),null),snapshot->{});
	}
	@Test void definedToolAppearsAfterFreezeExecutesEditsAndDisappears() throws Exception {
		var p=new SelfToolProvider(queries(json("{\"metadata\":{\"serverTick\":5},\"blocks\":[{\"blockId\":\"minecraft:chest\"}]}")));
		var registry=PlannerToolRegistry.of(p); registry.freezeToolPrefix();
		assertFalse(registry.isActiveTool("custom_count"));
		assertTrue(run(p,"define_tool",definition()).startsWith("Tool defined"));
		assertTrue(registry.isActiveTool("custom_count"));
		assertTrue(registry.activeOpenAiTool("custom_count").isPresent());
		assertTrue(registry.isBatchSafeReadTool("custom_count"));
		assertFalse(registry.isBatchSafeReadTool("define_tool"));
		assertEquals("Tool result for custom_count: {\"metadata\":{\"serverTick\":5},\"result\":1}",run(p,"custom_count",json("{\"id\":\"minecraft:chest\"}")));
		assertTrue(run(p,"custom_count",json("{}" )).startsWith("TOOL_ERROR"));
		var d=definition();d.addProperty("source","function query() {return 7;}");
		run(p,"define_tool",d);
		assertTrue(run(p,"custom_count",json("{\"id\":\"minecraft:chest\"}")).endsWith("\"result\":7}"));
		assertTrue(run(p,"inspect_tool",json("{\"name\":\"custom_count\"}")).contains("return 7"));
		run(p,"remove_tool",json("{\"name\":\"custom_count\"}"));
		assertFalse(registry.isActiveTool("custom_count"));
		assertFalse(registry.isKnownTool("custom_count"));
	}
	@Test void rejectsNativeNamesUnsupportedSchemasAndInvalidCapture() throws Exception {
		var p=new SelfToolProvider(queries(new JsonObject()));
		var d=definition();d.addProperty("name","navigate_to");
		assertTrue(run(p,"define_tool",d).startsWith("TOOL_ERROR"));
		d=definition();d.getAsJsonObject("parameters").addProperty("oneOf",true);
		assertTrue(run(p,"define_tool",d).startsWith("TOOL_ERROR"));
		d=definition();d.getAsJsonObject("capture").addProperty("radius",99);
		assertTrue(run(p,"define_tool",d).startsWith("TOOL_ERROR"));
		assertFalse(p.handles("custom_count"));
	}
	@Test void surveyCoversDistantLandmarkGroupsDoorAndShowsFractionalHeight() throws Exception {
		var world=json("""
			{"metadata":{"bounds":{"min":{"x":-4,"y":62,"z":-1},"max":{"x":4,"y":67,"z":1}}},
			 "player":{"position":{"x":0.5,"y":64,"z":0.5}},"blocks":[],"entities":[]}
			""");
		var blocks=world.getAsJsonArray("blocks");
		for(int x=-4;x<=4;x++) for(int z=-1;z<=1;z++) for(int y=62;y<=67;y++) {
			var b=new JsonObject();b.add("position",json("{\"x\":"+x+",\"y\":"+y+",\"z\":"+z+"}"));
			boolean floor=y<=63; b.addProperty("blockId",floor?"minecraft:stone":"minecraft:air");
			b.addProperty("air",!floor);b.addProperty("fluid",false);b.addProperty("collisionEmpty",!floor);b.add("properties",new JsonObject());
			b.add("collisionBoxes",JsonParser.parseString(floor?"[[0,0,0,1,1,1]]":"[]"));
			if(x==4 && z==0 && (y==64 || y==65)) {
				b.addProperty("blockId","minecraft:oak_door");b.addProperty("air",false);
				b.getAsJsonObject("properties").addProperty("half",y==64?"lower":"upper");
			}
			if(x==1 && z==0 && y==63) b.add("collisionBoxes",JsonParser.parseString("[[0,0,0,1,0.5,1]]"));
			blocks.add(b);
		}
		var p=new SelfToolProvider(queries(world));
		var response=geometry(world,json("{\"focus\":\"door\",\"landmarkLimit\":1}"));
		var result=JsonParser.parseString(response.substring(response.indexOf('{'))).getAsJsonObject().getAsJsonObject("result");
		var landmark=result.getAsJsonArray("landmarks").get(0).getAsJsonObject();
		assertEquals(4,landmark.getAsJsonObject("position").get("x").getAsInt());
		assertEquals(2,landmark.getAsJsonArray("occupied").size());
		assertTrue(result.get("relativeHeight").getAsString().contains("-0.5"));
		assertTrue(result.get("terrain").getAsString().contains("@"));
		assertEquals(3,result.get("terrain").getAsString().split("\n").length);
		assertEquals(9,result.get("relativeHeight").getAsString().split("\n")[0].trim().split("\\s+").length);
	}
	@Test void surveyMarksMissingColumnsAndMultipleFloors() throws Exception {
		var world=json("""
			{"metadata":{"bounds":{"min":{"x":0,"y":60,"z":0},"max":{"x":1,"y":68,"z":0}}},
			 "player":{"position":{"x":3,"y":64,"z":0}},"blocks":[],"entities":[]}
			""");
		for(int y=60;y<=68;y++) {
			boolean floor=y==60 || y==64;
			var b=json("{\"position\":{\"x\":0,\"y\":"+y+",\"z\":0},\"properties\":{},\"fluid\":false}");
			b.addProperty("blockId",floor?"minecraft:stone":"minecraft:air");
			b.addProperty("air",!floor);b.addProperty("collisionEmpty",!floor);
			b.add("collisionBoxes",JsonParser.parseString(floor?"[[0,0,0,1,1,1]]":"[]"));
			world.getAsJsonArray("blocks").add(b);
		}
		var p=new SelfToolProvider(queries(world));
		var response=geometry(world,json("{\"landmarkLimit\":0}"));
		var result=JsonParser.parseString(response.substring(response.indexOf('{'))).getAsJsonObject().getAsJsonObject("result");
		assertEquals("M   ?",result.get("terrain").getAsString().trim());
		assertEquals("+1   ?",result.get("relativeHeight").getAsString().trim());
		assertTrue(run(p,"survey_surroundings",json("{\"landmarkLimit\":25}")).startsWith("TOOL_ERROR"));
		assertTrue(run(p,"survey_surroundings",json("{\"landmarkLimit\":1.5}")).startsWith("TOOL_ERROR"));
	}

	@Test void landmarkAtCaptureCeilingStillHasMapMarker() throws Exception {
		var world=json("""
			{"metadata":{"bounds":{"min":{"x":0,"y":64,"z":0},"max":{"x":0,"y":64,"z":0}}},
			 "player":{"position":{"x":2,"y":64,"z":0}},"entities":[],
			 "blocks":[{"position":{"x":0,"y":64,"z":0},"blockId":"minecraft:chest","properties":{"type":"single"},
			 "air":false,"fluid":false,"collisionEmpty":false,"collisionBoxes":[[0,0,0,1,0.875,1]]}]}
			""");
		String response=geometry(world,json("{}"));
		var result=JsonParser.parseString(response.substring(response.indexOf('{'))).getAsJsonObject().getAsJsonObject("result");
		assertEquals("1?",result.get("terrain").getAsString().trim());
		assertEquals("?",result.get("relativeHeight").getAsString().trim());
	}

	@Test void overlappingLandmarksAndSelfRetainAllMapReferences() throws Exception {
		var world=json("""
			{"metadata":{"bounds":{"min":{"x":0,"y":64,"z":0},"max":{"x":0,"y":65,"z":0}}},
			 "player":{"position":{"x":2,"y":64,"z":0}},"entities":[],
			 "blocks":[{"position":{"x":0,"y":64,"z":0},"blockId":"minecraft:chest","properties":{},
			 "air":false,"fluid":false,"collisionEmpty":false,"collisionBoxes":[[0,0,0,1,0.875,1]]},
			 {"position":{"x":0,"y":65,"z":0},"blockId":"minecraft:furnace","properties":{},
			 "air":false,"fluid":false,"collisionEmpty":false,"collisionBoxes":[[0,0,0,1,1,1]]}]}
			""");
		for (String expected:List.of("L", "@")) {
			if(expected.equals("@")) world.getAsJsonObject("player").getAsJsonObject("position").addProperty("x",0);
			String response=geometry(world,json("{}"));
			var result=JsonParser.parseString(response.substring(response.indexOf('{'))).getAsJsonObject().getAsJsonObject("result");
			assertEquals(expected+"?",result.get("terrain").getAsString().trim());
			assertEquals(2,result.getAsJsonArray("landmarks").size());
			for(var l:result.getAsJsonArray("landmarks")) {
				var map=l.getAsJsonObject().getAsJsonObject("map");
				assertEquals(expected,map.get("marker").getAsString());
				assertEquals(0,map.get("row").getAsInt());
				assertEquals(0,map.get("column").getAsInt());
			}
		}
	}

	@Test void surveyIsPlainTextWithHostCoverageAndRealMapRows() throws Exception {
		var world=json("""
			{"metadata":{"dimension":"minecraft:overworld","serverTick":42,
			 "bounds":{"min":{"x":0,"y":64,"z":0},"max":{"x":0,"y":64,"z":0}},
			 "blocks":{"returned":1,"requested":1,"unloaded":0,"outsideWorld":0,"truncated":false},"entities":{"included":false}},
			 "player":{"position":{"x":2,"y":64,"z":0}},"entities":[],
			 "blocks":[{"position":{"x":0,"y":64,"z":0},"blockId":"minecraft:chest","properties":{"type":"single"},
			 "air":false,"fluid":false,"collisionEmpty":false,"collisionBoxes":[[0,0,0,1,0.875,1]]}]}
			""");
		String result=run(new SelfToolProvider(queries(world)),"survey_surroundings",json("{}"));
		assertTrue(result.startsWith("Tool result for survey_surroundings:\nCoverage: minecraft:overworld tick=42 box=0,64,0..0,64,0"),result);
		assertTrue(result.contains("1 chest(0,64,0) type=single"));
		assertTrue(result.contains("Terrain:\n 1?\nHeight relative to self feet:\n  ?"),result);
		assertFalse(result.contains("\\n"));
		assertFalse(result.contains("\"result\""));
		var call=call("query_world",json("{\"source\":\"function query(w){w.metadata.blocks.returned=-1;return 'hello';}\",\"input\":{}}"));
		String custom=queries(world).execute(call).get(15,TimeUnit.SECONDS);
		assertTrue(custom.contains("blocks=1/1"));
		assertTrue(custom.endsWith("\nhello"));
	}

	@Test void oresAreExcludedUnlessExplicitlyEnabledEvenWhenFocused() throws Exception {
		var world=json("""
			{"metadata":{"bounds":{"min":{"x":0,"y":64,"z":0},"max":{"x":0,"y":64,"z":0}}},
			 "player":{"position":{"x":2,"y":64,"z":0}},"entities":[],
			 "blocks":[{"position":{"x":0,"y":64,"z":0},"blockId":"minecraft:diamond_ore","properties":{},
			 "air":false,"fluid":false,"collisionEmpty":false,"collisionBoxes":[[0,0,0,1,1,1]]}]}
			""");
		for(String input:List.of("{}","{\"focus\":\"ore\"}","{\"includeOres\":false}")) {
			var result=JsonParser.parseString(geometry(world,json(input))).getAsJsonObject().getAsJsonObject("result");
			assertEquals(0,result.getAsJsonArray("landmarks").size());
		}
		var result=JsonParser.parseString(geometry(world,json("{\"includeOres\":true}"))).getAsJsonObject().getAsJsonObject("result");
		assertEquals("minecraft:diamond_ore",result.getAsJsonArray("landmarks").get(0).getAsJsonObject().get("blockId").getAsString());
	}

}
