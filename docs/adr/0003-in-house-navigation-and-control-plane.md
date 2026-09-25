# In-house navigation and a single control plane

Status: accepted (2026-09-25); not yet implemented. Migration plan:
[docs/superpowers/specs/2026-09-25-in-house-navigation-and-control-plane.md](../superpowers/specs/2026-09-25-in-house-navigation-and-control-plane.md).

## Context

Baritone runs as an external process with asynchronous cancellation, string path
events and process-wide settings. Airicraft adapts it with ten mixins into
Baritone internals, a release-barrier protocol between owners, and executor logic
that reinterprets path events. Airicraft also writes player input from many
independent controllers, while Baritone writes through its own input handler.
Actuation therefore has no single owner.

## Decision

Airicraft owns pathfinding and movement execution. A pure Java navigation core
provides a terrain snapshot, a per-request movement policy, a move catalog,
bounded search and motor executors. A Minecraft adapter builds snapshots and
applies intents.

All physical actuation goes through one control plane: movement input, rotation,
hotbar selection, attack and use. Owners hold prioritized leases: reflex, then
foreground work, then background work. Preemption and release take effect in the
same tick; no owner waits for another to acknowledge a release.

Navigation returns typed outcomes. Arrival is decided by the same goal predicate
that the search used. Travel bounds, preserved areas, edit permissions and
movement costs are inputs to each request, not process-wide settings.

The first version covers walking, climbing, falling, swimming, doors,
tunnelling, bridging and pillaring. Parkour follows later. Long-range travel is
planned in segments through loaded terrain and replanned as chunks load; there
is no unloaded-chunk cache.

The planner keeps the `configure_pathfind` and `inspect_pathfind` tools. Their
schema becomes a small Airicraft-owned movement policy instead of Baritone
setting names.

Baritone is removed in the same change that makes the in-house backend the
default. There is no fallback period. Parity gates must pass before that change
merges.

## Consequences

- The Baritone runtime dependency, its mixins, the release-wait states and
  global-settings mutation all go away.
- Routes that relied on Baritone's parkour or on its cached unloaded chunks may
  end `Unreachable` or `Partial` until the equivalent features exist.
- Planner-visible names change, including Baritone setting names and
  `BARITONE_CANCELLED`. Planner evaluation runs separately from navigation
  evaluation so that the two kinds of regression stay distinguishable.
- After the switch, rollback means reverting that change.

## Relationship to ADR-0002

ADR-0002 keeps "a single foreground actuator boundary". Leases make that
boundary explicit and testable.

ADR-0002 also rules out "a continuous position controller". That rule concerns
System 2 decisions. The motor replaces Baritone's existing System 1 executor and
gives System 2 no new continuous control.
