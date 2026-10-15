# State document and READING form (v0)

Specification of what System 1.5 reads and writes. Schema version `reading-v0`
(`s15.reading.SCHEMA_VERSION`); labels and predictions record it, and any change to slots, allowed values or
serialization bumps it. Reference implementation: [`s15/statedoc.py`](../../../research/system15/s15/statedoc.py) and
[`s15/reading.py`](../../../research/system15/s15/reading.py).

## Sections

One document, rendered as Markdown-style headed sections in this order. The order is by volatility, so the stable
prefix can stay in the prompt cache and only the tail is re-encoded on most refreshes.

| Section | Writer | Content | Rendering source |
| --- | --- | --- | --- |
| `SELF` | config | Identity, hard rules, the contract that 1.5 only fills READING | `DEFAULT_SELF` or `--self-file` |
| `OBJECTIVE` | System 2 | Objective id, status, text; constraints; completion criteria; named decisions; travel restrictions | `current.objective`, `current.travelRestrictions` of the latest decision context |
| `PLAN` | System 2 | The last two System 2 decisions whose responses had arrived by the document's tick: tool calls with arguments, reply text | `llm-calls` (`parsedResponse`, dispatch tick + latency) |
| `NOW` | System 1 | Tick, decision/actuation owner, dimension, vitals, position and contact flags, reflex state and threats, current work, inventory, other `current` keys as compact JSON; age of this snapshot when it predates the document | `current` of the latest canonical decision context |
| `RECENT` | System 1 | Semantic events since that context, newest 16, always keeping the last four `social.*` lines; semantic one-liners for chat, slow-mining notices, damage and work changes, compact JSON otherwise | `events.jsonl`, `semantic_event` observations, or `/v1/agent/events/recent` |
| `READING` | System 1.5 | The form below; lives in the decoder canvas, never in the prompt | filler output |

Rules:

- Unknown `current` keys and event payloads pass through as compact JSON, following the repository's
  semantic-input standard (preserve distinctions, no lossy generic transformation).
- A section is **dirty** when its content changed, ignoring tick counters and snapshot ages
  (`statedoc.fingerprint`). A READING slot is dirty when any section it depends on is dirty.
- Documents contain no information from after their tick: `PLAN` only includes decisions whose response had arrived
  (dispatch tick plus recorded latency at 20 TPS), and hindsight labels are stored separately.
- Offline documents are made at every System 2 decision boundary (`kind: decision`) and, with
  `--granularity event`, at every salient event in between (`kind: event`: chat, combat, reflex start/resolve/threat,
  task notices, failures, completions, cancellations, physical episodes, deaths). Live shadow documents
  (`kind: live`) are made every refresh and add a `Live reflex`/`Live task` line polled at that moment.
- Decision contexts come from the canonical planner conversation and carry native IDs; they are copied as-is.

## READING v0

Serialized as one line of JSON with this exact key order; every value is a string.

| Slot | Kind | Values / limit | Default | Inputs | Drives after Gate B |
| --- | --- | --- | --- | --- | --- |
| `situation` | text | ≤ 20 words | `""` | OBJECTIVE, PLAN, NOW, RECENT | nothing (dashboard, System 2 context) |
| `chat.intent` | enum | none, stop, wait, follow, come_here, go_home, protect, dont_attack, give_item, question, smalltalk, complex | none | RECENT | via `escalate`, `fix`, `say` |
| `chat.target` | ref | @r reference, player name or none, ≤ 3 words | none | RECENT, NOW | – |
| `threats.{0,1}.ref` | ref | ≤ 2 words | none | NOW, RECENT | – |
| `threats.{0,1}.stance` | enum | ignore, fight, avoid, protect | ignore | NOW, RECENT, OBJECTIVE | per-entity reflex filter (needs a `ReflexPolicy` extension) |
| `reflex.combat` | enum | on, off | on | NOW, RECENT, OBJECTIVE | `configure_reflex.combatEnabled` |
| `reflex.max_threat_distance` | enum | 4, 8, 12, 16, 24, 32 | 16 | NOW, RECENT, OBJECTIVE | `configure_reflex.maxThreatDistance` |
| `reflex.require_los` | enum | yes, no | yes | NOW, RECENT | `configure_reflex.requireLineOfSight` |
| `anomaly.kind` | enum | none, wrong_tool, stuck, no_progress, hazard, low_food, low_health, drowning, lost, inventory_full, other | none | NOW, RECENT | via `fix`, `escalate` |
| `anomaly.detail` | text | ≤ 12 words | `""` | NOW, RECENT | – |
| `fix.action` | enum | none, equip, eat, retreat, pause_work, resume_work, look_at, ask_s2 | none | NOW, RECENT | `equip_item`, hold/resume, look-at (reversible only) |
| `fix.arg` | text | ≤ 6 words | none | NOW, RECENT | argument of the above |
| `say` | text | ≤ 16 words | `""` | RECENT | chat acknowledgement |
| `escalate.level` | enum | no, soon, now | no | OBJECTIVE, PLAN, NOW, RECENT | `PlannerTriggerType.SYSTEM` wake |
| `escalate.reason` | enum | none, new_instruction, plan_invalid, threat, anomaly, goal_done, uncertain | none | OBJECTIVE, PLAN, NOW, RECENT | wake reason |

