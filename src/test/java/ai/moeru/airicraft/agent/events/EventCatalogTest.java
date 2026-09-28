package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.llm.PlannerDecisionContext;
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

	private static EventTypeSpec spec(String id, boolean prefix) {
		return new EventTypeSpec(id, prefix, EventFamily.INTERNAL, Set.of("test"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly(id));
	}
}
