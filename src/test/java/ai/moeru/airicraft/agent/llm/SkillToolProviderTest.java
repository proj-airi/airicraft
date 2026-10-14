package ai.moeru.airicraft.agent.llm;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillToolProviderTest {
	private final PlannerSkills skills = PlannerSkills.bundled();
	private final SkillToolProvider provider = new SkillToolProvider();

	private static JsonObject args(String json) {
		return JsonParser.parseString(json).getAsJsonObject();
	}

	@Test void everyIndexedSkillHasABodyAndAShortDescription() {
		assertFalse(skills.names().isEmpty());
		assertEquals(List.of(), PlannerSkills.problems(skills));
	}

	@Test void theSystemPromptCarriesOnlyTheIndex() {
		String instructions = provider.promptInstructions();
		for (String name : skills.names()) assertTrue(instructions.contains("- " + name + ": "), name);
		assertFalse(instructions.contains("craft minecraft:torch from charcoal and sticks"), "bodies stay out of the prompt");
		assertFalse(PlannerPromptPolicy.systemPrompt(PlannerVisionMode.EXTERNAL_SUMMARY).contains("craft minecraft:torch from charcoal and sticks"));
	}

	@Test void useSkillReturnsTheBodyAsAReadToolResult() throws Exception {
		assertTrue(provider.isReadTool("use_skill"));
		assertFalse(provider.dynamicTools(), "the tool list is fixed, so the frozen prefix holds");
		String result = provider.execute(new PlannerToolCall("call-1", "use_skill", args("{\"name\":\"mining-light\"}"), null)).get();
		assertTrue(result.startsWith("Tool result for use_skill: skill=mining-light"));
		assertTrue(result.contains("craft minecraft:torch from charcoal and sticks"));
	}

	@Test void unknownSkillsAndExtraArgumentsAreRefused() {
		assertThrows(IllegalArgumentException.class, () -> provider.validateArguments("use_skill", args("{\"name\":\"nope\"}")));
		assertThrows(IllegalArgumentException.class, () -> provider.validateArguments("use_skill", args("{\"name\":\"furnaces\",\"x\":1}")));
		assertThrows(IllegalArgumentException.class, () -> provider.validateArguments("use_skill", args("{}")));
		provider.validateArguments("use_skill", args("{\"name\":\"furnaces\"}"));
	}

	@Test void parseRejectsAMismatchedName() {
		assertThrows(IllegalStateException.class, () -> PlannerSkills.parse("a", "---\nname: b\ndescription: d\n---\nbody"));
		var skill = PlannerSkills.parse("a", "---\nname: a\ndescription: d\n---\n\nbody\n");
		assertEquals(Map.of("a", "body").get("a"), skill.body());
	}

	@Test void blocksFrontmatterMatchesNamespacesAndExactIds() {
		var skill = PlannerSkills.parse("a", "---\nname: a\ndescription: d\nblocks: create:*, minecraft:furnace\n---\nbody");
		assertEquals(List.of("create:*", "minecraft:furnace"), skill.blocks());
		assertEquals(List.of(), PlannerSkills.parse("a", "---\nname: a\ndescription: d\n---\nbody").blocks());
	}

	@Test void aCreateBlockPointsAtTheModdedMachinesSkillAndVanillaDoesNot() {
		assertEquals(List.of("modded-machines"), skills.matchingBlocks("{\"expectedState\":\"create:hand_crank[facing=north]\"}").stream().map(PlannerSkills.Skill::name).toList());
		assertEquals(List.of(), skills.matchingBlocks("minecraft:stone_bricks placed; createx:thing"));
	}
}
