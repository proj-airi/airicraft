# Planner Event System Phase 6 Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the planner tune its own attention.

- The planner can read the rules that decide what wakes it, and replace them.
- Every edit is checked by replaying recent history before it takes effect, and the planner sees what it would have
  changed.
- A bad edit can be rolled back, and a broken one is reverted automatically.
- The constitution and the clamp stay in Java, out of the planner's reach.

This is the last phase of the refactor. The perception performance work (P9) follows it.

**Spec:** [`specs/2026-09-26-planner-perception-and-wake-design.md`](../specs/2026-09-26-planner-perception-and-wake-design.md),
section 4.12 (planner authorship), section 6 (Phase 6), decisions O7 and O11, and the risk "planner-authored rules
that tune attention badly". **Previous phase:** [`plans/2026-09-29-planner-event-system-phase-5.md`](2026-09-29-planner-event-system-phase-5.md).

## Status (2026-09-29)

Decisions G1–G14 confirmed by the user on 2026-09-29 ("all good, implement and create one PR"). Slices 6a–6e are
implemented on `claude/hopeful-gauss-1gjr8v`, with the revisions below. `dev` was at 97828e23 (Phase 5 and the
Mojang-mappings migration), CI green.

**Revisions during implementation**

- **G3, activation.** The event bus accepts events only from the tick thread, so the worker never activates. It
  validates, warms a private engine and replays; then it queues the activation, which `PlannerRules.drain()` runs at the
  start of the next tick (`EmbodiedAgentRuntime.tickClient`). If the game does not tick for 10 s the edit is dropped
  (`rules_update_timeout`). An automatic revert between the check and the activation refuses the edit
  (`rules_changed_during_check`), since the candidate's origin names the version number it would get.
- **Engines.** `RuleEngine.shared` closes the hook's previous override, which would have closed the running module
  during the check. `RuleEngine.detached` builds the candidate privately and `RuleEngine.adopt` registers it once it is
  accepted, so the runtime's `shared(module)` finds it warm. A restored older version has a new origin, so its engine
  warms after activation, and the Java reference decides (`FALLBACK`) until it is ready.
- **Revert target.** The policies revert through a `RuleRevert` that the runtime points at the `RulesStore`; each
  version records the version to fall back to, and a revert or rollback copies its target's fallback, so a version that
  fails never reverts to a bad one. `rules.reverted` gained `hook`, `fromVersion` and `toVersion` (the version now
  active, 0 for the base).
- **G12, docs.** `read_rules_docs` is `docs.md` plus `lib.js`, the protected and constitution types read from the live
  catalog, and the bundled attention module, so the list cannot go stale. A test checks it against
  `docs/attention-rules.md`.
- **Verification.** `./gradlew build` passes: 1,896 root, 96 wrapper, 51 navigation and 20 JourneyMap tests (the
  known JourneyMap timing test failed once under load and passed alone). Evaluator, Python (117) and dashboard (11)
  suites pass; the three golden classes repeated 15 times and the three new scenarios 20 times, all passing.
  `RuleDifferentialTest` is unchanged. The live playtest is the user's.
- **Goldens.** Wake tests settle a rules edit by draining on the tick thread (`WakeScenarioHarness.settle`, with
  `PlannerRules.inFlight()`), so an edit's warm-up never changes a tick count.

## Where Phase 6 starts

What Phases 1–5 already provide, and what is missing (on `dev`, 97828e23):

| Needed | State on `dev` |
|---|---|
| Two hooks with replaceable modules | **Done.** `RuleModule.Hook` is `ATTENTION` or `SALIENCE`. `RuleAttentionPolicy.useModule` and `SaliencePolicy.useModule` switch modules with fresh state. Source is capped at 32,768 characters (`RuleModule.MAX_SOURCE_CHARS`). |
| Validating a candidate module | **Done.** `RuleEngine.validate(module, timeout)` compiles it and runs one step on an empty input. Today only `airicraft reload` uses it, for operator files. |
| Reverting a failing module | **Partly.** Both policies revert after 3 consecutive step failures (or a load failure), but always to the *bundled* module, and `rules.reverted` is `DIAGNOSTIC`, so the planner never sees it. |
| Replaying history through a module | **Offline only.** `AttentionReplay` and `SalienceReplay` read a recorded run directory. In memory there is an `AttentionDecisionLog` (1,024 decisions with their inputs), an `AgentEventLog` (512 events) and a `SalienceStepLog` (256 steps with input and state). Nothing replays them live. |
| `update_event_policy` | **Already a shim.** The runtime passes the planner's `plannerRules` table to the attention module as `input.plannerRules`, and the bundled module applies it. Its schema is frozen. |
| Planner tools for rules | **Missing.** `SelfToolProvider` (`define_tool`, `inspect_tool`, `remove_tool`) is the precedent: session-local, bounded, replaces in place. `PolicyDocsToolProvider` (`read_policy_docs`) is the precedent for a docs tool. |
| Edit history | **Missing.** No versions, no rollback, no record of who changed a module. |

