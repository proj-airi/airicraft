package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.attention.AttentionDecision;
import ai.moeru.airicraft.agent.attention.AttentionDecisionLog;
import ai.moeru.airicraft.agent.attention.AttentionOutcome;
import ai.moeru.airicraft.agent.attention.AttentionPolicy;
import ai.moeru.airicraft.agent.attention.Delivery;
import ai.moeru.airicraft.agent.attention.WakeDecision;
import ai.moeru.airicraft.agent.debug.AgentDebugRecorder;
import ai.moeru.airicraft.agent.llm.PlannerTrigger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentEventPipeline {
	@FunctionalInterface
	public interface TriggerFactory {
		PlannerTrigger create(SemanticEvent event, EventRoutingProfile profile);
	}

	@FunctionalInterface
	public interface DefaultPolicyResolver {
		EventPolicyDecision resolve(SemanticEvent event, EventRoutingProfile profile);
	}

	private final EventView rawEventBuffer;
	private final AgentEventLog rawEventLog;
	private final AgentEventBus rawPublisher;
	private final SemanticEventBuffer plannerEventBuffer;
	private final EventPolicyState policyState;
	private final Map<String, EventRoutingProfile> routingProfiles;
	private final AgentDebugRecorder debugRecorder;
	private final AttentionPolicy attentionPolicy;
	private final AttentionDecisionLog attentionLog;
	private long lastProcessedRawSeqNo;
	private boolean plannerEnabled = true;

	public AgentEventPipeline(
		AgentEventLog rawEventLog,
		AgentEventBus rawPublisher,
		SemanticEventBuffer plannerEventBuffer,
		EventPolicyState policyState,
		Map<String, EventRoutingProfile> routingProfiles
	) {
		this(rawEventLog, rawPublisher, plannerEventBuffer, policyState, routingProfiles, new AgentDebugRecorder(), (event, profile) -> EventPolicyDecision.allow());
	}

	public AgentEventPipeline(
		AgentEventLog rawEventLog,
		AgentEventBus rawPublisher,
		SemanticEventBuffer plannerEventBuffer,
		EventPolicyState policyState,
		Map<String, EventRoutingProfile> routingProfiles,
		AgentDebugRecorder debugRecorder
	) {
		this(rawEventLog, rawPublisher, plannerEventBuffer, policyState, routingProfiles, debugRecorder, (event, profile) -> EventPolicyDecision.allow());
	}

	public AgentEventPipeline(
		AgentEventLog rawEventLog,
		AgentEventBus rawPublisher,
		SemanticEventBuffer plannerEventBuffer,
		EventPolicyState policyState,
		Map<String, EventRoutingProfile> routingProfiles,
		AgentDebugRecorder debugRecorder,
		DefaultPolicyResolver defaultPolicyResolver
	) {
		this(rawEventLog, rawPublisher, plannerEventBuffer, policyState, routingProfiles, debugRecorder,
			AttentionPolicy.routingOnly(Objects.requireNonNull(defaultPolicyResolver, "defaultPolicyResolver")::resolve),
			new AttentionDecisionLog());
	}

	public AgentEventPipeline(
		AgentEventLog rawEventLog,
		AgentEventBus rawPublisher,
		SemanticEventBuffer plannerEventBuffer,
		EventPolicyState policyState,
		Map<String, EventRoutingProfile> routingProfiles,
		AgentDebugRecorder debugRecorder,
		AttentionPolicy attentionPolicy,
		AttentionDecisionLog attentionLog
	) {
		this.rawEventLog = Objects.requireNonNull(rawEventLog, "rawEventLog");
		this.rawEventBuffer = rawEventLog;
		this.rawPublisher = Objects.requireNonNull(rawPublisher, "rawPublisher");
		this.plannerEventBuffer = Objects.requireNonNull(plannerEventBuffer, "plannerEventBuffer");
		this.policyState = Objects.requireNonNull(policyState, "policyState");
		this.routingProfiles = Map.copyOf(Objects.requireNonNull(routingProfiles, "routingProfiles"));
		this.debugRecorder = Objects.requireNonNull(debugRecorder, "debugRecorder");
		this.attentionPolicy = Objects.requireNonNull(attentionPolicy, "attentionPolicy");
		this.attentionLog = Objects.requireNonNull(attentionLog, "attentionLog");
	}

	public SemanticEventBuffer plannerEventBuffer() {
		return plannerEventBuffer;
	}

	public AttentionDecisionLog attentionLog() {
		return attentionLog;
	}

	public EventPolicyState policyState() {
		return policyState;
	}

	public void clear() {
		rawEventLog.clear();
		plannerEventBuffer.clear();
		policyState.clear();
		lastProcessedRawSeqNo = 0L;
		recordBufferState();
	}

	/**
	 * Clears shutdown state while preserving raw sequence monotonicity for
	 * asynchronous terminal evidence emitted by the retiring runtime.
	 */
	public void clearForShutdown() {
		rawEventLog.clearPreservingSequence();
		plannerEventBuffer.clear();
		policyState.clear();
		lastProcessedRawSeqNo = 0L;
		recordBufferState();
	}

	public void clearPlannerFeed() {
		plannerEventBuffer.clear();
		lastProcessedRawSeqNo = rawEventBuffer.latestSeqNo();
		recordBufferState();
	}

	public void setPlannerEnabled(boolean enabled) {
		plannerEnabled = enabled;
		if (!enabled) {
			plannerEventBuffer.clearPreservingSequence();
			lastProcessedRawSeqNo = rawEventBuffer.latestSeqNo();
			recordBufferState();
		}
	}

	public List<PlannerTrigger> drain(TriggerFactory triggerFactory) {
		Objects.requireNonNull(triggerFactory, "triggerFactory");
		ArrayList<PlannerTrigger> triggers = new ArrayList<>();
		while (true) {
			SemanticEventQueryResult queryResult = rawEventBuffer.query(lastProcessedRawSeqNo <= 0L ? null : lastProcessedRawSeqNo);
			if (queryResult.events().isEmpty()) {
				return List.copyOf(triggers);
			}
			for (SemanticEvent event : queryResult.events()) {
				lastProcessedRawSeqNo = event.seqNo();
				triggers.addAll(route(event, triggerFactory));
			}
		}
	}

	private List<PlannerTrigger> route(SemanticEvent event, TriggerFactory triggerFactory) {
		EventRoutingProfile profile = routingProfiles.getOrDefault(event.type(), EventRoutingProfile.rawOnly(event.type()));
		if (!profile.semanticEligible() && !profile.triggerEligible()) {
			attentionLog.record(new AttentionDecision(event.seqNo(), event.tick(), event.type(), false, Delivery.NONE,
				ai.moeru.airicraft.agent.attention.Urgency.LOW, ai.moeru.airicraft.agent.attention.AttentionStage.RULES,
				"catalog.raw_only", "", false));
			recordBufferState();
			return List.of();
		}

		AttentionOutcome outcome = attentionPolicy.decide(event, profile, policyState, plannerEnabled);
		policyState.recordEvaluation(new EventPolicyState.RuleMatch(outcome.ruleMatchIndex(), outcome.ruleMatch()), event.timestampMs());
		EventPolicyDecision decision = outcome.policy();
		if (decision.intervened()) {
			EventPolicyIntervention intervention = new EventPolicyIntervention(
				event.seqNo(),
				event.type(),
				decision.effect(),
				decision.matchedRuleId(),
				decision.reason(),
				event.timestampMs()
			);
			policyState.recordIntervention(intervention);
			LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
			payload.put("sourceEventSeqNo", event.seqNo());
			payload.put("sourceEventType", event.type());
			payload.put("effect", decision.effect().name());
			if (decision.matchedRuleId() != null) {
				payload.put("matchedRuleId", decision.matchedRuleId());
			}
			if (decision.reason() != null) {
				payload.put("reason", decision.reason());
			}
			rawPublisher.publish(event.tick(), event.timestampMs(), "policy.event_intervened", payload,
				"AgentEventPipeline", EventCause.event(event.seqNo()));
		}

		boolean emitSemantic = outcome.emitSemantic();
		if (emitSemantic) {
			plannerEventBuffer.append(event.tick(), event.timestampMs(), event.type(), event.payload(), event.source(), event.cause());
		}

		WakeDecision wake = outcome.wake();
		PlannerTrigger trigger = wake.wakes() ? triggerFactory.create(event, profile) : null;
		attentionLog.record(new AttentionDecision(event.seqNo(), event.tick(), event.type(), emitSemantic, wake.delivery(),
			wake.urgency(), wake.stage(), wake.ruleId(), wake.wakes() && trigger == null ? "invalid_payload" : wake.reason(),
			trigger != null));
		debugRecorder.recordEventRouting(
			event.tick(),
			event.timestampMs(),
			event.seqNo(),
			event.type(),
			decision.effect().name(),
			emitSemantic,
			trigger != null,
			plannerEventBuffer.latestSeqNo(),
			trigger == null || trigger.type() == null ? null : trigger.type().name()
		);
		recordBufferState();
		if (trigger == null) {
			return List.of();
		}
		return List.of(trigger);
	}

	private void recordBufferState() {
		debugRecorder.updateEventPipelineBufferState(
			rawEventBuffer.latestSeqNo(),
			plannerEventBuffer.latestSeqNo(),
			rawEventBuffer.droppedCount(),
			plannerEventBuffer.droppedCount(),
			lastProcessedRawSeqNo
		);
	}
}
