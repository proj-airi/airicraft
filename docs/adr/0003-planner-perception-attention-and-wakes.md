# Planner perception, attention, and wakes

Status: accepted (2026-09-26); implemented through Phase 6 (2026-09-29). The perception performance work
(P9) is the remaining step.

## Context

Planner attention is spread over eight wake paths, eleven gates, and three
evidence channels. This makes suppression, duplicated evidence, and missed
outcomes difficult to explain. The accepted [design](../superpowers/specs/2026-09-26-planner-perception-and-wake-design.md)
records the source analysis, catalog, interfaces, and decisions O1–O13.

## Decision

Accept O1–O13 in design section 9. Extend [ADR-0002](0002-system2-evidence-work-and-decisions.md):
`observe` remains the evidence channel; the event log stays bounded at 512
events; this is not event sourcing.

Preserve behavior through Phases 1–2, then isolate model-visible changes in
Phase 3 (O1). A single typed pipeline carries honest sensor candidates into
percepts and agent events, then attention policy and wake scheduling. Policy
chooses urgency and delivery (O2). Direct guidance supersedes existing work
as today; safety epochs may preempt before side effects, never after (O3).
Debounce and idle timing use agent ticks; retries retain wall-clock timing (O4).
Salient perceptions may wake during work within a debounce and autonomous
budget, except when that executor owns the same work (O5).

Notice visible notable blocks, entities, and dropped items, with contextual
garbage filtering and no X-ray (O6). GraalJS owns salience, attention heuristics,
and budgets; Java owns the constitution and clamp (O7, O12). Ignoring an event
suppresses wakes, not observed evidence (O8). Retain the 512-event bound unless
measured gaps justify revisiting it (O9). The runtime owns the `agent.attention`
scheduler, delivering to dialogue (O10). Planner-authored rules begin
session-local; operator files override bundled defaults (O11). Rule failures
fall back to catalog defaults, with repeated failures reverting the module (O13).

## Ownership and limits

Sensors own truthful sampling and throttling. Rules select salience and
attention; they cannot override system gates, protected direct/critical wakes,
or evaluation suppression. Ownership inhibition and blocked-goal relevance
are guarded by characterization tests. The runtime owns scheduling and bounded
logs; existing executors, reflex state, model settings, and conversation,
compaction, and retry mechanics retain their responsibilities.

## Consequences

Phase 0 must measure current behavior before refactoring: inventory guard,
wake audits, reviewed golden transcripts, defect probes, baseline metrics,
and a real-sandbox GraalJS spike. Acceptance of this design is not evidence
that the proposed runtime budget is met. Later phases depend on those results.

## Implementation (Phases 1–6)

- One event log and one sequence space; the catalog decides what `observe` shows. Wakes reference evidence
  (`observe.wake`) instead of carrying prose (Phases 1–3).
- Honest perception: Java sensors hand line-of-sight candidates to the GraalJS salience module, which publishes
  `perception.*` events; percepts wake debounced (Phase 4).
- Preemption (O3): a reflex start's new safety epoch preempts a running turn that has externalized nothing; after a
  side-effect tool the turn is rejected on completion instead. A hold change within the same epoch never preempts,
  since the planner's own tools can cause one. Both planner backends cancel a discarded call (Phase 5).
- Budgets: the supersede budget and the coalesce window are Java, in the wake scheduler, counted in ticks; the
  autonomous-wake leaky bucket and the notice budgets live in the bundled GraalJS modules (O7), and the constitution
  and clamp keep protected wakes out of their reach (Phase 5).
- Reflex inputs stay tick-sampled outside the bus; the reflex publishes only its outputs.
- Planner-authored rules (O11, Phase 6): the planner reads and replaces its attention and salience modules with
  `inspect_rules`, `update_rules` and `read_rules_docs`. An edit is loaded, run once and replayed against the recent
  decision and step logs next to the running module, and the replay diff is returned before the edit activates; a
  module that fails to evaluate is rejected. Versions are bounded (8 per hook), a rollback or automatic revert is a
  new version, and a failing version reverts to the one it replaced. Edits are session-local, rate-limited and refused
  during a safety hold; `rules.updated` and `rules.reverted` are evidence in `observe`. The constitution, the clamp and
  the scheduler's timings stay Java, so no edit can reach them.

The perception cost budget is still to be measured on a real machine; that work follows the refactor.
