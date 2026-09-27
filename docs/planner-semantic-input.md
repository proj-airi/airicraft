# Semantic planner input

The model should receive a short account of the observed situation, not a serialization of implementation objects. `PlannerStateText` and `PlannerInputText` implement deterministic presentation rules; `CurrentWorldQueryService` summarizes local geometry. This is input rendering, separate from conversation-history compaction. No extra LLM call is involved.

## Standard

- Preserve actionable distinctions: item counts, selected/occupied slots, equipment location, durability, work and hold identities, state versus executor phase, failures, decision/actuator ownership, event sequence and ticks, dimensions, bounds, and explicit unknowns.
- Describe arrangements, not merely nonempty fields. “Only dirt in the first slot” encodes both the occupied slot and all other slots being empty. Multiple occupied slots retain their individual positions and counts. An empty selected slot is stated when other slots contain items.
- Omit `minecraft:` for known registry identifiers. Keep other namespaces and opaque identities intact. Tool arguments still follow their schemas; restore the default namespace where a full registry ID is required.
- Factor common facts once within one answer. Placement sites can share “all targets are air; each support is directly below; all within reach.” A differing site prevents that common claim. A work event identical to a current-work snapshot can explicitly refer to that snapshot. Its event identity, type and tick remain separate.
- Keep canonical decision history, dispatch metadata and event cursors structured. Observations, JSON tool results and delegated system triggers retain their fields alongside exact source text in the chronicle. The request renderer rounds numeric JSON values and shortens UUID-bearing identity fields before prose rendering; it leaves unstructured text exact. It does not modify history, tool-call pairing, images, native executor identities or persistence. Retry rendering is deterministic and does not depend on a previous request having been seen.
- Unknown fields in JSON envelopes pass through as additional structured fields; their numeric values use the same evidence precision rule. Unstructured text remains unchanged. Do not invent a generic lossy JSON-to-prose transformation.

## Examples

Inventory summaries describe exact item totals instead of repeating both item totals and overlapping resource categories. Occupied slots and worn/held items describe arrangement separately from those totals:

```text
Carrying 32 dirt, stone_pickaxe, 8 torch.
Hotbar: stone_pickaxe in first slot (selected); 8 torch in third slot. Other slots empty.
No armor; Main hand: stone_pickaxe; offhand empty.
Durability: stone_pickaxe 1/131 (inventory slot 0).
Health 7.5/20; food 6/20; saturation 0; air 12/300.
```

An empty starting inventory becomes:

```text
Inventory empty. 36 free storage slots.
Nothing in hotbar.
Wearing and holding nothing.
Health 20/20; food full; saturation 20; air full.
```

Work and evidence use distinct statements. A receipt's acceptance never means physical completion:

```text
Accepted; Work @r28; task SMELT_ITEMS; state QUEUED; phase IDLE; updated tick 3501; collected 0; result ...
Current work:
  Work @r28; task SMELT_ITEMS; state and phase RUNNING; updated tick 3501; message opening_furnace.
Evidence after 160 through 166.
Event 166 at tick 3501: work.changed: same snapshot as current work @r28.
```

The event alias is allowed only when the complete projected payload equals the current snapshot. Similar states, missing fields and earlier failures do not qualify.

## Spatial summaries and permitted loss

`inspect_world` defaults to `detail=summary`. `detail=blocks` returns individual block or placement-site records for exact inspection. Both retain the same observed-position authorization and result limits.

Within returned observations, greedily merge equal materials/states into completely filled horizontal rectangles. Merge along X, then extend whole rows along Z, at one block Y. Never fill a hole, cross an unloaded or omitted cell, combine different block states, or infer that a surface is a usable room/floor. The same procedure works for air patches and layered geometry. Prefer the existing grid/individual representation if it is shorter or no useful patch exists.

For example, 25 observed dirt cells become:

```text
5x5 horizontal patch at block Y=63, X=10..14, Z=20..24: dirt; distance 0..4.
```

This describes a flat dirt patch. Feet clearance and a safe route require separate observations. A floor block at Y=63 does not itself prove a standing position at feet Y=64 is clear or supported by a full collision surface.

