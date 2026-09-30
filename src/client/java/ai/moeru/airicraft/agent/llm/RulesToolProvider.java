package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.attention.ReferenceAttentionPolicy;
import ai.moeru.airicraft.agent.events.EventCatalog;
import ai.moeru.airicraft.agent.rules.PlannerRules;
import ai.moeru.airicraft.rules.RuleModule;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/**
 * The planner's view of its own attention and salience rules (spec 4.12): read them, replace one after a replay
 * diff, roll back, and read the contract. The tool list is fixed; the runtime binds the service that acts on it.
 */
public final class RulesToolProvider implements PlannerToolProvider {
	public static final String INSPECT = "inspect_rules";
	public static final String UPDATE = "update_rules";
	public static final String DOCS = "read_rules_docs";
	private static final Set<String> TOOLS = Set.of(INSPECT, UPDATE, DOCS);
	private static final List<String> HOOKS = List.of("attention", "salience");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private volatile PlannerRules rules;

	/** Connects the tools to the runtime's rule authorship; until then they report themselves unavailable. */
	public void bind(PlannerRules rules) {
		this.rules = rules;
	}

	@Override public String id() { return "planner_rules"; }
	@Override public boolean handles(String name) { return TOOLS.contains(name); }
	@Override public boolean isReadTool(String name) { return !name.equals(UPDATE); }

	@Override public List<Map<String, Object>> openAiTools() {
		return List.of(
			toolForProvider(INSPECT, "Read one of your rule modules: its running source, version history, engine health and the rule ids of recent decisions. "
				+ "Pass version to read an older version's source. Start from this source before you change it.",
				propertiesForProvider(
					propForProvider("hook", enumStringForProvider("Which module.", HOOKS)),
					propForProvider("version", Map.of("type", "integer", "description", "A version number from the history; omit for the running source."))
				), List.of("hook")),
			toolForProvider(UPDATE, "Replace one of your rule modules, or roll it back. A replacement is loaded, run once and replayed against recent history next to the running module; "
				+ "the result shows the wakes or percepts it would have gained and lost. A module that fails is rejected and nothing changes. Session-local; the constitution and the clamp cannot be changed. "
				+ "Send exactly one of source (the complete new module) or revert_to (a version number, or base for the original rules). Read read_rules_docs first.",
				propertiesForProvider(
					propForProvider("hook", enumStringForProvider("Which module.", HOOKS)),
					propForProvider("source", optionalStringForProvider("The complete module source, up to 32768 characters.")),
					propForProvider("revert_to", optionalStringForProvider("A version number from inspect_rules, or base.")),
					propForProvider("reason", stringForProvider("What you are tuning and why, up to 200 characters. Recorded with the version."))
				), List.of("hook", "reason")),
			toolForProvider(DOCS, "Read the rule module contract, the helper library, what the constitution and the clamp protect, and the bundled attention module as an example. No arguments.",
				propertiesForProvider(), List.of()));
	}

	@Override public void validateArguments(String name, JsonObject args) {
		switch (name) {
			case DOCS -> {
				if (!args.isEmpty()) throw new IllegalArgumentException("read_rules_docs_takes_no_arguments");
			}
			case INSPECT -> {
				requireKeys(args, Set.of("hook", "version"));
				hook(args);
				if (args.has("version") && !(args.get("version").isJsonPrimitive() && args.get("version").getAsJsonPrimitive().isNumber()
					&& args.get("version").getAsInt() >= 1)) throw new IllegalArgumentException("version_must_be_a_positive_integer");
			}
			case UPDATE -> {
				requireKeys(args, Set.of("hook", "source", "revert_to", "reason"));
				hook(args);
				if (!args.has("reason") || !args.get("reason").isJsonPrimitive()) throw new IllegalArgumentException("reason_required");
				String reason = args.get("reason").getAsString();
				if (reason.isBlank() || reason.length() > PlannerRules.MAX_REASON_CHARS) throw new IllegalArgumentException("reason_1_to_" + PlannerRules.MAX_REASON_CHARS + "_characters");
				if (args.has("source") == args.has("revert_to")) throw new IllegalArgumentException("send_exactly_one_of_source_or_revert_to");
				if (args.has("source")) {
					String source = args.get("source").isJsonPrimitive() ? args.get("source").getAsString() : "";
					if (source.isBlank() || source.length() > RuleModule.MAX_SOURCE_CHARS) throw new IllegalArgumentException("source_1_to_" + RuleModule.MAX_SOURCE_CHARS + "_characters");
				}
				else if (!revertTarget(args).matches("base|[1-9][0-9]{0,8}")) {
					throw new IllegalArgumentException("revert_to_must_be_base_or_a_version_number");
				}
			}
			default -> throw new IllegalArgumentException("unknown_rules_tool");
		}
	}

