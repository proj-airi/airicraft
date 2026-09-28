package ai.moeru.airicraft.agent.attention;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AttentionDecisionLogTest {
	@Test void evictsOldestAndReportsTruncation() {
		var log = new AttentionDecisionLog(2);
		for (long seq = 1; seq <= 3; seq++) log.record(decision(seq, seq == 2 ? "catalog.trigger" : "catalog.raw_only"));
		assertEquals(List.of(2L, 3L), log.query(null).decisions().stream().map(AttentionDecision::seqNo).toList());
		assertFalse(log.query(null).truncated());
		assertTrue(log.query(0L).truncated(), "decision 1 was evicted");
		assertFalse(log.query(1L).truncated());
		assertEquals(List.of(3L), log.query(2L).decisions().stream().map(AttentionDecision::seqNo).toList());
		var state = log.debugState(1);
		assertEquals(3L, state.get("recorded"));
		assertEquals(1L, state.get("dropped"));
		assertEquals(Map.of("catalog.raw_only", 2L, "catalog.trigger", 1L), state.get("countsByRule"));
		assertEquals(1, ((List<?>) state.get("latest")).size());
	}

	private static AttentionDecision decision(long seq, String ruleId) {
		return new AttentionDecision(seq, seq, "task.notice", false, Delivery.NONE, Urgency.LOW, AttentionStage.RULES, ruleId, "", false);
	}
}
