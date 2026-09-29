# Attention Rules

Attention rules decide, for each routed event, whether the event reaches the planner's semantic input and whether
it may wake the planner. They are Stage B of the attention pipeline. Two Java stages surround them and cannot be
overridden:

- **Stage A, the constitution.** It runs first. Direct chat addressed to the agent, reset commands, the survival
  reflex handoff, and evaluation suppression are decided in Java and never reach the rules.
- **Stage C, the clamp.** It runs last. Types marked `policyBypass` in the catalog keep their bypass and semantic
  delivery. A rule cannot silence a wake that the Java reference would deliver for those types. Each clamp is
  counted in `attention.rules.clamps`.

## Modules

The bundled module is `src/main/resources/airicraft/rules/attention/default.js` (origin
`bundled:attention/default.js`). Each of its decisions is a port of the Java `ReferenceAttentionPolicy`, and
`RuleDifferentialTest` pins it to the same decisions. Its one stateful part, the
[autonomous-wake budget](#autonomous-wake-budget), has no Java mirror.

To override it, place a module at `config/airicraft/rules/attention.js`. Copying the bundled module is the usual
starting point.

- **`airicraft reload`** compiles the override and runs one step against an empty input before accepting it. If
  that fails, the command returns `invalid_config` naming the file, the error code (`load_failed`, `guest_error`,
  `source_limit`, ...) and the guest message, and the old runtime keeps running. Deleting the file and reloading
  restores the bundled module.
- **At startup** the override is not validated, so a broken file never blocks the client. A module that fails to
  load is reverted at the first routed event (`rules.step_failed`, then `rules.reverted` with reason `load_failed`).
  A module that loads but fails its steps is reverted after 3 consecutive failures. Either way the client logs a
  warning and the bundled module takes over.
- **At run time** any override is reverted after 3 consecutive failed steps (`consecutive_step_failures`).

The engine is interpreter-only. Before it reports ready, it runs 600 synthetic steps off-thread, so the first real
steps are not slow. Whenever the rules have not decided (the engine is still warming, or a step failed), the Java reference decides
instead. Those wakes are audited with stage `FALLBACK`.

## Contract

A module is one JavaScript expression: a factory that receives the bundled library and returns an object with
`step(input, state, lib)`.

```js
// Leading comments are fine.
(lib => ({
  step(input, state) {
    return {decisions: input.events.map(event => decide(event, input)), state};
  }
}))
```

`input` has this shape:

```text
{tick, seed,
 attention: {proactiveSocialMode, reflexOwnsActuation, activeJobType, activeJobIdle, activeJobTerminal, pendingCraftToolResult,
             activeJobTargets, routineWakesHeld, goalBlocked},
 plannerRules: [{index, ruleId, effect, reason, match: {eventType, player, speaker, actor, itemId, damageTypeId, attackerName}}],
 events: [{seqNo, type, fields, profile: {semantic, trigger, bypass}, plannerEnabled,
           evidence: {addressedToAgent, resetCommand, senderWithinChatDistance}}]}
```

- `plannerRules` holds the planner's `update_event_policy` rules in order. The latest matching rule wins.
- `fields` holds the payload values the policy reads (`player`, `speaker`, `actor`, `itemId`, `damageTypeId`,
  `attackerName`, `state`, `blockId`, `change`), as strings.
- `routineWakesHeld` and `goalBlocked` say that the scheduler will drop the wake anyway (G4): routine pickups and
  crafts while accepted or queued work consumes them, and everything but direct guidance while the goal is blocked.
  The bundled budget does not charge those wakes.
- `activeJobTargets` lists the block and item ids the running job works on (a mining job's block ids, a collect job's
  target blocks and accepted items).

`step` must return one decision per event:

```text
{decisions: [{seqNo, emitSemantic,
              ruleMatch: {effect, ruleIndex, ruleId, reason, bypassed},
              policy:    {effect, ruleIndex, ruleId, reason, bypassed},
              wake:      {delivery: NONE|IMMEDIATE|DEBOUNCE, urgency: CRITICAL|DIRECT|HIGH|NORMAL|LOW|SELF, ruleId, reason}}],
 state}
```

- `ruleMatch` is the planner rule that matched. The host records it for `update_event_policy` bookkeeping.
- `policy` is the rule that applies. For the bundled module this is the default mining-pickup rule when no named
  planner rule matched.
- Effects are `ALLOW`, `IGNORE`, `SEMANTIC_ONLY` and `TRIGGER_ONLY`. Since Phase 3, every planner-visible event is observed whatever the rules decide, so an
  effect only decides the wake: `IGNORE` and `SEMANTIC_ONLY` stop it, and `ALLOW` and `TRIGGER_ONLY` let it through.
  `emitSemantic` is still recorded on each decision, but nothing consumes it.
- `ruleIndex` is `-1` or the index of the planner rule named by `ruleId`. Any other value makes the step fail with
  `malformed_decision`.
- `wake.ruleId` must be 1–128 characters and `reason` at most 256.
- `DEBOUNCE` holds the wake until 10 ticks pass without another debounced wake, or 100 ticks after the oldest, and
  then delivers all held wakes as one batch. A wake delivered in the meantime takes the held ones with it. Critical,
  direct and high urgency are never held.
- The bundled module debounces percepts (`perception.*`) at `LOW` urgency. A percept about a block or item the running
  job targets is `NONE`. `perception.environment_changed` wakes only for dusk while idle; other changes are evidence.
- Do not start rule ids with an event-id namespace such as `social.`. Use prefixes like `chat.`, `ownership.` or your
  own.

### Autonomous-wake budget

The bundled module keeps a leaky bucket in its rule state (`state.autonomous`, through `lib.leakyBucket`).

- **What it counts:** every `NORMAL` or `LOW` wake (`IMMEDIATE` or `DEBOUNCE`) of a type that is not protected costs
  one. `HIGH` and above are never budgeted, and protected types (`profile.bypass`) are skipped, since the clamp would
  restore their wakes anyway.
- **Defaults:** capacity 10 and a leak of 0.01 per tick, so a burst of 10 wakes and then 12 a minute.
- **Over budget:** the decision becomes `NONE` with rule id `budget.autonomous_wakes` and the bucket level in its
  reason. The event is still observed; only the wake is withheld.
- **Retuning:** change `AUTONOMOUS_BUDGET` in a copy of the module, or remove the `budget` call to turn it off. The
  Java fallback, used while the engine is cold or failing, does not budget.

The defaults are meant to catch storms (a burst of pickups, a flood of noticed items), not ordinary play: replaying
the recorded runs through the module changes no decision.

## The wake scheduler

The rules decide each wake; `WakeScheduler` decides when a wake reaches the planner. These timings are Java and cannot
be overridden:

- **Preemption.** A reflex start opens a new safety epoch, and its wake has delivery `PREEMPT`. If a planner turn is
  running for the old epoch and has externalized nothing (its model call is still running, no tool is executing, and
  no tool other than a read tool ran in the turn), it is cancelled at once and `planner.turn_preempted` is published;
  the reflex wake is delivered in the same tick. After a side-effect tool the turn is never preempted: it is rejected
  when it completes (`planner.stale_response_rejected`), as before. A hold change within the same epoch does not
  preempt. Both backends cancel the discarded call.
- **Supersession.** Direct guidance cancels a replaceable running turn at most 3 times per 600 ticks. Over that
  budget, the guidance waits behind the running turn (audit gate `supersede.budget`) and starts the next one. Reset
  commands are handled before the planner, so a player can always stop the agent.
- **Coalescing.** After a supersede, the scheduler holds the new turn for `clamp((n−1) × step, min, max)` from
  `plannerSessionCoalesce{Step,Min,Max}Millis` (defaults 10/10/100 ms), rounded up to ticks (1/1/2), so lines typed
  together start one turn. With a single queued line there is nothing to coalesce, and the new turn starts at once.
- **Pending bound.** At most 64 pending task wakes and 32 held debounced wakes; overflow drops the least urgent,
  oldest first, and never an attention, preempting, critical or direct wake (audit gate `pending.bounded`).

## Salience rules

A second hook decides what the agent notices (spec section 5). Java sensors hand over honest candidates: things the
player could actually perceive, each once, as plain records. A block is a candidate only when a raycast from the eyes
reaches one of its faces exposed to a non-opaque neighbour, so a block sealed in stone never is. The salience module
decides which candidates become percepts. It never sees the live world.

The bundled module is `src/main/resources/airicraft/rules/salience/default.js`. Override it with
`config/airicraft/rules/salience.js`; reload and startup treat it exactly like an attention override.

```text
module: {interests: {blocks: [ids or #tags]}, step(input, state, lib)}
input:  {tick, seed,
         context: {objective, constraints, wanted: [itemIds], inventory: {itemId: count}, activeJobType, activeJobTargets, idle},
         candidates: [{id, kind: block|item|entity|entity_lost|environment, ...evidence}]}
output: {percepts: [{type, payload, candidateIds}], drops: [{candidateId, reason}], state}
```

- `interests.blocks` is what the block sensor scans for. Every entry costs raycasts; keep it short. `#tag` entries
  match block tags.
- `type` must be one of `perception.block_noticed`, `perception.item_noticed`, `perception.entity_noticed`,
  `perception.entity_lost` or `perception.environment_changed`; other percepts are rejected and counted.
- Candidates neither turned into a percept nor dropped are recorded as `dropped:unselected`.
- At most `perception.candidatesPerStep` candidates (default 50) go into one step, oldest first. A failed step keeps
  its candidates for up to 20 ticks after their first failed step, and an override that fails 3 steps in a row reverts
  to the bundled module. Candidates wait out the engine's warm-up without ageing.
- The bundled module notices notable ores, spawners, chests and portals (adjacent blocks form one vein percept),
  players, villagers, named or tamed animals, food animals when their products are wanted, dropped items that are not
  common garbage (unless the goal or a job wants them), and environment changes. It drops offered items (the offer is
  already a percept) and anything the running job owns, and applies per-category cooldowns and hourly caps.

`airicraft agent debug state` shows the salience engine as `[perceptionSalience]` (percepts, drops, step timings);
`--verbose` adds per-sensor timings and the recent decisions. Recorded runs write `salience-steps.jsonl`; re-run them
with `./gradlew salienceReplay -Pairicraft.replayRun=<run-dir> [-Pairicraft.replaySalienceModule=<salience.js>]`,
which writes `salience-replay.json` with the steps whose percepts or drops differ.

## Sandbox

- **No host access.** No I/O, threads, Java interop, or host access.
- **Deterministic.**
  - `Date` and `Date.now()` are frozen at `tick * 50` ms.
  - `Math.random` is seeded from `input.seed`.
  - A step's result depends only on `input` and `state`.
- **Limits.** 50,000 statements per step; 32 KiB of source; 16 KiB of JSON state; 64 KiB of output. Exceeding a
  limit fails the step and leaves the previous state in place.
- **State.** `state` is the object returned by the previous step (`{}` at first and after a module switch).

`lib` has these helpers, each keeping its memory in the state object it is given:

- `leakyBucket`
- `slidingWindow`
- `tumblingWindow`
- `cooldown`
- `hourlyCap`
- `cluster`
- `seededRandom`

## Observing

- `airicraft agent debug state` shows the engine as `[attentionRules]`, with decision and pending-wake counts;
  `--verbose` adds the latest decisions and counts by rule. The bridge JSON (`attention`) and the dashboard's
  Attention view carry the same data. Engine fields:
  - `module`, `ready`
  - `steps`, `fallbacks`, `failures`, `consecutiveFailures`
  - `clamps`, `reverts`, `rebuilds`
  - `maxStepMicros`, `stateBytes`, `lastFailure`
- The attention decision log under `attention` records every decision with its stage, rule id and reason.
- Diagnostic events `rules.step_failed` and `rules.reverted` appear in the event log.

## Replaying a recorded run

Evaluation runs and automatic playtests record each attention decision, with the inputs it was decided from, in
`attention-decisions.jsonl` next to `events.jsonl`. The inputs are:

- the attention state and chat evidence;
- whether the planner was enabled;
- the routing profile;
- the planner rules.

To re-decide every recorded decision with both the Java reference and a rule module, run:

```sh
./gradlew attentionReplay -Pairicraft.replayRun=<run-dir> [-Pairicraft.replayModule=<attention.js>]
python3 scripts/wake_ledger.py replay-summary <run-dir>
```

The Gradle task writes `attention-replay.json` into the run directory. For each replayed event it holds three
decisions: the recorded one, the reference's, and the rules'. Two decisions are the same when they agree on delivery,
urgency, semantic feed and rule id. `replay-summary` counts where the recording differs from the reference and where
the rules differ from it. It breaks these counts down by event type and rule transition, and lists the first
differing decisions.

Use it to check an override against real runs before shipping it.
