# System 2 evidence, work, and decisions

Status: accepted; implemented, automated and assisted live contract validation completed (2026-09-14). See docs/system2-refactor-validation.md for the setup death, controlled-cheat conditions, and remaining planner/navigation limitations.

## Context

Live qwen controller play exposed task outcomes delayed across tool calls, graph-only
inspection used for direct jobs, tool-budget exhaustion mistaken for objective failure,
reflexes starving supervision, and acquisition search bounds acting as travel limits.
Evidence: `run/playtest/2026-09-13/reflex-policy-live-validation.jsonl` is an untruncated
export. The wood job failed at agent tick1995. Five later requests over roughly19seconds
lacked that job's outcome. The observation labelled tick1996 was dispatched before the
failure; recorder collection time must not be mistaken for request dispatch time.

## Decision

Game-owned observations and work outcomes exist independently of requests to wake a
model. Each model role incorporates identified evidence into its own bounded
conversation before a gameplay decision, as a runtime-issued `observe` tool call and
its tool result rather than a user message: observations are evidence, not requests. Raw exchanges remain immutable in the
recorder and work history, while the retained request projection may replace one
identified tool result in place: a queued acknowledgement with its execution result,
or raw inspection evidence with its validated micro-compaction finding. Replacements
preserve tool-call identity and pairing. Full compaction waits for pending
micro-compaction, then establishes a new checkpoint epoch. Transport retries reuse
their frozen request. A new job or human instruction may supersede a wakeup, never the
effects of earlier work.

Expose one work lifecycle over existing direct jobs, graph executions and background
processes. Keep their executors and single foreground actuator boundary. System 2 may
choose high-level or precise actions without graph-first fallback requirements.

The controller owns the overall world-persisted objective. The thinker owns a bounded
delegation and may return its outcome, not finish the overall objective. Objectives
have an explicit blocked state distinct from failed attempts, yielded turns and terminal
outcomes. Constraints and named decisions are stored separately from observations.

Decision ownership is separate from actuator ownership. Reflexes gate physical actions
but allow bounded, event-driven inspection and policy changes by the active model role.
Do not add a continuous position controller or change model/effort settings.

Target/search constraints select resources. Explicit travel restrictions govern paths
and edits. Report failed predicates and actual positions; use bounded local geometry
queries for support, clearance and interaction feasibility.

Freeze the cleaned-up native typed-tool prefix independently for each role. Discovery
is catalog help, not dynamic activation of native tools. Session-local self-authored
read-only query tools are the explicit exception: their schemas form a mutable suffix
managed by `define_tool`, `inspect_tool`, and `remove_tool`; they cannot replace native
tools. Retain separate histories with stable system/native-schema prefixes. Preserve
bounded recording and explicit overflow gaps; do not introduce unbounded event
sourcing.

## Ownership and limits

| Owner | Authoritative responsibility |
| --- | --- |
| Minecraft runtime and semantic event buffer | Physical observations, executor transitions, bounded event identities and gaps. |
| Work projection/history | Common identity, requested parameters, current state and retained outcomes over existing executors; no scheduling authority. |
| Controller and objective store | Overall objective, constraints, criteria, blockers and named decisions. |
| Thinker and delegation | Bounded assignment, private reasoning/history, claimed return outcome and shared evidence references. |
| Survival reflex | Temporary actuator ownership; does not own or silence System2 decisions. |
| Per-role orchestrator | Fresh decision boundary, incorporated cursor, frozen transport attempts, stale response rejection, fixed native-schema prefix and session-local read-only self-tool suffix. |

Work history retains128 terminal entries plus unresolved work. Ordinary decisions include concise unresolved work and newly incorporated outcome events; initial context, compaction and event gaps also refresh the latest8 terminal summaries. inspect_work retains full request/evidence detail. Shared semantic evidence is bounded512events, with explicit gaps. Goal notes are bounded16names and replaced goals32entries. The recorder remains a separate12000server-tick/64MiB diagnostic window.

