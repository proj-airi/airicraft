package ai.moeru.airicraft.agent.attention;

import ai.moeru.airicraft.agent.events.EventPolicyDecision;
import ai.moeru.airicraft.agent.events.EventPolicyEffect;
import ai.moeru.airicraft.agent.events.EventPolicyState;
import ai.moeru.airicraft.agent.events.EventRoutingProfile;
import ai.moeru.airicraft.agent.events.SemanticEvent;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Java attention policy. It reproduces the decisions that were spread over the routing table (G1),
 * planner-authored rules and the default mining rule (G2), and the trigger factories' early returns (G3):
 * <ul>
 *   <li>Stage A, the constitution: evaluation suppression, direct chat and the safety handoff;</li>
 *   <li>Stage B, the reference judgement a rule module must reproduce by default;</li>
 * </ul>
 * It is pure: it reads a state snapshot and per-event evidence, and never changes runtime state.
 */
public final class ReferenceAttentionPolicy implements AttentionPolicy {
	/** Autonomous types that stay quiet after an evaluation turns the planner's own initiative off. */
	public static final Set<String> EVALUATION_SUPPRESSED = Set.of(
		"social.player_spoke", "social.system_message", "pickup.item_picked_up", "social.item_offered",
		"crafting.item_crafted", "combat.damage_taken", "player.physical", "smelting.output_ready",
		"task.blocked", "action_graph.goal_suspended", "action_graph.goal_terminal");

	private final Supplier<AttentionState> state;
	private final Function<SemanticEvent, AttentionEvidence> evidence;

	public ReferenceAttentionPolicy(Supplier<AttentionState> state, Function<SemanticEvent, AttentionEvidence> evidence) {
		this.state = Objects.requireNonNull(state, "state");
		this.evidence = Objects.requireNonNull(evidence, "evidence");
	}

	@Override
	public AttentionOutcome decide(SemanticEvent event, EventRoutingProfile profile, EventPolicyState rules, boolean plannerEnabled) {
		return decide(state.get(), evidence.apply(event), event, profile, rules, plannerEnabled);
	}

	/** The reference decision for one event with an explicit state snapshot and evidence. */
	public static AttentionOutcome decide(AttentionState state, AttentionEvidence evidence, SemanticEvent event,
		EventRoutingProfile profile, EventPolicyState rules, boolean plannerEnabled) {
		AttentionOutcome routed = AttentionPolicy.route(event, profile, rules, plannerEnabled, defaultRule(event, state))
			.withInputs(state, evidence);
		if (!routed.wake().wakes()) return routed;
		return routed.withWake(gate(event, state, evidence));
	}

	/** Types the constitution decides entirely; rule modules never see them. */
	public static final Set<String> CONSTITUTION_TYPES = Set.of(
		"social.player_addressed_agent", "social.local_controller_spoke", "social.airi_commanded", "reflex.resolved");

	/** The bundled default planner rule: pickups are progress owned by an active mining job. */
	public static EventPolicyDecision defaultRule(SemanticEvent event, AttentionState state) {
		if (event == null || !"pickup.item_picked_up".equals(event.type())) return EventPolicyDecision.allow();
		if (state.activeJobType() == null || state.activeJobIdle() || state.activeJobTerminal()) return EventPolicyDecision.allow();
		if (!"MINE_BLOCKS".equals(state.activeJobType()) && !"ENSURE_BLOCKS_IN_INVENTORY".equals(state.activeJobType())) {
			return EventPolicyDecision.allow();
		}
		return new EventPolicyDecision(EventPolicyEffect.SEMANTIC_ONLY, "default-mining-pickup-semantic-only",
			"pickup progress is owned by the active mining job", false);
	}

