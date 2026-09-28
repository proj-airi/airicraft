# Planner Event System Phase 3 Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wakes reference evidence instead of carrying prose. A wake tells the
model *why* it was woken (`observe.wake`), and the evidence behind it is an
ordinary `observe.events` entry. Trigger prose is removed:

- instructions move to the system prompt and tool descriptions;
- facts move into event payloads;
- situation-specific coaching moves to `observe.hints`.

The legacy semantic notice channel (E2) retires. This is the first phase that
changes what the model sees (spec O1), so every golden diff is reviewed and
attributed to one slice.

**Spec:** [`specs/2026-09-26-planner-perception-and-wake-design.md`](../specs/2026-09-26-planner-perception-and-wake-design.md),
sections 2.4–2.6, 4.4, 4.8 and 6 (Phase 3). **Previous phase:**
[`plans/2026-09-28-planner-event-system-phase-2.md`](2026-09-28-planner-event-system-phase-2.md).

---

## Decisions

These were settled on 2026-09-28, before implementation.

| # | Question | Decision | Why |
|---|---|---|---|
| R1 | One PR or slices? | **One PR, four slices, each its own commits with its own golden diff.** 3a visibility, 3b `observe.wake`, 3c retire E2, 3d prose out. | Every model-visible change stays attributable to one cause (O1), while the user asked for one PR per phase. |
| R2 | Which types become planner-visible (D8)? | **A type is `PLANNER` when its routing profile is semantic-eligible or trigger-eligible, except chat.** Newly visible: `action_graph.goal_suspended`, `action_graph.goal_terminal`, `follow.stuck`, `follow.target_acquired`, `follow.target_lost`, `planner.goal_set`, `planner.goal_cleared`, `planner.degraded_entered`, `planner.degraded_cleared`, `planner.reset_requested`, `planner.stale_response_rejected`, `social.item_offered`, `social.system_message`, `social.player_joined_game`, `social.player_left_game`, `social.player_joined_nearby`, `social.player_left_nearby`. `observe` filters by the catalog, not G10's prefix list. | These are exactly the facts that today reach the model only through E1 prose or E2 notices. Chat (`social.player_spoke`, `social.player_addressed_agent`, `social.local_controller_spoke`) stays a user turn, so listing it again would recreate D2. Raw-only internals (provider errors, lifecycle steps, `rules.*`) stay diagnostic. |
| R3 | What does `observe.wake` look like? | **A list, one entry per distinct reason, deduplicated by `seqNo`:** `{"reason": "event", "seqNo", "type", "urgency"}` for W1/W2/W3, and `{"reason": "goal_continuation" \| "idle_think" \| "delegation" \| "tool_queue_review"}` for W4/W5/W6/W9. Chat is not listed; it remains the user turn. Urgency is lowercase. | Matches spec 4.8. Deduplicating by `seqNo` absorbs D4's double path (a `reflex.resolved` W1 and its W2 name the same event), which Phase 2 deferred here. |
| R4 | Where does each piece of prose go? | See the **prose disposition** table below. In short: facts become payload fields; the standing rules for a wake reason go into the system prompt; tool-specific rules go into the tool's description; the remaining per-situation coaching becomes `observe.hints: [{"seqNo", "type", "hint", "provenance": "runtime_hint"}]`, rendered by `DecisionHints`. | Spec 4.8 and ADR-0002: observations are evidence, not requests. Hints stay explicitly labelled as runtime advice. |
| R5 | What replaces the E2 aggregations (pickup and craft sums, damage sums)? | **Nothing new.** E2 retires with `pendingSemanticEvents`, `SemanticContextProjector`, the W8 overflow flush and `plannerEventBuffer`. | Pickups, crafts and damage are already individual `observe.events` entries; E2 only repeated them (D2). Removing the duplicate lowers tokens. Aggregation can be added to the renderer later if token data asks for it. |
| R6 | Ambient notices (time beacon, session mode, primary player, direct action goal, world evidence snapshot)? | **Unchanged in Phase 3.** They stay in `observe.notices`. | They are not E2 (spec 2.4); moving them into `current` is a separate prompt change with its own risk. |
| R7 | `update_event_policy` semantics after E2 (O8)? | **`ignore` and `semantic_only` stop wakes; evidence stays visible in `observe`. `trigger_only` behaves like `allow`.** The schema is unchanged; the tool description says so. The attention log keeps `emitSemantic` for replay compatibility, documented as the rule's claim, not a channel. | O8 was decided before implementation. |
| R8 | The delegation start prompt (W6) and evaluation seeds (W7)? | **Unchanged.** The delegation prompt is the thinking role's task statement and the evaluation seed is a scenario instruction, not autonomous-event prose. They gain only a `wake` reason. Protocol notices (tool-call repair, turn boundary, tool queue state, report requests, policy continuation) are also out of scope. | Phase 3 targets event prose. Rewriting task statements changes different behaviour. |
| R9 | Defects fixed here | **D1** (one sequence space once `plannerEventBuffer` is gone), **D2** (no duplicate channel), **D4** (one `wake` entry per fact), **D8** (visibility). **D3** is resolved by O8 as documented semantics: its probe flips to pin "ignore stops the wake, evidence stays visible". D5 was refuted in Phase 0 and stays as is. | These fall out of the slices; each probe flips in the slice that fixes it. |
| R10 | How is Phase 3 validated without a model? | **Deterministically, then live by the user.** The goldens show every observe payload before and after. A new prompt-size check measures observe and system-prompt characters across all golden scenarios, before and after, and is recorded in the plan. The spec's live A/B (a playtest plus an evaluation batch against the Phase 2 build) needs a model; the user decides whether to run it. | Phase 3 is the first phase whose change the goldens cannot prove harmless on their own, because it changes what the model reads. |

