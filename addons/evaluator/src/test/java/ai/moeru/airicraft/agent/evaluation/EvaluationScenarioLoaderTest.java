package ai.moeru.airicraft.agent.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EvaluationScenarioLoaderTest {
	@Test
	void optionalModsDefaultToNoneAndRoundTrip(@TempDir Path tempDir) throws Exception {
		Path config = tempDir.resolve("scenario.yml");
		assertEquals(java.util.List.of(), EvaluationScenarioLoader.fromMap(java.util.Map.of(), config).requiredMods());
		for (var mods : java.util.List.of(java.util.List.<String>of(), java.util.List.of("journeymap"),
			java.util.List.of("roughlyenoughitems"), java.util.List.of("journeymap", "roughlyenoughitems"))) {
			var scenario = EvaluationScenarioLoader.fromMap(java.util.Map.of("requiredMods", mods), config);
			EvaluationScenarioLoader.write(config, scenario);
			assertEquals(mods, EvaluationScenarioLoader.load(config).requiredMods());
		}
	}

	@Test
	void rejectsMalformedOrUnsupportedRequiredMods(@TempDir Path tempDir) throws Exception {
		Path config = tempDir.resolve("scenario.yml");
		for (String value : java.util.List.of("null", "journeymap", "[unknown_mod]", "[1]")) {
			Files.writeString(config, "requiredMods: " + value);
			assertThrows(IllegalArgumentException.class, () -> EvaluationScenarioLoader.load(config));
		}
	}

	@Test
	void parsesScenarioConfig(@TempDir Path tempDir) throws Exception {
		Path config = tempDir.resolve("scenarios").resolve("smelting-basic").resolve("scenario.yml");
		Files.createDirectories(config.getParent());
		Files.writeString(config, """
			id: smelting-basic
			name: Smelting basic
			metadata:
			  minecraftVersion: "1.21.8"
			  airicraftVersion: dev
			worldArchive: world.zip
			frozen: true
			prompt: "@agent smelt one iron ore"
			budget:
			  maxPlannerTurns: 8
			  maxElapsedTicks: 1200
			  maxElapsedMillis: 60000
			  heartbeatIntervalTicks: 40
			  maxStallTicks: 900
			checks:
			  - type: inventory_contains
			    itemId: minecraft:iron_ingot
			    count: 1
			waypoints:
			  - provider: journeymap
			    id: farm-here
			    name: Farm here
			    dimension: minecraft:overworld
			    x: -31
			    y: 63
			    z: -63
			evidence:
			  includePlannerJournal: true
			  includeDebugTimeline: false
			  includeRecentEvents: true
			  includeTaskState: true
			  includeWorldSnapshot: false
			""", StandardCharsets.UTF_8);

		EvaluationScenario scenario = EvaluationScenarioLoader.load(config);

		assertEquals("smelting-basic", scenario.id());
		assertEquals("Smelting basic", scenario.name());
		assertEquals("1.21.8", scenario.minecraftVersion());
		assertEquals("dev", scenario.airicraftVersion());
		assertTrue(scenario.frozen());
		assertEquals("@agent smelt one iron ore", scenario.prompt());
		assertEquals(8, scenario.budget().maxPlannerTurns());
		assertEquals(1200L, scenario.budget().maxElapsedTicks());
		assertEquals(60000L, scenario.budget().maxElapsedMillis());
		assertEquals(40L, scenario.budget().heartbeatIntervalTicks());
		assertEquals(900L, scenario.budget().maxStallTicks());
		assertEquals(EvaluationBudget.DEFAULT_MAX_STALL_TICKS,
			EvaluationScenarioLoader.fromMap(java.util.Map.of("budget", java.util.Map.of("maxPlannerTurns", 3)), null).budget().maxStallTicks());
		assertEquals(1, scenario.checks().size());
		assertEquals("inventory_contains", scenario.checks().getFirst().type());
		assertEquals("minecraft:iron_ingot", scenario.checks().getFirst().string("itemId"));
		assertEquals(1, scenario.waypoints().size());
		EvaluationWaypoint waypoint = scenario.waypoints().getFirst();
		assertEquals("journeymap", waypoint.provider());
		assertEquals("farm-here", waypoint.id());
		assertEquals("Farm here", waypoint.name());
		assertEquals("minecraft:overworld", waypoint.dimension());
		assertEquals(-31, waypoint.x());
		assertEquals(63, waypoint.y());
		assertEquals(-63, waypoint.z());
		assertFalse(scenario.evidence().includeDebugTimeline());
		assertFalse(scenario.evidence().includeWorldSnapshot());
	}

	@Test
	void writesScenarioConfig(@TempDir Path tempDir) throws Exception {
		Path config = tempDir.resolve("scenarios").resolve("wood").resolve("scenario.yml");
		EvaluationScenario scenario = new EvaluationScenario(
			"wood",
			"Wood",
			"1.21.8",
			"dev",
			config,
			"world.zip",
			true,
			"@agent collect wood",
			new EvaluationBudget(2, 100, 0, 10),
			java.util.List.of(new EvaluationCheck("event_contains", java.util.Map.of("eventType", "task.completed"))),
			java.util.List.of(new EvaluationWaypoint("journeymap", "farm-here", "Farm here", "minecraft:overworld", -31, 63, -63)),
			EvaluationEvidenceSettings.defaults()
		);

		EvaluationScenarioLoader.write(config, scenario);
		EvaluationScenario loaded = EvaluationScenarioLoader.load(config);

		assertEquals("wood", loaded.id());
		assertTrue(loaded.frozen());
		assertEquals("event_contains", loaded.checks().getFirst().type());
		assertEquals("task.completed", loaded.checks().getFirst().string("eventType"));
		assertEquals("Farm here", loaded.waypoints().getFirst().name());
		assertEquals(-31, loaded.waypoints().getFirst().x());
	}
}
