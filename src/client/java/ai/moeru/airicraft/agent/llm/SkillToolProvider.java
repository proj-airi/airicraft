package ai.moeru.airicraft.agent.llm;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/**
 * Lazy task-specific rules: the system prompt lists the skills in one line each and this read tool returns a body as
 * a tool result. The tool list is fixed, so the frozen prefix and prompt cache are untouched.
 */
public final class SkillToolProvider implements PlannerToolProvider {
	public static final String USE_SKILL = "use_skill";
	private final PlannerSkills skills;

	public SkillToolProvider() {
		this(PlannerSkills.bundled());
	}

	SkillToolProvider(PlannerSkills skills) {
		this.skills = skills;
	}

	@Override public String id() { return "planner_skills"; }
	@Override public boolean handles(String name) { return USE_SKILL.equals(name); }

	@Override public String promptInstructions() {
		return skills.names().isEmpty() ? "" : "SKILLS\nDetailed rules for these tasks are not in this prompt. Call use_skill with the name before you act on a task that matches; "
			+ "the text returns as a tool result and stays in the conversation until compaction.\n" + skills.index();
	}

	@Override public List<Map<String, Object>> openAiTools() {
		if (skills.names().isEmpty()) return List.of();
		return List.of(toolForProvider(USE_SKILL, "Read the detailed rules for a task named in the SKILLS list of your instructions.",
			propertiesForProvider(propForProvider("name", enumStringForProvider("The skill to read.", skills.names()))), List.of("name")));
	}

	@Override public void validateArguments(String toolName, JsonObject arguments) {
		for (String key : arguments.keySet()) if (!key.equals("name")) throw new IllegalArgumentException("unexpected_argument_" + key);
		if (!arguments.has("name") || !arguments.get("name").isJsonPrimitive() || skills.find(arguments.get("name").getAsString()).isEmpty())
			throw new IllegalArgumentException("name_must_be_one_of_" + String.join("_", skills.names()));
	}

	@Override public CompletableFuture<String> execute(PlannerToolCall call) {
		try {
			validateArguments(call.name(), call.arguments());
			var skill = skills.find(call.arguments().get("name").getAsString()).orElseThrow();
			return CompletableFuture.completedFuture("Tool result for use_skill: skill=" + skill.name() + "\n" + skill.body());
		}
		catch (RuntimeException error) {
			return CompletableFuture.completedFuture("TOOL_ERROR: use_skill " + error.getMessage());
		}
	}
}