Defaults for `reflex.*` equal `ReflexPolicy.defaults()`. The labelling rubric (what each value means) is
`s15.prompts.RUBRIC`; the same text goes to the teacher, the AR baseline and the diffusion prompt.

### Parsing

`reading.parse` never raises. It takes the first complete JSON object in the text (ignoring canvas padding after
it), repairs truncation (closes strings and containers, drops a dangling key, strips trailing commas), snaps enum values
(case, spaces and hyphens normalized; a unique prefix match is accepted but marked inexact), clips text to its word
limit, and fills missing slots with defaults. `exact` records whether each value was valid as written, `present`
whether it appeared at all. Scores use the snapped values; format validity is reported separately.

### Canvas layouts

- **Free**: the canonical JSON tokenized as-is, padded with EOS to 256 tokens. Slot positions move when value lengths
  change, so per-step slot values are found by re-parsing the argmax canvas.
- **Fixed-width** (`--clamp-template`): keys and punctuation are tokenized once and clamped every step; each value has a
  fixed token budget padded with spaces: enums get the longest value plus one token, refs 5, `situation` 32,
  `anomaly.detail` 20, `fix.arg` 10, `say` 24. The layout must fit the 256-token canvas; the harness raises an error
  otherwise. Slot positions never move, so trajectories are exact per slot.

### Warm start

For a refresh with a previous READING: build the layout from the previous values, replace the tokens of dirty slots
with uniform random tokens (`--no-renoise` keeps them), and run the last `warm_steps` of the 48-step temperature
schedule (0.8 → 0.4). Without a previous READING (first refresh of a run, or `--cold`) the canvas is fully random
(template clamped in fixed-width mode) and runs the full schedule. `--carry-self-conditioning` also feeds the previous
refresh's final logits as step-0 self-conditioning.

### Settledness and commit rule (Phase 2)

A slot may drive anything only when all hold:

1. its value was unchanged over the last k denoising steps of the refresh (default k = 2) and its mean token entropy is
   below the calibrated threshold from E3;
2. the value equals the previous refresh's value (two-refresh hysteresis);
3. the staleness guard passes (same world, objective, travel restrictions, guidance and safety epoch as the document;
   health not lower), as in `PolicyContinuationPlanner`;
4. the slot's rate limit allows it (at most one actuation per slot per 2 s).

Under an active threat, `reflex.*`, `threats.*` and `escalate` use k = 1 and one refresh of hysteresis.

## Example

Built from the bundled fixture (`research/system15/tests/fixtures/run-a`): a real decision context from the
iron-pickaxe run with a synthetic slow-mining notice added. SELF and OBJECTIVE omitted.

```text
## PLAN (System 2)
Decision at tick 1110: cancel_work({"workId":"@r9"}) — said: Stopping. Coming to you, Alex.
Decision at tick 3502: wait_for_work({})

## NOW (System 1)
As of tick 3502; decisions: controller; actuation: work.
Dimension: overworld
Vitals: health 20, air 300, food 20
Position (2.5, 68, 13.2); grounded
Reflex IDLE epoch 0; threats: none
Work @r28 SMELT_ITEMS state RUNNING: opening_furnace
Work @r31 furnace: minecraft:iron_ingot state WAITING phase COOKING
Inventory: 1 coal, 9 cobblestone, 11 dirt, 3 raw_iron, 1 spruce_planks, 4 stick, 1 stone_pickaxe, 1 wooden_pickaxe
session: SINGLEPLAYER_LAN_HOST
(NOW is 98 ticks old; see RECENT.)

## RECENT (System 1)
[t3600 #167] slow mining: breaking stone holding furnace for 120/480 ticks; best carried tool stone_pickaxe (slot 34)
```

Hand-labelled READING (`teacher-gold.jsonl`):

```json
{"situation": "mining stone while holding a furnace", "chat": {"intent": "none", "target": "none"}, "threats": [{"ref": "none", "stance": "ignore"}, {"ref": "none", "stance": "ignore"}], "reflex": {"combat": "on", "max_threat_distance": "16", "require_los": "yes"}, "anomaly": {"kind": "wrong_tool", "detail": "holding furnace, stone_pickaxe carried in slot 34"}, "fix": {"action": "equip", "arg": "stone_pickaxe"}, "say": "", "escalate": {"level": "soon", "reason": "anomaly"}}
```

## Changing the schema

Add or change slots in `s15/reading.py` (`SLOTS`), update the rubric in `s15/prompts.py`, bump `SCHEMA_VERSION`,
re-label with `label-teacher`, and keep old labels only for their version. The fixed-width budget must still fit the
canvas.