	private static void requireKeys(JsonObject args, Set<String> allowed) {
		for (String key : args.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("unexpected_argument_" + key);
	}

	private static String revertTarget(JsonObject args) {
		return args.get("revert_to").isJsonPrimitive() ? args.get("revert_to").getAsString().strip() : "";
	}

	private static RuleModule.Hook hook(JsonObject args) {
		String hook = args.has("hook") && args.get("hook").isJsonPrimitive() ? args.get("hook").getAsString() : "";
		if (!HOOKS.contains(hook)) throw new IllegalArgumentException("hook_must_be_attention_or_salience");
		return RuleModule.Hook.valueOf(hook.toUpperCase());
	}

	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		String name = call.name();
		try {
			validateArguments(name, call.arguments());
			if (name.equals(DOCS)) return CompletableFuture.completedFuture(documentation());
			PlannerRules service = rules;
			if (service == null) return CompletableFuture.completedFuture("TOOL_UNAVAILABLE: rules_unavailable");
			var args = call.arguments();
			if (name.equals(INSPECT)) {
				var map = service.inspect(hook(args), args.has("version") ? args.get("version").getAsInt() : null);
				return CompletableFuture.completedFuture("Tool result for inspect_rules: " + GSON.toJson(map));
			}
			var request = new PlannerRules.Request(hook(args), args.has("source") ? args.get("source").getAsString() : null,
				args.has("revert_to") ? revertTarget(args) : null, args.get("reason").getAsString().strip());
			return service.update(request).handle((result, failure) -> failure == null
				? "Tool result for update_rules: " + GSON.toJson(result) : refusal(name, failure));
		}
		catch (PlannerRules.Refused refused) {
			return CompletableFuture.completedFuture(refusal(name, refused));
		}
		catch (RuntimeException error) {
			return CompletableFuture.completedFuture("TOOL_ERROR: " + name + " " + error.getMessage());
		}
	}

	private static String refusal(String tool, Throwable failure) {
		Throwable cause = failure;
		while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
		if (cause instanceof PlannerRules.Refused refused) {
			return "TOOL_ERROR: " + tool + " " + refused.code() + ": " + refused.getMessage()
				+ (refused.details().isEmpty() ? "" : " " + GSON.toJson(refused.details()));
		}
		return "TOOL_ERROR: " + tool + " rules_failed: " + cause.getMessage();
	}

	/** The contract, the helper library, what is protected (from the live catalog) and the bundled module. */
	static String documentation() {
		var protectedTypes = new TreeSet<String>();
		EventCatalog.defaults().routingProfiles().forEach((type, profile) -> { if (profile.policyBypass()) protectedTypes.add(type); });
		return PolicyDocsToolProvider.readResource("/airicraft/rules/docs.md")
			+ "\n```js\n" + PolicyDocsToolProvider.readResource("/airicraft/rules/lib.js").strip() + "\n```\n\n"
			+ "## Protected event types\n\n"
			+ "The clamp keeps the wakes of these types: " + String.join(", ", protectedTypes) + ".\n\n"
			+ "The constitution decides these types before the rules run: "
			+ String.join(", ", new TreeSet<>(ReferenceAttentionPolicy.CONSTITUTION_TYPES)) + ".\n\n"
			+ "## Example: the bundled attention module\n\n```js\n"
			+ PolicyDocsToolProvider.readResource("/airicraft/rules/attention/default.js").strip() + "\n```\n";
	}
}
