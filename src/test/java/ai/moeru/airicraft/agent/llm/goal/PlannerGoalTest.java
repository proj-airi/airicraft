package ai.moeru.airicraft.agent.llm.goal;

import ai.moeru.airicraft.agent.llm.PlannerToolCall;
import ai.moeru.airicraft.agent.llm.PlannerToolRegistry;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PlannerGoalTest {
	@TempDir Path world;

	@Test void lifecyclePersistsAndRejectsStaleCompletionAcrossChange() throws Exception {
		var store = new PlannerGoalStore(() -> world);
		var first = store.set("Prepare torches and scout a cave");
		assertThrows(IllegalArgumentException.class, () -> store.set("Another goal"));
		var changed = store.change(first.id(), "Build shelter first", "Nightfall before cave preparation");
		assertThrows(IllegalArgumentException.class, () -> store.finish(first.id(), PlannerGoalStore.Status.SUCCEEDED, "Old result"));
		var loaded = new PlannerGoalStore(() -> world);
		loaded.refreshWorld();
		assertEquals(changed, loaded.snapshot());
		assertTrue(loaded.active());
		loaded.finish(changed.id(), PlannerGoalStore.Status.SUCCEEDED, "Roof, walls and entrance verified");
		var reloaded = new PlannerGoalStore(() -> world);
		reloaded.refreshWorld();
		assertFalse(reloaded.active());
		assertEquals(PlannerGoalStore.Status.SUCCEEDED, reloaded.snapshot().status());
	}

	@Test void worldSwitchCannotCarryAnObjectiveIntoAnotherWorld() throws Exception {
		var path = new AtomicReference<>(world);
		var store = new PlannerGoalStore(path::get);
		var first = store.set("Explore this world");
		path.set(world.resolve("other"));
		store.refreshWorld();
		assertFalse(store.active());
		assertNull(store.snapshot());
		path.set(null);
		assertThrows(IllegalStateException.class, () -> store.set("No world"));
		path.set(world);
		store.refreshWorld();
		assertEquals(first, store.snapshot());
	}

	@Test void malformedSaveIsReportedAndNeverOverwritten() throws Exception {
		Files.createDirectories(world.resolve("airicraft"));
		Path file = world.resolve("airicraft/planner-goal.json");
		Files.writeString(file, "{broken");
		var store = new PlannerGoalStore(() -> world);
		store.refreshWorld();
		assertFalse(store.active());
		assertTrue(store.context().contains("load_failed"));
		assertThrows(IllegalStateException.class, () -> store.set("New objective"));
		assertEquals("{broken", Files.readString(file));
	}

	@Test void goalToolsAreAvailableInitiallyAndFreshStateStaysOutsideSystemPrompt() throws Exception {
		var store = new PlannerGoalStore(() -> world);
		var provider = new PlannerGoalToolProvider(store, Runnable::run);
		var registry = PlannerToolRegistry.of(provider);
		assertTrue(registry.isActiveTool("set_planner_goal"));
		assertTrue(registry.isActiveTool("finish_planner_goal"));
		String result = provider.execute(new PlannerToolCall(null, "set_planner_goal", JsonParser.parseString("{\"objective\":\"Scout a cave\"}").getAsJsonObject(), null)).get();
		assertTrue(result.contains("ACTIVE"));
		assertTrue(registry.contextSnapshot().contains("Scout a cave"));
		assertFalse(registry.promptInstructions().contains("Scout a cave"));
		assertFalse(registry.isReadTool("change_planner_goal"));
		var finished = store.finish(store.snapshot().id(), PlannerGoalStore.Status.GIVEN_UP, "No safe entrance found");
		assertTrue(provider.contextSnapshot().contains("GIVEN_UP"));
		assertEquals(finished.outcome(), "No safe entrance found");
	}
}
