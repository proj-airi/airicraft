package ai.moeru.airicraft.agent.llm;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CaveSurveyToolProviderTest {
	@Test void spreadsVisibleStandingCandidatesAndSkipsTheCurrentStance() {
		BlockPos origin = new BlockPos(0, 64, 0);
		BlockPos east = new BlockPos(8, 64, 0), north = new BlockPos(0, 62, -7);
		assertEquals(List.of(east, north), CaveSurveyToolProvider.selectWaypoints(origin,
			List.of(origin, origin.east(), new BlockPos(3, 64, 0), east, east, north)));
	}
	@Test void noObservedFloorsDoesNotInventAnExplorationGoal() {
		assertTrue(CaveSurveyToolProvider.selectWaypoints(BlockPos.ZERO, List.of()).isEmpty());
	}
	@Test void surveyIsReadOnlyAndRadiusIsBounded() {
		var provider = new CaveSurveyToolProvider(ignored -> {});
		assertTrue(provider.isReadTool("survey_cave"));
		provider.validateArguments("survey_cave", com.google.gson.JsonParser.parseString("{\"radius\":24}").getAsJsonObject());
		assertThrows(com.google.gson.JsonParseException.class, () -> provider.validateArguments("survey_cave",
			com.google.gson.JsonParser.parseString("{\"radius\":100}").getAsJsonObject()));
		assertThrows(com.google.gson.JsonParseException.class, () -> provider.validateArguments("survey_cave",
			com.google.gson.JsonParser.parseString("{\"radius\":4.5}").getAsJsonObject()));
	}
}