### Prose disposition

| Source (today) | Facts → payload | Standing rule → system prompt / tool description | Coaching → `observe.hints` |
|---|---|---|---|
| Pickup, craft, damage (W1) | already events | — | — |
| `smelting.output_ready` | already in the payload | `collect_smelted_items` description: collect with the event's `processId`, then verify inventory | — |
| `task.blocked` | already in the payload | — | — |
| `action_graph.goal_suspended` | payload + `pendingWatch` (now visible) | system prompt: a suspended goal resumes on its own; you may explain the wait, start one useful goal, or acknowledge | — |
| `action_graph.goal_terminal` (FAILED) | payload + `failedPrimitive`, `failedTarget`, `failedArgs`, `failureCode` (now visible) | system prompt: explain terminal failures accurately and never claim completion | `unknown_acquisition_method` / `unsupported_resource_kind`: "unsupported capability; do not retry unchanged" |
| `social.item_offered` | payload (now visible) | — | "inferred from spawn position and motion; not a confirmed pickup" |
| `player.physical` | payload | system prompt: physical observations are changes, not proof of an involuntary cause | — |
| `reflex.resolved` (Stage A) | `pendingDecision: {holdId, options: ["continue", "clear_queue"]}`, `reflexPolicy`, `recoveryWindowTicks` (stalemate), `combatSummary` | system prompt: a safety hold awaits `continue` or `clear_queue`; policy changes do not resume paused work | stalemate and approach-stalled coaching |
| `social.system_message` | payload (now visible) | — | — |
| Ambient player chat | stays a user turn | — | — |
| Goal continuation (W4) | `current.plannerGoal` already carries the goal | system prompt: on `goal_continuation`, advance, change or finish the goal explicitly | — |
| Safety hold still pending (W4) | `current.pendingDecision` | (as `reflex.resolved`) | — |
| Idle think (W5) | — | system prompt: on `idle_think`, do one small enjoyable thing in character, not a checklist read-back | `idle_think` hint with the character's interests and the idle ideas |
| Task wakes (W2/W3) "Work changed." / `task.notice` message | the `task.notice` / `work.changed` event itself | — | — |

---

## Ground rules

1. **Each slice is its own commits, and its golden diff is reviewed** before
   the next slice starts: regenerate with `AIRICRAFT_UPDATE_WAKE_GOLDENS=1`,
   read every changed `observe` payload, and name the change in the commit.
2. **Wake audit labels stay stable** (W1–W9, gate ids), as in Phase 2.
3. **The attention and scheduling decisions do not change.** Phase 3 changes
   what a wake *says*, not whether or when it happens; `wakeAudit` sections of
   the goldens must stay identical except where a slice names a reason.
4. **The recorder, dashboard, CLI and evaluator keep working** on the one
   event log (`semanticEventContains` reads the raw log already).

## Slice 3a: visibility from the catalog (D8)

### Task 1: catalog visibility

- [ ] Set the R2 types to `EventVisibility.PLANNER`; keep chat, raw-only
  internals and graph lifecycle steps `DIAGNOSTIC`. Update
  `event-inventory.json`.
- [ ] `PlannerDecisionContext.relevant` reads the catalog
  (`EventCatalog.defaults().visibility(type) == PLANNER`) instead of the prefix
  list. A test pins that every G10 prefix type is still visible and every R2
  type is newly visible.
- [ ] Goldens: new `observe.events` entries for the R2 types (graph terminal,
  item offer, social, follow, planner goal events). Flip the D8 probe.
  Commit: `feat(observe): take event visibility from the catalog (fixes D8)`.