Permitted losses in patch summaries: per-cell distances become a range; routine negative material flags are omitted. State properties, positive replaceability/fluid flags, coordinates, material identity, unknown/unloaded cells, and result truncation remain visible. Detail mode restores individual flags and distances. Displaying “nothing in hotbar” omits the inconsequential selected index when every slot is empty. “None” can stand for explicit empty values; it never replaces unknown/null observations.

Placement summaries preserve target coordinates, support relation/material/state, distance, reach, standing position and nearby required-block matches. Door state remains explicit; reach does not imply line of sight, and a placement candidate does not guarantee a route.

## Verification

Tests use captured follow-ups from the successful iron-pickaxe run `20260914-161847-819095-92787` as realistic input fixtures. They check shorter output, objective text, event identity/ranges, unknown extension fields, rejection/hold evidence, same-request aliases, and protocol/native-history preservation.

Spatial cases cover a 5x5 patch, exact detail output, partial results, an unloaded hole, a modded waterlogged slab, and placement candidates with differing reach/door state. Player cases cover empty/sparse hotbars, empty selection, partial observations, both hands, armor, modded IDs, low durability and abnormal vitals. Character reductions are measured separately from provider token usage; fixture replay is not a live gameplay result.

Live follow-up D161 passed iron-pickaxe but used more total tokens (3.05M versus1.58M) due to a longer trajectory. It also exposed goal-continuation wrappers bypassing prose rendering and the in-game projected conversation retaining native IDs. The formatter now handles complete context paragraphs within wrappers, and the panel uses the model's shared reference table. Canonical journal/export records remain native. See D161 for measurements and the distinction between the run and post-run fixes.

## Possible player item offers

`social.item_offered` records a possible offer before pickup and wakes the planner with an autonomous `SYSTEM` trigger. It respects event policy and coalesces pending triggers by likely player UUID. The semantic feed retains each offered stack separately. Collection and acknowledgment remain planner decisions; `pickup.item_picked_up` is still the separate evidence of collection.

The client infers the source and intent because vanilla item tracking does not synchronize the thrower. A newly observed item must be at most five client ticks old, within one block of exactly one living, non-spectating player's drop position (eye height minus 0.3), and moving in that player's facing direction toward Airicraft. Self-drops are excluded. The item must be within six horizontal blocks and 2.5 vertical blocks, moving horizontally at least 0.1 blocks/tick, with directional alignment at least 0.7 and a projected horizontal miss distance no greater than 1.5 blocks. These are conservative heuristics, not proof of a gift or a collision-free path.

The first observation after world entry/reset establishes a baseline. Each loaded item UUID is evaluated once; stack metadata may arrive up to five ticks later without losing the original motion evidence. Payloads include likely player name/UUID, item entity UUID, item ID/count, current/spawn position, initial velocity, dimension, observation tick, and `inferred=true`. Overlapping players, delayed observations, and offers without clear horizontal motion may be missed. Other item sources with matching geometry can produce false positives. Multiplayer behavior still requires live validation.

## Combat resolution summaries

`reflex.resolved` includes `combatSummary` for episodes that entered mob combat. The summary retains each participant across threat filtering and distinguishes `confirmedDead`, `surviving`, and `unconfirmed` targets by UUID, name/type, last observed health, and distance. It also reports duration in ticks, health before/after, cumulative observed health loss, and the resolution reason. Healing does not erase previously observed health loss; damage before the reflex began or between unsampled health updates is not included.

A death requires the client's dead state or `RemovalReason.KILLED`; disappearance, unloading, lost line of sight, and leaving engagement range are not kills. A loaded survivor outside engagement range still appears as alive. Confirmed deaths persist after entity removal. Deaths describe observed outcomes without assigning kill credit to Airicraft. Episode state clears on resolution, reset, and the next episode.

The autonomous resolution trigger includes the readable summary, resolution position, and the full structured event as fields. Semantic context preserves the summary as well. The planner can use confirmed outcomes directly and only needs to inspect facts that remain unknown or may have changed. Existing tactical-hold and resume instructions still apply. Focused tests cover death retention, missing/reappearing targets, survivors, health recovery, episode isolation, and planner/context delivery; live multiplayer validation remains separate.
