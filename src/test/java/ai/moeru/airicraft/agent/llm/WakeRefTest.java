package ai.moeru.airicraft.agent.llm;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WakeRefTest {
	private static PlannerTrigger system(String speaker, String key) {
		return PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, speaker, "text", 1, 50, key);
	}

	@Test void eventWakesAreDeduplicatedBySequenceKeepingTheMostUrgent() {
		var low = system("self", "pickup").withWake(WakeRef.event(7, "reflex.resolved", "LOW"));
		var critical = system("runtime", "task").withWake(WakeRef.event(7, "reflex.resolved", "CRITICAL"));
		var other = system("self", "craft").withWake(WakeRef.event(8, "crafting.item_crafted", "LOW"));
		assertEquals(List.of(
			Map.of("reason", "event", "seqNo", 7L, "type", "reflex.resolved", "urgency", "critical"),
			Map.of("reason", "event", "seqNo", 8L, "type", "crafting.item_crafted", "urgency", "low")),
			WakeRef.render(List.of(low, critical, other)));
	}

	@Test void chatIsTheUserTurnAndNeverAWakeEntry() {
		var chat = PlannerTrigger.direct(PlannerTriggerType.CHAT, "Alex", "hi", 1, 50).withWake(WakeRef.event(3, "social.player_addressed_agent", "DIRECT"));
		assertEquals(List.of(), WakeRef.render(List.of(chat)));
	}

	@Test void reasonsWithoutEvidenceComeFromTheWakeOrigin() {
		var triggers = List.of(
			system("self", "planner_goal"),
			system("controller", "delegation"),
			PlannerTrigger.pending(PlannerTriggerType.IDLE_THINK, "self", "idle", 1, 50),
			PlannerTrigger.pending(PlannerTriggerType.SYSTEM, "tool_queue", "FIFO empty.", 1, 50),
			PlannerTrigger.pending(PlannerTriggerType.SYSTEM, "evaluation", "x", 1, 50),
			system("self", "planner_goal"));
		assertEquals(List.of("goal_continuation", "delegation", "idle_think", "tool_queue_review", "evaluation"),
			WakeRef.render(triggers).stream().map(entry -> entry.get("reason")).toList());
	}
}