## Decisions

**G1: Tools live in a native provider, and the tool prefix changes once, on purpose.** A new
`RulesToolProvider` (static tool list, like `PolicyDocsToolProvider`) offers three tools:
`inspect_rules`, `update_rules` and `read_rules_docs`. The frozen native prefix hash changes in one named commit.
*Alternative:* put them in `SelfToolProvider`, whose tools are dynamic and sit after the prefix. That avoids the hash
change, but these are not planner-defined tools, and the spec asks for native ones.

**G2: Both hooks are editable, chosen by a `hook` argument** (`attention` or `salience`). One module per hook.

**G3: Edits are checked by replay, and replay runs off the tick.** A cold engine needs a warm-up (600 synthetic steps)
and the first real step exceeds a tick, so `update_rules` returns a future. The tool validates, warms the candidate
engine and replays on a worker, and only the activation happens on the client thread at a tick boundary. The result
says which version it replaced.

**G4: What the replay compares.**
- *Attention:* the last N = 200 decisions that have inputs, joined to their events in the event log, in order. The
  old module and the new module each run from an empty state through `RuleAttentionPolicy` (so the clamp applies to
  both), and the diff lists changed decisions. Both start empty so the diff is like for like; live state is not
  copied.
- *Salience:* the last 64 recorded steps, threaded from the first step's recorded state. The diff lists percepts and
  drops that change.
- The result carries counts (replayed, changed, wakes gained, wakes lost, by type), up to 10 examples, and how many
  entries could not be replayed (missing events or inputs). An empty history is reported, not treated as success.

**G5: A replay diff informs and does not block.** A rule change that fails to compile, fails a step, exceeds the size
cap or targets the wrong hook is **rejected**. A change that only *mutes* things is allowed, and the tool result
says so plainly (for example "would have muted 41 of 44 wakes: pickup.item_picked_up, crafting.item_crafted"),
because the constitution and clamp already protect the wakes that matter. *Alternative:* require an `acknowledge`
argument when more than half of the replayed wakes are lost. I recommend against it: it adds a round trip and a
second way to fail, and the rollback and the automatic revert already cover a bad edit.

