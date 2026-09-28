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
`bundled:attention/default.js`). It is a port of the Java `ReferenceAttentionPolicy`, and `RuleDifferentialTest` pins
it to the same decisions.

To override it, place a module at `config/airicraft/rules/attention.js`. Copying the bundled module is the usual
starting point.

- **`airicraft reload`** compiles the override and runs one step against an empty input before accepting it. If
  that fails, the command returns `invalid_config` naming the file, the error code (`load_failed`, `guest_error`,
  `source_limit`, ...) and the guest message, and the old runtime keeps running. Deleting the file and reloading
  restores the bundled module.
- **At startup** the override is not validated, so a broken file never blocks the client. The first routed event
  reports `rules.step_failed`, then the policy reverts to the bundled module, publishes `rules.reverted` with reason
  `load_failed`, and logs a warning.
- **At run time** an override is also reverted after 3 consecutive failed steps (`consecutive_step_failures`).

Whenever the rules have not decided (the engine is still warming, or a step failed), the Java reference decides
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
 attention: {proactiveSocialMode, reflexOwnsActuation, activeJobType, activeJobIdle, activeJobTerminal, pendingCraftToolResult},
 plannerRules: [{index, ruleId, effect, reason, match: {eventType, player, speaker, actor, itemId, damageTypeId, attackerName}}],
 events: [{seqNo, type, fields, profile: {semantic, trigger, bypass}, plannerEnabled,
           evidence: {addressedToAgent, resetCommand, senderWithinChatDistance}}]}
```

- `plannerRules` holds the planner's `update_event_policy` rules in order. The latest matching rule wins.
- `fields` holds the payload values the policy reads (`player`, `speaker`, `actor`, `itemId`, `damageTypeId`,
  `attackerName`, `state`), as strings.

`step` must return one decision per event:

```text
{decisions: [{seqNo, emitSemantic,
              ruleMatch: {effect, ruleIndex, ruleId, reason, bypassed},
              policy:    {effect, ruleIndex, ruleId, reason, bypassed},
              wake:      {delivery: NONE|IMMEDIATE, urgency: CRITICAL|DIRECT|HIGH|NORMAL|LOW|SELF, ruleId, reason}}],
 state}
```

- `ruleMatch` is the planner rule that matched. The host records it for `update_event_policy` bookkeeping.
- `policy` is the rule that applies. For the bundled module this is the default mining-pickup rule when no named
  planner rule matched.
- Effects are `ALLOW`, `IGNORE`, `SEMANTIC_ONLY` and `TRIGGER_ONLY`.
- `ruleIndex` is `-1` or the index of the planner rule named by `ruleId`. Any other value makes the step fail with
  `malformed_decision`.
- `wake.ruleId` must be 1–128 characters and `reason` at most 256.
- Do not start rule ids with an event-id namespace such as `social.`. Use prefixes like `chat.`, `ownership.` or your
  own.

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

- `airicraft agent debug state` shows the engine under `attention.rules`, and the dashboard runtime snapshot has
  the same data. Fields:
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