	/**
	 * Whether a trigger-eligible event, after routing and planner rules, may wake the planner. Payload validity
	 * stays with the trigger factory; this decides attention only.
	 */
	public static WakeDecision gate(SemanticEvent event, AttentionState state, AttentionEvidence evidence) {
		String type = event.type();
		if (state.evaluationSuppressed() && EVALUATION_SUPPRESSED.contains(type)) {
			return WakeDecision.none(AttentionStage.CONSTITUTION, "constitution.evaluation_suppressed",
				"autonomous wakes are suppressed after an evaluation");
		}
		return switch (type) {
			case "social.player_addressed_agent" -> {
				if (evidence.resetCommand()) yield WakeDecision.none(AttentionStage.CONSTITUTION, "constitution.reset_command",
					"reset commands are handled before the planner");
				if (!evidence.senderWithinChatDistance()) yield WakeDecision.none(AttentionStage.CONSTITUTION,
					"constitution.chat_distance", "sender is outside the configured chat distance");
				yield WakeDecision.immediate(Urgency.DIRECT, AttentionStage.CONSTITUTION, "constitution.direct_chat", "");
			}
			case "social.local_controller_spoke" -> evidence.resetCommand()
				? WakeDecision.none(AttentionStage.CONSTITUTION, "constitution.reset_command", "reset commands are handled before the planner")
				: WakeDecision.immediate(Urgency.DIRECT, AttentionStage.CONSTITUTION, "constitution.direct_chat", "");
			case "social.airi_commanded" -> WakeDecision.immediate(Urgency.DIRECT, AttentionStage.CONSTITUTION,
				"constitution.airi_command", "");
			case "reflex.resolved" -> WakeDecision.immediate(Urgency.CRITICAL, AttentionStage.CONSTITUTION,
				"constitution.safety_handoff", "");
			case "social.player_spoke" -> {
				if (evidence.addressedToAgent()) yield WakeDecision.none(AttentionStage.RULES, "chat.addressed_routed_separately",
					"addressed chat wakes through social.player_addressed_agent");
				if (!state.proactiveSocialMode()) yield WakeDecision.none(AttentionStage.RULES, "chat.proactive_mode_off",
					"ambient chat wakes only in proactive social mode");
				if (!evidence.senderWithinChatDistance()) yield WakeDecision.none(AttentionStage.RULES, "chat.distance",
					"sender is outside the configured chat distance");
				yield WakeDecision.immediate(Urgency.LOW, AttentionStage.RULES, "chat.ambient", "");
			}
			case "social.system_message" -> state.proactiveSocialMode()
				? WakeDecision.immediate(Urgency.LOW, AttentionStage.RULES, "chat.system_message", "")
				: WakeDecision.none(AttentionStage.RULES, "chat.proactive_mode_off", "system messages wake only in proactive social mode");
			case "pickup.item_picked_up" -> state.activeJobRunning("COLLECT_RESOURCE")
				? WakeDecision.none(AttentionStage.RULES, "ownership.collect_resource_progress", "the collect-resource job owns pickups")
				: WakeDecision.immediate(Urgency.LOW, AttentionStage.RULES, "catalog.trigger", "");
			case "crafting.item_crafted" -> {
				if (state.activeJobRunning("COLLECT_RESOURCE")) yield WakeDecision.none(AttentionStage.RULES,
					"ownership.collect_resource_progress", "the collect-resource job owns crafts");
				if (state.pendingCraftToolResult()) yield WakeDecision.none(AttentionStage.RULES, "ownership.pending_craft_result",
					"a craft tool call is waiting for this result");
				yield WakeDecision.immediate(Urgency.LOW, AttentionStage.RULES, "catalog.trigger", "");
			}
			case "combat.damage_taken" -> state.reflexOwnsActuation()
				? WakeDecision.none(AttentionStage.RULES, "ownership.reflex_actuation", "the survival reflex owns actuation")
				: WakeDecision.immediate(Urgency.LOW, AttentionStage.RULES, "catalog.trigger", "");
			case "player.physical" -> state.reflexOwnsActuation()
				? WakeDecision.none(AttentionStage.RULES, "ownership.reflex_actuation", "the survival reflex owns actuation")
				: WakeDecision.immediate(Urgency.NORMAL, AttentionStage.RULES, "catalog.trigger", "");
			case "social.item_offered" -> WakeDecision.immediate(Urgency.NORMAL, AttentionStage.RULES, "catalog.trigger", "");
			case "action_graph.goal_terminal" -> "FAILED".equals(stringValue(event.payload().get("state")))
				? WakeDecision.immediate(Urgency.HIGH, AttentionStage.RULES, "catalog.trigger", "")
				: WakeDecision.none(AttentionStage.RULES, "graph.terminal_not_failed", "only failed graph outcomes wake");
			case "smelting.output_ready", "task.blocked", "action_graph.goal_suspended" ->
				WakeDecision.immediate(Urgency.HIGH, AttentionStage.RULES, "catalog.trigger", "");
			case "perception.block_noticed", "perception.item_noticed", "perception.entity_noticed", "perception.entity_lost" ->
				state.activeJobOwns(event.payload().get("blockId")) || state.activeJobOwns(event.payload().get("itemId"))
					? WakeDecision.none(AttentionStage.RULES, "ownership.active_job_target", "the running job is working on this")
					: WakeDecision.debounce(Urgency.LOW, AttentionStage.RULES, "percept.notice", "");
			case "perception.environment_changed" -> "dusk".equals(stringValue(event.payload().get("change"))) && state.idleForNotices()
				? WakeDecision.debounce(Urgency.LOW, AttentionStage.RULES, "percept.dusk_idle", "")
				: WakeDecision.none(AttentionStage.RULES, "percept.environment_evidence",
					"environment changes are evidence; only dusk wakes, and only while idle");
			default -> WakeDecision.none(AttentionStage.RULES, "catalog.no_trigger", "no trigger is defined for this type");
		};
	}

	private static String stringValue(Object value) {
		return value == null ? null : String.valueOf(value);
	}
}
