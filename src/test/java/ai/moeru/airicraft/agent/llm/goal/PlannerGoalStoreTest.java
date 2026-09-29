package ai.moeru.airicraft.agent.llm.goal;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import com.google.gson.JsonParser;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlannerGoalStoreTest {
	@TempDir Path world;
	@Test void migratesFinishedV1WithoutReactivation() throws Exception {
		Files.createDirectories(world.resolve("airicraft"));
		Path file = world.resolve("airicraft/planner-goal.json");
		Files.writeString(file, """
			{"version":1,"goal":{"id":"old","objective":"shelter","status":"SUCCEEDED","outcome":"built"}}
			""");
		var store = new PlannerGoalStore(() -> world); store.refreshWorld();
		assertEquals(PlannerGoalStore.Status.SUCCEEDED, store.snapshot().status());
		assertFalse(store.active());
		assertTrue(Files.exists(file.resolveSibling("planner-goal.v1.backup.json")));
		assertEquals(2, JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("version").getAsInt());
		store.set("farm");
		assertTrue(Files.readString(file).contains("built"));
	}
	@Test void blockPersistsAndOnlyNamedEventsPermitReassessment() throws Exception {
		var store = new PlannerGoalStore(() -> world);
		var goal = store.set("farm", "stay at home", "harvest wheat");
		store.decide(goal.id(), "layout", "beside water", "irrigation");
		store.decide(goal.id(), "layout", "one row beside water", "less excavation");
		store.block(goal.id(), "no seeds", "JOB:one failed", "obtain seeds", List.of("pickup.item"));
		assertFalse(store.active()); assertTrue(store.blocked());
		assertFalse(store.relevantToBlock("work.changed")); assertTrue(store.relevantToBlock("pickup.item"));
		var reloaded = new PlannerGoalStore(() -> world); reloaded.refreshWorld();
		assertTrue(reloaded.blocked());
		assertEquals("irrigation", reloaded.snapshot().decisions().get("layout").previousReason());
		assertThrows(IllegalArgumentException.class, () -> reloaded.set("different"));
		reloaded.resume(goal.id(), "seeds found");
		assertTrue(reloaded.active()); assertEquals("stay at home", reloaded.snapshot().constraints());
	}
	@Test void thinkerCannotMutateEvenWithFabricatedToolCall() throws Exception {
		var store = new PlannerGoalStore(() -> world); var original = store.set("shelter");
		var provider = new PlannerGoalToolProvider(store, Runnable::run, false, () -> true);
		assertFalse(provider.handles("set_planner_goal"));
		assertEquals(1, provider.openAiTools().size());
		String result = provider.execute(new PlannerToolCall("one", "set_planner_goal", JsonParser.parseString("{\"objective\":\"different\"}").getAsJsonObject(), null)).join();
		assertTrue(result.contains("objective_control_requires_controller_ownership"));
		assertEquals(original.id(), store.snapshot().id());
	}
}
