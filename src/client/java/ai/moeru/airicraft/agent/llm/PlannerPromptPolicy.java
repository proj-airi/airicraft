package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.character.CharacterCard;
import ai.moeru.airicraft.agent.character.CharacterPrompt;
import ai.moeru.airicraft.agent.dialogue.DialogueSpeakerLabels;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class PlannerPromptPolicy {
	private static final String SYSTEM_PROMPT_TEMPLATE = "/prompts/planner-system.md";
	private static final String COMPACTION_PROMPT_TEMPLATE = "/prompts/planner-compaction.md";
	private static final String COMPACTION_PROMPT_TEMPLATE_TEXT = readTemplate(COMPACTION_PROMPT_TEMPLATE);

	private PlannerPromptPolicy() {
	}

	public static String systemPrompt(PlannerVisionMode visionMode) {
		return systemPrompt(visionMode, PlannerToolRegistry.empty());
	}

	public static String systemPrompt(PlannerVisionMode visionMode, PlannerToolRegistry toolRegistry) {
		return systemPrompt(visionMode, toolRegistry, null);
	}

	/** A null character prompt renders the built-in character without an in-game name. */
	public static String systemPrompt(PlannerVisionMode visionMode, PlannerToolRegistry toolRegistry, String characterPrompt) {
		PlannerToolRegistry effectiveToolRegistry = toolRegistry == null ? PlannerToolRegistry.empty() : toolRegistry;
		String visionInstruction = "If visual information is needed, use take_a_look when it is in your tool schema.";
		String character = characterPrompt == null ? CharacterPrompt.render(CharacterCard.defaults(), null) : characterPrompt;
		return renderTemplate(SYSTEM_PROMPT_TEMPLATE, readTemplate(SYSTEM_PROMPT_TEMPLATE), Map.of(
			"character", character,
			"vision_instruction", visionInstruction,
			"provider_tool_instructions", effectiveToolRegistry.promptInstructions(),
			"same_client_admin", DialogueSpeakerLabels.SAME_CLIENT_ADMIN
		));
	}

	public static String compactionInstruction() {
		return COMPACTION_PROMPT_TEMPLATE_TEXT;
	}

	private static String renderTemplate(String resourcePath, String template, Map<String, String> values) {
		String rendered = template;
		for (Map.Entry<String, String> entry : values.entrySet()) {
			rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
		}
		if (rendered.contains("{{")) {
			throw new IllegalStateException("Unresolved prompt placeholder in " + resourcePath);
		}
		return rendered;
	}

	private static String readTemplate(String resourcePath) {
		try (InputStream stream = PlannerPromptPolicy.class.getResourceAsStream(resourcePath)) {
			if (stream == null) {
				throw new IllegalStateException("Missing embedded planner prompt template: " + resourcePath);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Failed to read planner prompt template: " + resourcePath, exception);
		}
	}
}