## Slice 3b: `observe.wake`

### Task 2: wake references on triggers and task wakes

- [ ] `PlannerTrigger` gains an optional `WakeRef(reason, seqNo, type, urgency)`.
  The pipeline sets it for W1 (event, from the attention decision); the idle
  hook sets `goal_continuation` / `idle_think`; delegation sets `delegation`;
  task wakes (W2/W3) set `event` from `Wake.eventSequence()` and the event's
  type; the tool queue review sets `tool_queue_review`.
- [ ] `PlannerRequest` / `PlannerTriggerBatch` carry the batch's refs, and the
  orchestrator renders them into the observation as `wake` (deduplicated by
  `seqNo`, first urgency wins by rank). Additive: prose is still present.
- [ ] Goldens: every request gains `observe.wake`. Flip the D4 probe to
  "one wake entry for the fact".
  Commit: `feat(observe): say why the planner woke (observe.wake)`.

## Slice 3c: retire E2 (D1, D2)

### Task 3: remove the semantic notice channel

- [ ] Remove `pendingSemanticEvents` from `PlannerContextState` and the
  reducer, `SemanticContextProjector` and its formatter/coalescer, the E2 part
  of `renderSnapshotNotices`, the W8 overflow flush, and `plannerEventBuffer`
  with `PlannerFeedPublisher`. The role cursor (`lastObservedEventSeqNo`)
  reads the one event log.
- [ ] The pipeline keeps routing and attention decisions; `emitSemantic` has no
  consumer (R7).
- [ ] Goldens: E2 notices disappear from `observe.notices` (pickup/craft sums,
  social, follow, planner goal lines). Their facts are in `observe.events`
  since 3a. Flip D1 and D2 probes.
  Commit: `refactor(observe): retire the legacy semantic notice channel (fixes D1, D2)`.

## Slice 3d: prose out

### Task 4: `DecisionHints` and payload facts

- [ ] `DecisionHints` renders `observe.hints` from the batch's event refs,
  keyed by event type, using the coaching column of the disposition table.
- [ ] Add the payload facts: `reflex.resolved` gets `pendingDecision`,
  `reflexPolicy` and `recoveryWindowTicks`; `current.pendingDecision` while a
  safety hold waits.

### Task 5: static guidance

- [ ] Move the standing rules into `PlannerPromptPolicy` (one "Wakes" section
  keyed by `observe.wake` reasons and event types) and the
  `collect_smelted_items` and `update_event_policy` descriptions (R7).

### Task 6: remove the prose

- [ ] `WakePresenter` returns triggers without prose for event wakes; the
  orchestrator no longer turns runtime triggers into NOTICE messages.
  Goal continuation, safety-hold reminders, idle think and task wakes stop
  sending text. Chat, delegation and evaluation seeds are unchanged (R8).
- [ ] The coalescing keys stay, so W1 coalescing (Phase 5) is unaffected.
- [ ] Goldens: prose notices disappear, `observe.hints` appears where the
  table says, the system prompt sha changes once.
  Commit: `feat(observe): wakes reference evidence instead of carrying prose`.

## Slice 3e: verification and docs

### Task 7

- [ ] `./gradlew build`, the evaluator, Python and dashboard suites; a 200×
  repeat of the golden scenarios.
- [ ] Prompt-size check (R10): total `observe` and system-prompt characters
  across all golden requests, Phase 2 versus Phase 3, recorded here.
- [ ] Live smoke without a model (as in Phase 2): a driven client shows
  `observe.wake`, newly visible events and hints through `agent tools call observe`.
- [ ] Docs: spec Phase 3 checklist and D-rows, this plan's status,
  `docs/attention-rules.md` (R7), AGENTS.md behaviour notes.
- [ ] The user decides on the live A/B (R10).

## Exit criteria

- [ ] Build green; goldens changed only in the four slice commits, each
  reviewed; the 200× repeat is stable; `wakeAudit` unchanged except named
  reasons.
- [ ] No trigger prose reaches the model for event wakes, goal continuation,
  idle think or task wakes; `observe.wake` names every wake.
- [ ] D1, D2, D4 and D8 probes flipped; D3 documents O8.
- [ ] The prompt-size check shows no growth in total observe characters
  across the golden scenarios, or the growth is explained.
- [ ] Spec exit (fewer empty wakes, latency and tokens no worse, no new failure
  classes) is measured live only if the user runs the A/B.

## Risks

- **The model relied on prose imperatives.** The same rules move to the system
  prompt and hints; a live A/B is the only real check (R10).
- **Newly visible events add tokens.** Offset by removing E2 duplicates; the
  prompt-size check makes the net visible.
- **Prompt cache prefix changes** once, when the system prompt changes.
