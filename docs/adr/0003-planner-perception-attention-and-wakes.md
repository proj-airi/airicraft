# Planner perception, attention, and wakes

Status: accepted (2026-09-26); implementation begins with Phase 0 characterization.

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