**G6: Versions and rollback.** Each hook keeps a bounded history of 8 versions in a `RulesStore`: version number,
sha, source, the planner's `reason`, the tick and the replay summary. `update_rules` takes either `source` or
`revert_to` (a version number, or `base`), never both. `base` is whatever module was active before the planner's
first edit (the bundled one, or the operator's override).

**G7: Operator files and reload.** A planner edit is built on the active base, so an operator's
`config/airicraft/rules/*.js` keeps its role as the base. `airicraft reload` resets planner state (it already resets
the agent), which drops the planner's edits and history. This keeps the scope session-local, as O11 decides.

**G8: Lifetime.** Session-local, like `define_tool`. They are cleared when the runtime is rebuilt, not on world
changes. World-persisted rules stay a later decision (O11).

**G9: Automatic revert goes to the previous good version.** Three consecutive step failures (the existing threshold)
revert to the previous version in the history, or to the base if there is none, instead of always to the bundled
module. `rules.reverted` gains `hook`, `fromVersion` and `toVersion`. Its visibility changes from `DIAGNOSTIC` to
`PLANNER`, so the next `observe` shows it, and it does not wake the planner by itself.

**G10: Edits are events.** A new `rules.updated` event (`INTERNAL`, visible to the planner, no wake) records hook,
version, sha, reason and the replay summary, and appears in the raw log, `observe.events` and the dashboard. A
revert records the same way.

**G11: Guard rails on authorship.**
- Source at most 32,768 characters (existing cap); a `reason` of 1–200 characters is required.
- At most 6 accepted updates per hook per 12,000 ticks (10 minutes), so a confused planner cannot thrash the engine.
  Over it, the tool returns `TOOL_ERROR: rules_update_rate` with the tick it can retry.
- `update_rules` is refused while a safety hold is open, like the other reflex-adjacent tools that are not
  `update_event_policy`.
- If the new attention source never mentions `plannerRules`, the result warns that `update_event_policy` will have no
  effect until the module reads that table (the bundled module does).

**G12: `read_rules_docs`.** One resource (`airicraft/rules/docs.md`) with the contract, the input and state shapes for
both hooks, the constitution (what Java decides and the planner cannot change, with the clamp), the budgets and the
`lib.js` helpers. It ends with the bundled attention module as a worked example. A test keeps it in step with
`docs/attention-rules.md`.

**G13: Prompt guidance.** A short section in `prompts/planner-system.md`: why to tune (repeated wakes that do not
help the current work), what not to do (do not mute what protects the player), always dry-run and read the diff, keep
edits small, prefer `update_event_policy` for a single event type, roll back when in doubt.

**G14: Review surface.** Recorded runs write `planner-rules.jsonl` (each accepted edit with its source) next to
`attention-decisions.jsonl`. `agent debug state` lists the active version of each hook and the last edits, and the
dashboard's Attention view shows the version. The live playtest reviews this file, as the spec's risk section asks.

## Slices

### 6a: `RulesStore`, versions and the live replay (Java, no tool yet)

- [ ] `RulesStore`: per-hook active module, base module and bounded history (G6, G8).
- [ ] `RuleDryRun`: joins the decision log and event log for attention, and the step log for salience; runs old and
  new modules; returns the diff (G4). Unit tests with synthetic history: unchanged module, a module that mutes
  pickups, an empty history, a module that throws, missing events.
- [ ] Revert to the previous version in both policies, with `rules.reverted` fields (G9). Update the two policy tests.

### 6b: Events, visibility and recording

- [ ] `rules.updated` in `EventCatalog` and the event inventory; `rules.reverted` becomes planner-visible (G9, G10).
- [ ] `planner-rules.jsonl` in the recorder, and the debug and dashboard views (G14).

### 6c: The tools

- [ ] `RulesToolProvider`: `inspect_rules`, `update_rules`, `read_rules_docs`, with argument validation, the rate
  limit, the hold refusal and the async activation (G1, G3, G5, G11).
- [ ] Wire it in `PlannerShellFactory`; update the tool prefix goldens in their own **[golden diff]** commit.
- [ ] `docs.md` resource and its consistency test (G12).

### 6d: Goldens and the planner prompt

- [ ] Prompt section (G13).
- [ ] New wake goldens: `planner_mutes_pickups` (the planner replaces the attention module, and the next pickup storm
  no longer wakes it), `planner_rules_reverted` (a failing edit reverts and shows in `observe.events`), and
  `planner_rules_rollback`.

### 6e: Docs and verification

- [ ] `docs/attention-rules.md` (planner authorship section), `AGENTS.md` (behaviour note, key files), `CONTEXT.md`
  terms, README section, ADR-0003 (Phase 6), the spec checklist.
- [ ] Full build, evaluator, Python and dashboard suites, golden repeat (50×), `RuleDifferentialTest`.

## Verification

| Level | What |
|---|---|
| Unit | `RulesStore` versions, bounds and rollback; `RuleDryRun` diffs; rate limit and hold refusal; argument validation |
| Rules | A planner-style module in the real sandbox: mutes pickups, keeps protected wakes (the clamp), ignores `plannerRules` (warning) |
| Golden | The three new scenarios, and only the tool-prefix hashes changing in the named commit |
| Replay | `attentionReplay` over the two recorded runs with an edited module still matches the dry-run diff |
| Live | The user's playtest: the planner tunes its own attention while building; review `planner-rules.jsonl` and the decision log |

## Out of scope

- The perception performance work (the P9 budget on a real machine, and async salience), which follows the refactor.
- World-persisted rules (O11), until live evidence shows edits help across sessions.
- Changing the constitution, the clamp, the catalog or the routing profiles, which stay Java.
- A model evaluation batch to tune the Phase 5 budgets (the user's call).
