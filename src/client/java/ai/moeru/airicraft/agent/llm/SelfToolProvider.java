package ai.moeru.airicraft.agent.llm;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** Session-local named read-only policies. Native tools cannot be replaced. */
public final class SelfToolProvider implements PlannerToolProvider {
	private final WorldQueryScriptToolProvider queries;
	private final Map<String, JsonObject> definitions = new LinkedHashMap<>();
	private static final Set<String> MANAGEMENT = Set.of("define_tool", "inspect_tool", "remove_tool");
	public SelfToolProvider(WorldQueryScriptToolProvider queries) {
		this.queries = queries;
		var survey = JsonParser.parseString(PolicyDocsToolProvider.readResource("/airicraft/policies/survey.json")).getAsJsonObject();
		survey.addProperty("source", PolicyDocsToolProvider.readResource("/airicraft/policies/survey.js"));
		definitions.put("survey_surroundings", survey);
	}
	@Override public String id() { return "self_tools"; }
	@Override public boolean dynamicTools() { return true; }
	@Override public synchronized boolean handles(String name) { return MANAGEMENT.contains(name) || definitions.containsKey(name); }
	@Override public boolean isReadTool(String name) { return !Set.of("define_tool", "remove_tool").contains(name); }
	@Override public String promptInstructions() {
		return "Use survey_surroundings for local landmarks, terrain and elevation maps. You can create your own read-only tools with define_tool. "
			+ "Inspect survey_surroundings with inspect_tool to read its source and schema as a working example. "
			+ "Definitions are session-local and immediately exposed as callable tools on the next request. Changes replace the current definition; no versions. "
			+ "Custom names must start custom_. query(world,input) receives the query_world snapshot; source has no effects or host access. "
			+ "Try source with query_world before defining it. Each call captures fresh data using the definition's fixed capture settings. "
			+ "Input schemas support flat string/number/integer/boolean properties, description, enum, minimum, maximum, required and additionalProperties=false. "
			+ "inspect_tool with no name lists tools; remove_tool removes one. Read read_policy_docs for snapshot fields.";
	}
	@Override public synchronized List<Map<String,Object>> openAiTools() {
		var tools = new ArrayList<Map<String,Object>>();
		tools.add(toolForProvider("define_tool", "Create or replace a session read-only JavaScript tool. Immediately callable. Inspect the bundled survey_surroundings example first.", propertiesForProvider(
			propForProvider("name", stringForProvider("custom_ followed by lowercase letters, digits or underscores; survey_surroundings may also be edited.")),
			propForProvider("description", stringForProvider("When to use this tool and what it returns.")),
			propForProvider("parameters", Map.of("type","object","description","Flat JSON object schema; typed primitive properties, enum/minimum/maximum; additionalProperties=false.")),
			propForProvider("source", stringForProvider("JavaScript function query(world,input), up to 32768 characters.")),
			propForProvider("capture", Map.of("type","object","description","Fixed query_world snapshot settings: radius, verticalRadius, center, includeBlocks, includeEntities. Defaults apply when omitted."))
		), List.of("name","description","parameters","source","capture")));
		tools.add(toolForProvider("inspect_tool", "Read a tool definition including editable source, or list all self tools when name is omitted.", propertiesForProvider(propForProvider("name",stringForProvider("Tool name."))),List.of()));
		tools.add(toolForProvider("remove_tool", "Remove a self-created tool from this session's catalog.", propertiesForProvider(propForProvider("name",stringForProvider("Tool name."))),List.of("name")));
		for (var d : definitions.values()) tools.add(Map.of("type","function","function",Map.of("name",d.get("name").getAsString(),"description",d.get("description").getAsString(),"parameters",new Gson().fromJson(d.get("parameters"),Map.class))));
		return List.copyOf(tools);
	}
	@Override public synchronized void validateArguments(String name, JsonObject args) {
		if (name.equals("define_tool")) {
			if (!args.keySet().equals(Set.of("name","description","parameters","source","capture"))) throw new IllegalArgumentException("definition_fields_required");
			String n = args.get("name").getAsString();
			if (!n.matches("custom_[a-z0-9_]{1,48}") && !n.equals("survey_surroundings")) throw new IllegalArgumentException("custom_name_required");
			if (args.get("description").getAsString().isBlank() || args.get("description").getAsString().length()>1024) throw new IllegalArgumentException("description_limit");
			if (!definitions.containsKey(n) && definitions.size()>=16) throw new IllegalArgumentException("tool_limit_16");
			ToolInputSchema.check(args.getAsJsonObject("parameters"));
			queries.validateArguments("query_world", queryArguments(args, new JsonObject()));
		} else if (MANAGEMENT.contains(name)) {
			if (!Set.of("name").containsAll(args.keySet()) || (name.equals("remove_tool") && !args.has("name"))) throw new IllegalArgumentException("invalid_tool_name_argument");
			if (args.has("name") && !definitions.containsKey(args.get("name").getAsString())) throw new IllegalArgumentException("unknown_self_tool");
		} else {
			var d = definitions.get(name);
			if (d == null) throw new IllegalArgumentException("unknown_self_tool");
			ToolInputSchema.validate(d.getAsJsonObject("parameters"),args);
		}
	}
	private static JsonObject queryArguments(JsonObject definition, JsonObject input) {
		var args = definition.getAsJsonObject("capture").deepCopy();
		if (args.has("source") || args.has("input")) throw new IllegalArgumentException("invalid_capture_fields");
		args.add("source",definition.get("source")); args.add("input",input);
		return args;
	}
	@Override public synchronized CompletableFuture<String> execute(PlannerToolCall call) {
		try {
			String name=call.name(); var args=call.arguments(); validateArguments(name,args);
			if (name.equals("define_tool")) {
				definitions.put(args.get("name").getAsString(),args.deepCopy());
				return CompletableFuture.completedFuture("Tool defined: " + args.get("name").getAsString());
			}
			if (name.equals("inspect_tool")) return CompletableFuture.completedFuture(args.has("name") ? definitions.get(args.get("name").getAsString()).toString() : definitions.keySet().toString());
			if (name.equals("remove_tool")) { definitions.remove(args.get("name").getAsString()); return CompletableFuture.completedFuture("Tool removed"); }
			return queries.execute(new PlannerToolCall(call.id(),"query_world",queryArguments(definitions.get(name),args),null))
				.thenApply(result -> result.replaceFirst("query_world",name));
		} catch (RuntimeException error) { return CompletableFuture.completedFuture("TOOL_ERROR: " + call.name() + " " + error.getMessage()); }
	}
}