### Compact planner presentation (2026-09-14)

The OpenAI-compatible request boundary presents UUID-bearing native identity fields as short opaque references such as @r12. Controller, thinker and compactor share one reference table while keeping separate histories/cache prefixes. Tool ID arguments resolve before existing parsing, ownership and exact work/hold validation. Native executor IDs, persisted goals, recorder events and protocol tool-call/result pairing remain unchanged. Known references in planning-note fields are restored before persistence. Unstructured prose is not searched or rewritten; observations, JSON tool results and delegated system triggers retain typed fields in the canonical chronicle and receive field-specific presentation for the model.

The table retains8192 mappings. Evicted/unknown references fail closed and require fresh inspection; numbers are never reassigned within the client process. They are session presentation, not durable world IDs. Existing response freshness checks and native work validation still gate actuation. Request text encoding does not alter images or role schema prefixes.

configure_pathfind keeps atomic native validation and all non-Java settings, but advertises a compact name-to-typed-value map. inspect_pathfind(query) supplies up to16 matching setting descriptions/types/defaults; inspect_pathfind(names) reads values. Full settings documentation is no longer repeated in every model request. Action receipts have one flat work summary, list/wait/cancel/resume return summaries, and inspect_work returns detail. Goal context uses compact JSON; its persisted document remains readable.

## Implementation and acceptance checklist

- [x] Evidence: fresh decision context at initial, follow-up, continuation and handoff boundaries;
  per-role cursors; retained outcomes; retry/compaction/overflow/world-change coverage;
  distinguish dispatch/context/recording clocks.
- [x] Work: stable handles and receipts; inspect/list/cancel/resume/wait; graph children;
  background furnace semantics; legacy CLI adapters; authoritative terminal evidence.
- [x] Objectives: migrate existing persisted goals; blocked/resume; controller-only authority;
  scoped decisions, constraints and criteria; relevant-event reassessment without idle loops.
- [x] Delegation: structured assignment and fresh evidence; identified observed effects and
  final state separate from claimed outcome; preserve both role histories.
- [x] Reflex supervision: read/cancel/policy during reflex; no competing actuation; bounded
  wakes; observed release and explicit resume through the current work/hold identity.
- [x] Constraints and spatial queries: search/travel separation; full movement restrictions;
  forced-displacement reporting; failed predicates; bounded support/clearance/reach/LOS queries.
- [x] Tool surface/prompts: unified lifecycle; free choice of action detail; fixed native typed
  schemas; catalog-only native discovery; bounded session-local read-only self tools; preserve
  actual capabilities, evidence checks and user constraints.
- [x] Full build after each integrated subsystem; focused regressions; actual configured planner
  resource gathering, shelter repair, chest/furnace interaction and reflex-interruption trials.
  Measure redundant reads, outcome latency, repeated failures and time to productive action.

Keep the existing game paused during implementation. Export before rebuilding; use compatible
HotSwap/config reload where possible, restart cleanly for structural changes. Never silently
reactivate a finished goal. Commit logical stages and record live limitations explicitly.

Verification details, measured evidence latency, provider failures and the remaining live acceptance are in [the validation report](../system2-refactor-validation.md). Checked implementation entries do not imply all live trials passed.

### Semantic input presentation

Model requests use deterministic semantic descriptions over canonical observations. Canonical decision messages remain structured so recorder dispatch metadata and incorporated-event cursors do not depend on prose parsing. The request renderer describes work, ownership, player state and events; only exactly identical work payloads may reference an earlier current snapshot within the same request. Unknown fields pass through. Inventory and spatial query renderers describe sparse arrangements and complete horizontal patches, with individual world records available through `detail=blocks`. See [the compression standard](../planner-semantic-input.md) for preserved distinctions, permitted spatial losses, and validation boundaries.
