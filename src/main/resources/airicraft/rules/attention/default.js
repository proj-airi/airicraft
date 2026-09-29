// Bundled attention rules (Stage B). They decide, per routed event, whether it feeds the planner's semantic
// input and whether it may wake the planner. The Java constitution decides direct chat, reset commands, the
// reflex safety handoff and evaluation suppression before and after this module; the clamp keeps protected
// types from being silenced. These rules must decide exactly like the Java ReferenceAttentionPolicy.
//
// input:  {tick, seed, attention: {proactiveSocialMode, reflexOwnsActuation, activeJobType, activeJobIdle,
//          activeJobTerminal, pendingCraftToolResult, activeJobTargets}, plannerRules: [{index, ruleId, effect, reason, match}],
//          events: [{seqNo, type, fields, profile: {semantic, trigger, bypass}, plannerEnabled, evidence}]}
// output: {decisions: [{seqNo, emitSemantic, ruleMatch, policy, wake: {delivery, urgency, ruleId, reason}}], state}
(lib => {
  const MATCH_KEYS = ['player', 'speaker', 'actor', 'itemId', 'damageTypeId', 'attackerName'];
  const ALLOW = {effect: 'ALLOW', ruleIndex: -1, ruleId: null, reason: null, bypassed: false};

  function payloadString(event, key) {
    const direct = event.fields[key];
    if (direct != null) return direct;
    if (key === 'speaker') {
      if (event.fields.player != null) return event.fields.player;
      if (event.fields.actor != null) return event.fields.actor;
      if (event.type === 'social.system_message') return 'server';
    }
    return null;
  }

  function matches(match, event) {
    if (match.eventType == null || match.eventType !== event.type) return false;
    return MATCH_KEYS.every(key => match[key] == null || match[key] === payloadString(event, key));
  }

  // Planner-authored rules (update_event_policy): the latest matching rule wins.
  function plannerRule(event, rules) {
    if (event.profile.bypass) return {effect: 'ALLOW', ruleIndex: -1, ruleId: null, reason: null, bypassed: true};
    for (let index = rules.length - 1; index >= 0; index--) {
      const rule = rules[index];
      if (matches(rule.match, event)) {
        return {effect: rule.effect, ruleIndex: index, ruleId: rule.ruleId, reason: rule.reason, bypassed: false};
      }
    }
    return ALLOW;
  }

  // The bundled default rule: pickups are progress owned by an active mining job.
  function defaultRule(event, attention) {
    if (event.type !== 'pickup.item_picked_up') return ALLOW;
    if (attention.activeJobType == null || attention.activeJobIdle || attention.activeJobTerminal) return ALLOW;
    if (attention.activeJobType !== 'MINE_BLOCKS' && attention.activeJobType !== 'ENSURE_BLOCKS_IN_INVENTORY') return ALLOW;
    return {effect: 'SEMANTIC_ONLY', ruleIndex: -1, ruleId: 'default-mining-pickup-semantic-only',
      reason: 'pickup progress is owned by the active mining job', bypassed: false};
  }

  const none = (ruleId, reason) => ({delivery: 'NONE', urgency: 'LOW', ruleId, reason});
  const wake = (urgency, ruleId) => ({delivery: 'IMMEDIATE', urgency, ruleId, reason: ''});
  const debounce = (urgency, ruleId) => ({delivery: 'DEBOUNCE', urgency, ruleId, reason: ''});
  const running = (attention, type) => attention.activeJobType === type && !attention.activeJobTerminal;
  // A running job works on this block or item: a percept about exactly that work belongs to the job.
  const owns = (attention, id) => attention.activeJobType != null && !attention.activeJobTerminal && id != null
    && (attention.activeJobTargets || []).includes(String(id));
  const idleForNotices = attention => attention.activeJobType == null || attention.activeJobIdle;

  // Ownership and social rules for a trigger-eligible event (the trigger factories' former early returns).
  function gate(event, attention) {
    const evidence = event.evidence;
    switch (event.type) {
      case 'social.player_spoke':
        if (evidence.addressedToAgent) return none('chat.addressed_routed_separately', 'addressed chat wakes through social.player_addressed_agent');
        if (!attention.proactiveSocialMode) return none('chat.proactive_mode_off', 'ambient chat wakes only in proactive social mode');
        if (!evidence.senderWithinChatDistance) return none('chat.distance', 'sender is outside the configured chat distance');
        return wake('LOW', 'chat.ambient');
      case 'social.system_message':
        return attention.proactiveSocialMode ? wake('LOW', 'chat.system_message')
          : none('chat.proactive_mode_off', 'system messages wake only in proactive social mode');
      case 'pickup.item_picked_up':
        return running(attention, 'COLLECT_RESOURCE')
          ? none('ownership.collect_resource_progress', 'the collect-resource job owns pickups') : wake('LOW', 'catalog.trigger');
      case 'crafting.item_crafted':
        if (running(attention, 'COLLECT_RESOURCE')) return none('ownership.collect_resource_progress', 'the collect-resource job owns crafts');
        if (attention.pendingCraftToolResult) return none('ownership.pending_craft_result', 'a craft tool call is waiting for this result');
        return wake('LOW', 'catalog.trigger');
      case 'combat.damage_taken':
        return attention.reflexOwnsActuation ? none('ownership.reflex_actuation', 'the survival reflex owns actuation') : wake('LOW', 'catalog.trigger');
      case 'player.physical':
        return attention.reflexOwnsActuation ? none('ownership.reflex_actuation', 'the survival reflex owns actuation') : wake('NORMAL', 'catalog.trigger');
      case 'social.item_offered':
        return wake('NORMAL', 'catalog.trigger');
      case 'action_graph.goal_terminal':
        return event.fields.state === 'FAILED' ? wake('HIGH', 'catalog.trigger') : none('graph.terminal_not_failed', 'only failed graph outcomes wake');
      case 'smelting.output_ready':
      case 'task.blocked':
      case 'action_graph.goal_suspended':
        return wake('HIGH', 'catalog.trigger');
      case 'perception.block_noticed':
      case 'perception.item_noticed':
      case 'perception.entity_noticed':
      case 'perception.entity_lost':
        return owns(attention, event.fields.blockId) || owns(attention, event.fields.itemId)
          ? none('ownership.active_job_target', 'the running job is working on this') : debounce('LOW', 'percept.notice');
      case 'perception.environment_changed':
        return event.fields.change === 'dusk' && idleForNotices(attention) ? debounce('LOW', 'percept.dusk_idle')
          : none('percept.environment_evidence', 'environment changes are evidence; only dusk wakes, and only while idle');
      default:
        return none('catalog.no_trigger', 'no trigger is defined for this type');
    }
  }

  function decide(event, input) {
    const ruleMatch = plannerRule(event, input.plannerRules);
    let policy = ruleMatch;
    // As in the Java reference: a rule without an id does not shadow the default rule.
    if (!policy.bypassed && policy.ruleId == null) policy = defaultRule(event, input.attention);
    let emitSemantic = event.plannerEnabled && event.profile.semantic;
    let emitTrigger = event.profile.trigger;
    if (policy.effect === 'IGNORE') { emitSemantic = false; emitTrigger = false; }
    else if (policy.effect === 'SEMANTIC_ONLY') emitTrigger = false;
    else if (policy.effect === 'TRIGGER_ONLY') emitSemantic = false;
    const intervened = !policy.bypassed && policy.effect !== 'ALLOW';
    const routeRuleId = intervened ? (policy.ruleId == null ? 'planner_rule' : policy.ruleId)
      : event.profile.trigger ? 'catalog.trigger' : 'catalog.semantic';
    const routeReason = intervened ? policy.effect.toLowerCase() : '';
    const decision = emitTrigger ? gate(event, input.attention) : none(routeRuleId, routeReason);
    return {seqNo: event.seqNo, emitSemantic, ruleMatch, policy, wake: decision};
  }

  return {
    step(input, state) {
      return {decisions: input.events.map(event => decide(event, input)), state};
    }
  };
})
