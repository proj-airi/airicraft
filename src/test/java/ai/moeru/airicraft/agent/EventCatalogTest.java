package ai.moeru.airicraft.agent;

import ai.moeru.airicraft.agent.actions.BlockAcquisitionTestFixtures;
import ai.moeru.airicraft.agent.events.EventCatalog;
import ai.moeru.airicraft.agent.events.EventCause;
import ai.moeru.airicraft.agent.events.EventFamily;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.EventTypeSpec;
import ai.moeru.airicraft.agent.events.EventVisibility;
import ai.moeru.airicraft.agent.events.SemanticEventBuffer;
import ai.moeru.airicraft.agent.llm.PlannerDecisionContext;
import ai.moeru.airicraft.agent.tasks.TaskResourceKind;
import ai.moeru.airicraft.agent.tasks.TaskSpec;
import ai.moeru.airicraft.agent.tasks.TaskType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventCatalogTest {
	@Test void everySpecHasFamilyAndValidPrefix() {
		for (var spec : EventCatalog.defaults().specs()) {
			assertNotNull(spec.family(), spec.id());
			if (spec.prefix()) assertTrue(spec.id().endsWith("."), spec.id());
		}
	}

	@Test void exactIdWinsAndLongestPrefixMatches() {
		var broad = spec("example.", true);
		var narrow = spec("example.deep.", true);
		var exact = spec("example.deep.item", false);
		var catalog = new EventCatalog(List.of(broad, narrow, exact));
		assertEquals(exact, catalog.find("example.deep.item"));
		assertEquals(narrow, catalog.find("example.deep.other"));
		assertEquals(broad, catalog.find("example.other"));
		assertNull(catalog.find("elsewhere"));
	}

	@Test void visibilityAgreesWithPlannerObservation() {
		for (var spec : EventCatalog.defaults().specs()) {
			String id = spec.prefix() ? spec.id() + "sample" : spec.id();
			var events = new SemanticEventBuffer(2);
			Map<String, Object> payload = id.equals("work.changed")
				? new ai.moeru.airicraft.agent.work.WorkSnapshot(new ai.moeru.airicraft.agent.work.WorkHandle("JOB:test"), "", ai.moeru.airicraft.agent.work.WorkSnapshot.State.FAILED, "mine", "FAILED", false, 1, Map.of()).payload()
				: Map.of();
			events.append(1, id, payload);
			var context = new PlannerDecisionContext("test", 1, 1, "controller", "idle", Map.of(), events.query(null));
			boolean visible = !((List<?>) context.observation(0).get("events")).isEmpty();
			assertEquals(visible, spec.observeVisibility() == EventVisibility.PLANNER, id);
		}
	}

	@Test void causeCoverageReport() {
		try (var harness = new WakeScenarioHarness()) {
			harness.runtime.overrideBlockAcquisitionsForTests(BlockAcquisitionTestFixtures.survival());
			harness.runtime.submitTask(new TaskSpec(TaskType.COLLECT_RESOURCE, TaskResourceKind.WOOD_LOGS, 2), "test");
			harness.runtime.onClientTick(null);
			harness.runtime.onPlayerHealthUpdated(true, 10.0F, 0.0F);
			var events = harness.runtime.recentEvents(null).events();
			var death = events.stream().filter(event -> event.type().equals("player.died")).findFirst().orElseThrow();
			var cancelled = events.stream().filter(event -> event.type().equals("player.actions_cancelled")).findFirst().orElseThrow();
			var changed = events.stream().filter(event -> event.type().equals("work.changed")).findFirst().orElseThrow();
			assertEquals(EventCause.event(death.seqNo()), cancelled.cause());
			assertEquals(EventCause.work((String) changed.payload().get("workId")), changed.cause());

			var counts = new EnumMap<EventFamily, int[]>(EventFamily.class);
			for (var family : EventFamily.values()) counts.put(family, new int[2]);
			for (var event : events) {
				var spec = EventCatalog.defaults().find(event.type());
				assertNotNull(spec, event.type());
				var count = counts.get(spec.family());
				count[0]++;
				if (event.cause() != null) count[1]++;
			}
			for (var family : EventFamily.values()) {
				var count = counts.get(family);
				System.out.printf("cause coverage %s: %d/%d (%.1f%%)%n", family,
					count[1], count[0], count[0] == 0 ? 0.0 : 100.0 * count[1] / count[0]);
			}
		}
	}

	private static EventTypeSpec spec(String id, boolean prefix) {
		return new EventTypeSpec(id, prefix, EventFamily.INTERNAL, Set.of("test"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly(id));
	}
}
