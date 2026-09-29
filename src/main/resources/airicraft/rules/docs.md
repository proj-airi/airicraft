# Your attention rules

Two JavaScript modules decide what reaches you. You may read and replace them with `inspect_rules` and `update_rules`.
Each edit is checked first by replaying recent history through the old and the new module, and the result tells you
what your edit would have changed. Edits last for this session only (a reload of the game's config discards them).

- **attention** decides, for each event that reaches the rules, whether it may wake you and how urgently. Events you
  are not woken for are still visible in `observe.events`; only the wake is withheld.
- **salience** decides which things you could perceive (blocks with an exposed face, dropped items, nearby entities,
  the weather and time of day) become percepts (`perception.*` events) and which are dropped.

## What you cannot change

Java decides these before and after your module, and no rule can reach them:

- **The constitution.** Chat addressed to you, reset commands and the survival reflex hand-off never reach the
  rules. Autonomous wakes stay suppressed after an evaluation.
- **The clamp.** Event types the catalog marks as protected keep their wakes: a module that silences one gets it back
  (stage `CLAMP`). Protected types are listed at the end of this page.
- **Timing.** When a wake is delivered, preemption of a stale turn, the supersede budget and the limits on pending
  wakes are Java.

Mute what is noise for the work in hand, and never what protects the player. Prefer `update_event_policy` when one
event type is the whole problem: it needs no code.

## Working with an edit

1. `inspect_rules` with `hook` shows the running source, its history, engine health and the rule ids of recent
   decisions. Start from that source; a whole module must be sent, not a patch.
2. `update_rules` with `hook`, `source` and a `reason` (what you are tuning and why). The result carries the replay:
   how many recent events or steps were replayed, how many changed, the wakes or percepts gained and lost by type, and
   examples. **Read it.** A module that fails to load, throws or returns a malformed decision is rejected, and nothing
   changes.
3. The next `observe` shows `rules.updated`. If wakes you needed disappear, use `update_rules` with `revert_to` (a
   version number from `inspect_rules`, or `base` for the original rules) and a reason.
4. A module that fails 3 steps in a row is reverted for you, to the version it replaced, and the next `observe`
   shows `rules.reverted` with the reason.

Limits: at most 6 accepted edits per hook per 10 minutes; 32,768 characters of source; the latest 8 versions are
kept; edits are refused while a safety hold is open. The new module starts with empty state, so budgets and cooldowns
begin full. The replay also starts both modules empty, so it shows what changes, not the exact live outcome.

## Module contract

A module is one JavaScript expression: a factory that receives `lib` and returns an object with
`step(input, state, lib)`. Leading comments are fine.

```js
(lib => ({
  step(input, state) {
    return {decisions: input.events.map(event => decide(event, input)), state};
  }
}))
```

`state` is whatever the previous step returned (`{}` at first, and after any module change). Keep it small: 16 KiB.

### Attention

```text
input:  {tick, seed,
         attention: {proactiveSocialMode, reflexOwnsActuation, activeJobType, activeJobIdle, activeJobTerminal,
                     pendingCraftToolResult, activeJobTargets, routineWakesHeld, goalBlocked},
         plannerRules: [{index, ruleId, effect, reason, match: {eventType, player, speaker, actor, itemId, damageTypeId, attackerName}}],
         events: [{seqNo, type, fields, profile: {semantic, trigger, bypass}, plannerEnabled,
                   evidence: {addressedToAgent, resetCommand, senderWithinChatDistance}}]}
output: {decisions: [{seqNo, emitSemantic,
                      ruleMatch: {effect, ruleIndex, ruleId, reason, bypassed},
                      policy:    {effect, ruleIndex, ruleId, reason, bypassed},
                      wake:      {delivery: NONE|IMMEDIATE|DEBOUNCE, urgency: CRITICAL|DIRECT|HIGH|NORMAL|LOW|SELF, ruleId, reason}}],
         state}
```

- Return exactly one decision per event, keyed by `seqNo`.
- `plannerRules` holds your `update_event_policy` rules; the latest matching one wins. A module that never reads
  `input.plannerRules` makes `update_event_policy` do nothing, and `update_rules` warns you when yours does not.
- `ruleIndex` is `-1`, or the index of the planner rule named by `ruleId`; anything else fails the step.
- `wake.ruleId` is 1-128 characters and `wake.reason` at most 256. Choose your own rule id prefix (`mine.`); do not
  start one with an event namespace such as `social.`.
- `DEBOUNCE` holds a wake until 10 quiet ticks pass (or 100 after the oldest) and delivers the held wakes as one
  batch. CRITICAL, DIRECT and HIGH wakes are never held.
- Effects `IGNORE` and `SEMANTIC_ONLY` stop a wake; `ALLOW` and `TRIGGER_ONLY` let it through.
- The bundled module also keeps a leaky bucket over NORMAL and LOW wakes (a burst of 10, then 12 a minute; rule id
  `budget.autonomous_wakes`). It is a constant near the top of the module (`AUTONOMOUS_BUDGET`): retune or remove it.

### Salience

```text
module: {interests: {blocks: [ids or #tags]}, step(input, state, lib)}
input:  {tick, seed,
         context: {objective, constraints, wanted: [itemIds], inventory: {itemId: count}, activeJobType, activeJobTargets, idle},
         candidates: [{id, kind: block|item|entity|entity_lost|environment, ...evidence}]}
output: {percepts: [{type, payload, candidateIds}], drops: [{candidateId, reason}], state}
```

- `interests.blocks` is what the block sensor scans for. Every entry costs raycasts, so keep the list short.
- `type` is one of `perception.block_noticed`, `perception.item_noticed`, `perception.entity_noticed`,
  `perception.entity_lost`, `perception.environment_changed`. Anything else is rejected.
- A candidate you neither turn into a percept nor drop is dropped as `unselected`.
- Candidates are honest: a block is only a candidate if you could see an exposed face, so no rule can see ore that is
  sealed in stone.

## Sandbox and helpers

No I/O, threads or host access. `Date` is frozen at `tick * 50` ms and `Math.random` is seeded from `input.seed`, so a
step depends only on `input` and `state`. A step may run 50,000 statements and return 64 KiB.

`lib` helpers, each keeping its memory in the state object it is given: `leakyBucket`, `slidingWindow`,
`tumblingWindow`, `cooldown`, `hourlyCap`, `cluster`, `seededRandom`. Their source follows.
