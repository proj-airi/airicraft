package ai.moeru.airicraft.agent.llm;

import ai.moeru.airicraft.agent.llm.delegation.PlannerDelegation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlannerDelegationPresentationTest {
	@Test void delegatedSystemTriggersKeepIdentityInFieldsAndShortenOnlyTheModelView() {
		var delegation = new PlannerDelegation();
		delegation.delegate("mine iron", "obtain one ingot", "avoid lava");
		var assignment = delegation.startPrompt(Map.of("health", 19.375), 0);
		String id = delegation.id();
		assertEquals(id, assignment.fields().get("delegationId").getAsString());
		var trigger = PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "controller", assignment.text(), 1, 2, "delegation", assignment.fields());
		var message = PlannerTriggerBatch.of(List.of(trigger)).toObservedMessages().getFirst();
		assertEquals(id, message.fields().getAsJsonObject().get("delegationId").getAsString());
		var refs = new PlannerReferences();
		String presented = refs.presentMessages(LlmConversation.of(List.of(message))).get(0).getAsJsonObject().get("content").getAsString();
		assertTrue(presented.contains(refs.present(id)));
		assertFalse(presented.contains(id));
		assertTrue(presented.contains("19.4"));
		assertEquals(id, message.fields().getAsJsonObject().get("delegationId").getAsString());

		var continuation = delegation.continuationPrompt();
		assertEquals(id, continuation.fields().get("delegationId").getAsString());
		var next = PlannerTrigger.autonomous(PlannerTriggerType.SYSTEM, "self", continuation.text(), 2, 3, "planner_goal", continuation.fields());
		String nextPresented = refs.presentMessages(LlmConversation.of(PlannerTriggerBatch.of(List.of(next)).toObservedMessages()))
			.get(0).getAsJsonObject().get("content").getAsString();
		assertTrue(nextPresented.contains(refs.present(id)));
		assertFalse(nextPresented.contains(id));
	}
}
