package ai.moeru.airicraft.agent.events;

import ai.moeru.airicraft.agent.llm.PlannerTriggerType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Declared event types and their current producers, observation visibility, and G1 routing. */
public final class EventCatalog {
	private static final EventCatalog DEFAULTS = new EventCatalog(List.of(
			// action_graph
			new EventTypeSpec("action_graph.goal_admission", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("action_graph.goal_admission")),
			new EventTypeSpec("action_graph.goal_cancelled", false, EventFamily.EXECUTION, Set.of("ActionGraphCoordinator"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("action_graph.goal_cancelled")),
			new EventTypeSpec("action_graph.goal_resumed", false, EventFamily.EXECUTION, Set.of("ActionGraphCoordinator"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("action_graph.goal_resumed")),
			new EventTypeSpec("action_graph.goal_runnable", false, EventFamily.EXECUTION, Set.of("ActionGraphCoordinator"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("action_graph.goal_runnable")),
			new EventTypeSpec("action_graph.goal_started", false, EventFamily.EXECUTION, Set.of("ActionGraphCoordinator"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("action_graph.goal_started")),
			new EventTypeSpec("action_graph.goal_suspended", false, EventFamily.EXECUTION, Set.of("ActionGraphCoordinator", "EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("action_graph.goal_suspended", true, PlannerTriggerType.SYSTEM, true)),
			new EventTypeSpec("action_graph.goal_terminal", false, EventFamily.EXECUTION, Set.of("ActionGraphCoordinator", "EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("action_graph.goal_terminal", true, PlannerTriggerType.SYSTEM, true)),

			// combat
			new EventTypeSpec("combat.damage_taken", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("combat.damage_taken", true, PlannerTriggerType.DAMAGE, false)),

			// crafting
			new EventTypeSpec("crafting.item_crafted", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("crafting.item_crafted", true, PlannerTriggerType.CRAFT, false)),

			// follow
			new EventTypeSpec("follow.stuck", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("follow.stuck", true, null, false)),
			new EventTypeSpec("follow.target_acquired", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime", "FollowCapability"), EventVisibility.PLANNER, new EventRoutingProfile("follow.target_acquired", true, null, false)),
			new EventTypeSpec("follow.target_lost", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime", "FollowCapability"), EventVisibility.PLANNER, new EventRoutingProfile("follow.target_lost", true, null, false)),

			// food
			new EventTypeSpec("food.eat_failed", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("food.eat_failed")),
			new EventTypeSpec("food.eat_started", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("food.eat_started")),
			new EventTypeSpec("food.eaten", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("food.eaten")),
			new EventTypeSpec("food.unavailable", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("food.unavailable")),

			// lighting
			new EventTypeSpec("lighting.torch_placed", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("lighting.torch_placed", true, null, true)),

			// mission
			new EventTypeSpec("mission.submitted", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("mission.submitted")),

			// objective
			new EventTypeSpec("objective.changed", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("objective.changed")),

			// perception
			new EventTypeSpec("perception.block_noticed", false, EventFamily.PERCEPT, Set.of("SaliencePolicy"), EventVisibility.PLANNER, new EventRoutingProfile("perception.block_noticed", true, PlannerTriggerType.SYSTEM, false)),
			new EventTypeSpec("perception.entity_lost", false, EventFamily.PERCEPT, Set.of("SaliencePolicy"), EventVisibility.PLANNER, new EventRoutingProfile("perception.entity_lost", true, PlannerTriggerType.SYSTEM, false)),
			new EventTypeSpec("perception.entity_noticed", false, EventFamily.PERCEPT, Set.of("SaliencePolicy"), EventVisibility.PLANNER, new EventRoutingProfile("perception.entity_noticed", true, PlannerTriggerType.SYSTEM, false)),
			new EventTypeSpec("perception.environment_changed", false, EventFamily.PERCEPT, Set.of("SaliencePolicy"), EventVisibility.PLANNER, new EventRoutingProfile("perception.environment_changed", true, PlannerTriggerType.SYSTEM, false)),
			new EventTypeSpec("perception.item_noticed", false, EventFamily.PERCEPT, Set.of("SaliencePolicy"), EventVisibility.PLANNER, new EventRoutingProfile("perception.item_noticed", true, PlannerTriggerType.SYSTEM, false)),

			// pickup
			new EventTypeSpec("pickup.item_picked_up", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("pickup.item_picked_up", true, PlannerTriggerType.PICKUP, false)),

			// planner
			new EventTypeSpec("planner.degraded_blocked", false, EventFamily.INTERNAL, Set.of("DialogueCore"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("planner.degraded_blocked")),
			new EventTypeSpec("planner.degraded_cleared", false, EventFamily.INTERNAL, Set.of("DialogueCore"), EventVisibility.PLANNER, new EventRoutingProfile("planner.degraded_cleared", true, null, false)),
			new EventTypeSpec("planner.degraded_entered", false, EventFamily.INTERNAL, Set.of("DialogueCore"), EventVisibility.PLANNER, new EventRoutingProfile("planner.degraded_entered", true, null, false)),
			new EventTypeSpec("planner.goal_cleared", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("planner.goal_cleared", true, null, false)),
			new EventTypeSpec("planner.goal_set", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("planner.goal_set", true, null, false)),
			new EventTypeSpec("planner.internal_task_update_superseded", false, EventFamily.INTERNAL, Set.of("DialogueRuntime"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("planner.internal_task_update_superseded")),
			new EventTypeSpec("planner.parse_error", false, EventFamily.INTERNAL, Set.of("DialogueCore"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("planner.parse_error")),
			new EventTypeSpec("planner.provider_error", false, EventFamily.INTERNAL, Set.of("DialogueCore"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("planner.provider_error")),
			new EventTypeSpec("planner.reset_requested", false, EventFamily.INTERNAL, Set.of("DialogueCore"), EventVisibility.PLANNER, new EventRoutingProfile("planner.reset_requested", true, null, true)),
			new EventTypeSpec("planner.response_applied", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("planner.response_applied")),
			new EventTypeSpec("planner.stale_response_rejected", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("planner.stale_response_rejected", true, null, true)),
			new EventTypeSpec("planner.timeout", false, EventFamily.INTERNAL, Set.of("DialogueCore"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("planner.timeout")),
			new EventTypeSpec("planner.turn_preempted", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("planner.turn_preempted", true, null, true)),
			new EventTypeSpec("planner.unknown_intent", false, EventFamily.INTERNAL, Set.of("DialogueCore"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("planner.unknown_intent")),

			// player
			new EventTypeSpec("player.action_rejected", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("player.action_rejected", true, null, true)),
			new EventTypeSpec("player.actions_cancelled", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("player.actions_cancelled", true, null, true)),
			new EventTypeSpec("player.death_place_save_failed", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("player.death_place_save_failed")),
			new EventTypeSpec("player.death_place_saved", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("player.death_place_saved")),
			new EventTypeSpec("player.died", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime", "SessionRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("player.died", true, null, true)),
			new EventTypeSpec("player.physical", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("player.physical", true, PlannerTriggerType.SYSTEM, true)),
			new EventTypeSpec("player.respawn_request_failed", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("player.respawn_request_failed", true, null, true)),
			new EventTypeSpec("player.respawn_requested", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("player.respawn_requested", true, null, true)),
			new EventTypeSpec("player.respawned", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime", "SessionRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("player.respawned", true, null, true)),

			// policy
			new EventTypeSpec("policy.event_intervened", false, EventFamily.INTERNAL, Set.of("AgentEventPipeline", "EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("policy.event_intervened")),
			new EventTypeSpec("policy.rule_rejected", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("policy.rule_rejected")),
			new EventTypeSpec("policy.travel_changed", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("policy.travel_changed")),

			// reflex
			new EventTypeSpec("reflex.action_changed", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("reflex.action_changed", true, null, true)),
			new EventTypeSpec("reflex.actuator_failed", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("reflex.actuator_failed", true, null, true)),
			new EventTypeSpec("reflex.close_quarter_attack", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.close_quarter_attack")),
			new EventTypeSpec("reflex.combat_focus", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.combat_focus")),
			new EventTypeSpec("reflex.combat_progress", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.combat_progress")),
			new EventTypeSpec("reflex.combat_reposition", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.combat_reposition")),
			new EventTypeSpec("reflex.food_eat_failed", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.food_eat_failed")),
			new EventTypeSpec("reflex.food_eat_interrupted", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.food_eat_interrupted")),
			new EventTypeSpec("reflex.food_eat_started", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.food_eat_started")),
			new EventTypeSpec("reflex.food_retreat_failed", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.food_retreat_failed")),
			new EventTypeSpec("reflex.food_unavailable", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.food_unavailable")),
			new EventTypeSpec("reflex.hold_released", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("reflex.hold_released", true, null, true)),
			new EventTypeSpec("reflex.movement_recovery", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.movement_recovery")),
			new EventTypeSpec("reflex.policy_changed", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.policy_changed")),
			new EventTypeSpec("reflex.resolved", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("reflex.resolved", true, PlannerTriggerType.SYSTEM, true)),
			new EventTypeSpec("reflex.shield_lowered", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.shield_lowered")),
			new EventTypeSpec("reflex.shield_raised", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.shield_raised")),
			new EventTypeSpec("reflex.started", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("reflex.started", true, null, true)),
			new EventTypeSpec("reflex.task_resumed", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("reflex.task_resumed")),
			new EventTypeSpec("reflex.threat_detected", false, EventFamily.INTERNAL, Set.of("SurvivalReflexRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("reflex.threat_detected", true, null, true)),

			// rules
			new EventTypeSpec("rules.reverted", false, EventFamily.INTERNAL, Set.of("RuleAttentionPolicy", "SaliencePolicy"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("rules.reverted")),
			new EventTypeSpec("rules.step_failed", false, EventFamily.INTERNAL, Set.of("RuleAttentionPolicy", "SaliencePolicy"), EventVisibility.DIAGNOSTIC, EventRoutingProfile.rawOnly("rules.step_failed")),
			new EventTypeSpec("rules.updated", false, EventFamily.INTERNAL, Set.of("PlannerRules"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("rules.updated")),

			// session
			new EventTypeSpec("session.connection_lost", false, EventFamily.INTERNAL, Set.of("SessionRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("session.connection_lost", true, null, false)),
			new EventTypeSpec("session.lan_open_failed", false, EventFamily.INTERNAL, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("session.lan_open_failed")),
			new EventTypeSpec("session.lan_opened", false, EventFamily.INTERNAL, Set.of("SessionRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("session.lan_opened", true, null, false)),
			new EventTypeSpec("session.world_loaded", false, EventFamily.INTERNAL, Set.of("SessionRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("session.world_loaded", true, null, false)),
			new EventTypeSpec("session.world_unloaded", false, EventFamily.INTERNAL, Set.of("SessionRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("session.world_unloaded", true, null, false)),

			// smelting
			new EventTypeSpec("smelting.output_ready", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("smelting.output_ready", true, PlannerTriggerType.SYSTEM, true)),

			// social
			new EventTypeSpec("social.item_offered", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("social.item_offered", true, PlannerTriggerType.SYSTEM, false)),
			new EventTypeSpec("social.local_controller_spoke", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.DIAGNOSTIC, new EventRoutingProfile("social.local_controller_spoke", false, PlannerTriggerType.CHAT, true)),
			new EventTypeSpec("social.airi_commanded", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.DIAGNOSTIC, new EventRoutingProfile("social.airi_commanded", false, PlannerTriggerType.CHAT, true)),
			new EventTypeSpec("social.player_addressed_agent", false, EventFamily.PERCEPT, Set.of("ChatIngestService", "EmbodiedAgentRuntime"), EventVisibility.DIAGNOSTIC, new EventRoutingProfile("social.player_addressed_agent", false, PlannerTriggerType.CHAT, true)),
			new EventTypeSpec("social.player_joined_game", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("social.player_joined_game", true, null, false)),
			new EventTypeSpec("social.player_joined_nearby", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime", "NearbyPlayerTracker"), EventVisibility.PLANNER, new EventRoutingProfile("social.player_joined_nearby", true, null, false)),
			new EventTypeSpec("social.player_left_game", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("social.player_left_game", true, null, false)),
			new EventTypeSpec("social.player_left_nearby", false, EventFamily.PERCEPT, Set.of("EmbodiedAgentRuntime", "NearbyPlayerTracker"), EventVisibility.PLANNER, new EventRoutingProfile("social.player_left_nearby", true, null, false)),
			new EventTypeSpec("social.player_spoke", false, EventFamily.PERCEPT, Set.of("ChatIngestService", "EmbodiedAgentRuntime"), EventVisibility.DIAGNOSTIC, new EventRoutingProfile("social.player_spoke", false, PlannerTriggerType.CHAT, false)),
			new EventTypeSpec("social.system_message", false, EventFamily.PERCEPT, Set.of("ChatIngestService", "EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("social.system_message", false, PlannerTriggerType.SYSTEM, false)),

			// task
			new EventTypeSpec("task.blocked", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("task.blocked", true, PlannerTriggerType.SYSTEM, true)),
			new EventTypeSpec("task.cancelled", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("task.cancelled")),
			new EventTypeSpec("task.completed", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("task.completed")),
			new EventTypeSpec("task.failed", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("task.failed")),
			new EventTypeSpec("task.mining_opportunity", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, new EventRoutingProfile("task.mining_opportunity", true, null, true)),
			new EventTypeSpec("task.notice", false, EventFamily.EXECUTION, Set.of("DialogueRuntime", "EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("task.notice")),
			new EventTypeSpec("task.paused_by_reflex", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("task.paused_by_reflex")),
			new EventTypeSpec("task.paused_by_session_gate", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("task.paused_by_session_gate")),
			new EventTypeSpec("task.started", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("task.started")),
			new EventTypeSpec("task.submitted", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("task.submitted")),

			// work
			new EventTypeSpec("work.changed", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("work.changed")),
			new EventTypeSpec("work.travel_restriction_violated", false, EventFamily.EXECUTION, Set.of("EmbodiedAgentRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("work.travel_restriction_violated")),

			// interaction
			new EventTypeSpec("interaction.", true, EventFamily.EXECUTION, Set.of("InteractionLogbookRecorder"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("interaction.")),

			// policy
			new EventTypeSpec("policy.continuation.", true, EventFamily.INTERNAL, Set.of("DialogueRuntime"), EventVisibility.PLANNER, EventRoutingProfile.rawOnly("policy.continuation."))
	));

	private final List<EventTypeSpec> specs;
	private final Map<String, EventTypeSpec> exact;
	private final List<EventTypeSpec> prefixes;

	public EventCatalog(List<EventTypeSpec> specs) {
		this.specs = List.copyOf(specs);
		var exact = new HashMap<String, EventTypeSpec>();
		for (var spec : specs) if (!spec.prefix()) exact.put(spec.id(), spec);
		this.exact = Map.copyOf(exact);
		this.prefixes = specs.stream().filter(EventTypeSpec::prefix).toList();
	}

	public static EventCatalog defaults() {
		return DEFAULTS;
	}

	public List<EventTypeSpec> specs() {
		return specs;
	}

	public EventTypeSpec find(String type) {
		var match = exact.get(type);
		if (match != null) return match;
		for (var prefix : prefixes) {
			if (type.startsWith(prefix.id()) && (match == null || prefix.id().length() > match.id().length())) match = prefix;
		}
		return match;
	}

	/** Whether {@code type} may appear in {@code observe.events}; undeclared types never do. */
	public boolean plannerVisible(String type) {
		var spec = type == null ? null : find(type);
		return spec != null && spec.observeVisibility() == EventVisibility.PLANNER;
	}

	/** G1 entries. Raw-only types are omitted: the pipeline and the policy-bypass check both default to raw-only. */
	public Map<String, EventRoutingProfile> routingProfiles() {
		var profiles = new HashMap<String, EventRoutingProfile>();
		for (var spec : specs) {
			if (!spec.prefix() && !spec.routing().equals(EventRoutingProfile.rawOnly(spec.id()))) {
				profiles.put(spec.id(), spec.routing());
			}
		}
		return Map.copyOf(profiles);
	}
}
