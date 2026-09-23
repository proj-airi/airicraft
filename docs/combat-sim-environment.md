# Combat Sim Environment (`sim/`)

Server-side training environment for optimizing a Minecraft combat policy with
machine learning. Phase 1 implements the trustworthy-simulation layer: a fake
player driven by strictly-legal inputs, an explicit tick protocol, arenas with
reset, privileged-state observations, and episode recording — all inside the
dedicated server (no client).

The client mod (`airicraft`) remains the eventual porting/smoke-test target;
the sim mod (`airicraft-sim`) is server-only (`environment: "server"`).

## Running the dev server

```
./gradlew :sim:runServer
```

The dev server uses `sim/run/sim-server` as its game directory. Recommended
`server.properties` values (already present in a dev-generated run dir):

- `online-mode=false`, `level-type=minecraft\:flat`, `spawn-monsters=false`
- `max-tick-time=-1` — sprint-mode burst ticking must not trip the watchdog
- `pause-when-empty-seconds=-1` — fake players are not counted for auto-pause,
  so without this the server freezes itself after ~60s
- `view-distance=6`, `simulation-distance=4` — keeps sprint cost low

A control HTTP API listens on `airicraft.sim.port` (default `8777`).

## Control API (`SimHttpControl`)

All mutating endpoints run on the server thread and block the HTTP caller until
done (max 30s).

| Method/Path | Purpose |
|---|---|
| `GET /v1/status` | serverTick, gate mode, arenas, episodes |
| `GET /v1/observe?arena=&radius=` | live privileged-state snapshot |
| `POST /v1/arena` | create/reset arena `{name, world?, center:[x,y,z], size?, yaw?}` |
| `POST /v1/player` | spawn fake player `{arena, name?, pos?, yaw?}` |
| `POST /v1/spawn` | spawn mobs `{arena, type, pos? | count?, minDist?, targetPlayer?}` |
| `POST /v1/equip` | equip player `{arena, items:[{slot,id,count?}]}` |
| `POST /v1/episode` | start episode `{arena, policy, params?, maxTicks?, obsRadius?}` |
| `GET /v1/episode?id=` / `POST /v1/episode/stop?id=` | query/stop; non-running replies include `score` |
| `POST /v1/reset` | reset arena `{arena}` — rebuilds the platform, wiping terrain features |
| `POST /v1/terrain` | place blocks `{arena, block, pos:[[x,y,z],...]}` — region-bounded, y ∈ [floor, floor+3] |
| `POST /v1/tick` | `{mode: freeze|run|sprint, ticks?}` — `sprint` is synchronous: the HTTP call returns after the batch has ticked |

## Architecture

### Fake player (`fake/`)

`FakePlayerEntity extends ServerPlayerEntity` joined through a no-op
`FakeClientConnection`. Three vanilla mechanics had to be re-enabled for a
packet-less player:

- **`canMoveVoluntarily()` / `canActVoluntarily()`** — `PlayerEntity` returns
  `false` on the server (movement is client-authoritative). Overriding to
  `true` restores the full input→`travel()` physics path.
- **`playerTick()`** — vanilla drives entity physics from
  `ServerPlayNetworkHandler.tick()` via the connection loop, which never fires
  for a fake connection. `FakePlayerEntity.tick()` calls `playerTick()`
  directly each tick.
- **`lastAttackedTicks`** — the real client increments it inside the
  move-packet path; incremented manually so attack cooldowns advance.

### Legality executor (`input/`)

`SimInputExecutor` translates a policy `Intent` (look target, move dir,
jump/sprint/sneak, attack press, use-item) into the same state a real client
produces — never calling `attack()` directly on a desired target:

- `ActionProfile` pins human-limit invariants: `maxTurnDegPerTick` (45°),
  `attackIntervalTicks` (2t), `reachBlocks` (3.0), `aimToleranceBlocks`,
  `obsDelayTicks` (3t observation delay fed to the policy).
- Look: intent resolves to a world point; yaw/pitch move toward it clamped per
  tick — no instant 360° snaps.
- Attack: rate-limited press-attack. A press is only *executed* if the current
  crosshair ray (from the clamped head yaw) actually intersects a living
  entity's bounding box within reach (`canInteractWithEntity` + expanded-box
  raycast). Rejections are logged as events.
- Movement: writes `forwardSpeed/sidewaysSpeed/upwardSpeed/jumping`, exactly
  what client input processing writes (×0.98 scaling included).

### Tick protocol (`tick/`, `mixin/`)

`SimTickGate` has three modes injected at `MinecraftServer.tick` head:

- `RUN` — normal ticking (default)
- `FREEZE` — every server tick is cancelled; the world is fully paused
- `SPRINT` — the gate stays open while the server thread itself bursts N
  `server.tick()` calls back-to-back (synchronous HTTP response after)

`SimRuntime.beforeTick/afterTick` bracket each accepted tick: `preTick`
(snapshots obs → policy decides on `obsDelayTicks`-stale obs → queues intent)
and `postTick` (damage accounting, executor events, termination checks).

Sprint batches run on the **server thread** via `server.execute` — a dedicated
sprint thread calling `server.tick` races the main loop's chunk/task drain and
crashes the world state. Throughput: ~1100 ticks/s (~55× realtime).

### World rules for training

`applyWorldRules` pins `DO_DAYLIGHT_CYCLE`/`DO_WEATHER_CYCLE` off and sets the
clock to 18000 (midnight): zombies/skeletons must not burn mid-episode.

Arena creation **force-loads** its chunks (`setChunkForced`) — entities spawned
into a not-entity-loaded chunk go to pending storage and are invisible to
`world.getEntitiesByClass` until they materialize later. Required episode
recipe: create arena → warm-up ticks → spawn → settle ticks → start episode.

`reset()` also clears player inventory (re-equip afterwards) and revives a
dead player (`reviveForSim`). A player dead ~20+ ticks is `remove()`d by
vanilla `updatePostDeath`; a removed entity can never be teleported or ticked,
so `resetPlayer` swaps in a fresh `FakePlayerEntity` carrying over the
executor.

### Arena + spawn rules (`arena/`, `spawn/`)

`Arena` is a named world region (center + half-size) with a flat platform,
player spawn point, and a tracked-mob set. `reset()` removes all living
entities/projectiles/items in the region (except fake players) and restores
the player to a clean baseline.

`SpawnRules` enforces the user's constraint: external/manual spawning is
allowed but **never on the player's face** — `DEFAULT_MIN_DISTANCE` 5m
(hard floor 2m via `ABSOLUTE_MIN_DISTANCE`). Both explicit positions and random
`pickSpawnPos` go through the same distance check plus `isSpaceEmpty`.

### Episodes (`episode/`, `observe/`)

An episode records JSONL: `{"type":"tick",t,obs,intent}` per tick,
`{"type":"event",t,name}` for executor/kill/death events, and a final
`{"type":"end",score:{outcome,ticks,kills,damageTaken,damageDealt,remainingMobs}}`.
Logs land in `<run>/sim/episodes/`. Outcomes: `PLAYER_DIED`,
`ALL_MOBS_CLEARED`, `TIMEOUT`, `STOPPED`, `ERROR`.

`ObservationSnapshot` is privileged state (no vision): player pos/vel/health/
food/yaw/pitch/onGround/cooldown/lastAttackedTicks/speeds/using/sprinting/
hands, plus all entities within radius sorted by distance (id, type, pos, vel,
dist, tracked, hostile, health, targetingPlayer).

### Policies (`policy/`)

`CombatPolicy { id(); reset(); configure(params); Intent decide(JsonObject obs) }`
consumes the delayed observation JSON — the same contract a learned policy
will use. `configure` applies optimizer-supplied tunables (`params` in
`POST /v1/episode`) before the episode starts.
`baseline-melee` is a hand-tuned melee script (approach/orbit/flee-centroid +
cooldown-gated attack) exposing 8 tunables: `engageDistance`, `engageSlack`,
`sprintBeyond`, `crowdRadius`, `crowdThreshold`, `attackRange`,
`minLastAttackTicks`, `strafeFlipTicks`. `idle` does nothing.

## Optimizer (`sim/optimizer/`)

`optimize_cmaes.py` runs the full loop over the HTTP API: N parallel arenas →
an evolutionary algorithm samples policy tunables → each candidate is
evaluated on a freshly-sampled **shared scenario set** (common random
numbers) → tell. Sprint makes one evaluation batch (~650 ticks × 8 arenas)
take ~0.5s.

### Multi-objective mode (default): `--algo nsga2`

No scalarization. Each candidate's objective is the raw 5-dim metric vector
`[kills, −damageTaken, clear, survived, −ticks]` (all maximized), averaged
over `--nscen` random scenarios drawn fresh per generation and shared across
the population. Selection is NSGA-II (non-dominated sort + crowding
distance); per-generation progress is tracked as the front's hypervolume in
a fixed reference box. The final report re-evaluates the surviving front on
a large fresh scenario set and dumps every frontier member's params +
metrics so a trade-off can be chosen *after* optimization.

```
python3 sim/optimizer/optimize_cmaes.py --algo nsga2 --gens 15 --pop 8 --nscen 12
```

Randomization axes per scenario: mob mix (weighted zombie/skeleton/creeper/
spider), count 3–7, formation (ring/arc/cluster/pincer), radius 5.5–9 with
±0.8 baked jitter re-clamped to ≥5.5m and ≥1.6m between spawn points, plus
terrain features via `POST /v1/terrain` (pillars, walls, mounds, water
pools, cobwebs; ≥2m from player, ≥1.6m from mob points).

### Scalar mode (legacy): `--algo cmaes`

`J = 100·kills + dealt − 3·taken + 60·clear − 500·death − 0.2·ticks` —
kept for reference/comparison only.

`kills`/`damageDealt` are **player-credited** (`SimRuntime.isPlayerCredit`
on the last `DamageSource` seen per mob): damage by the player, mob-vs-mob
friendly fire, and falls count — baiting mobs into each other or off ledges
is positioning strategy. Explosions, entity cramming, and suffocation do
not. `damageTaken` is raw player health loss.

Result (NSGA-II, 15 gens, pop 8, nscen 12): front hypervolume 92.5k → 109.7k
(+19%); the final frontier on 24 fresh scenarios spans survived∈[0.42,1.0],
clear∈[0.42,0.875], ticks∈[118,275], damageTaken∈[2.1,19.1] — exposing an
explicit never-die/careful vs fast/aggressive trade-off axis the scalar
fitness could not express. Outputs land in `results_mo*/`
(`history.jsonl`, `final_report.json`).

### Structural search (GP): `optimize_gp.py`

`ast` policy (`AstPolicy.java`) interprets a JSON rule list shipped via
episode `params`: ordered rules `{when: <cond-tree>, act: <action>}`, first
match wins. Conditions are and/or/not trees over scalar obs features
(distances, per-type counts within a radius, self/mob HP, cooldown) plus
nearest-type substring checks; actions resolve a target
(`nearest|lowestHp|ranged|melee|farthest|centroid`) and a movement mode
(`approach|flee|orbit|kite|hold`) plus an attack rule.

`optimize_gp.py` evolves these programs with subtree mutation (constants,
features, comparators, rule add/remove/reorder, NOT wrap) and rule-swap
crossover under the same shared-scenario-set NSGA-II selection:

```
python3 sim/optimizer/optimize_gp.py --gens 18 --pop 12 --nscen 12 --seed 8
```

Result so far: template-seeded GP expands the trade-off front (HV 71k→103k
during search) but no evolved program strictly dominates the hand baseline
on the fresh 24-scenario eval — gains are trade-off shifts (e.g. +kills
against −survived). A hand-probed "charge ranged attackers first" template
does sit non-dominated vs baseline on a paired 24-scenario set (more kills,
higher survival, at the cost of more damage taken and slower clears) —
evidence the structural space holds real improvements that parameter search
cannot reach.

### MAP-Elites (diversity-preserving): `optimize_mapelites.py`

The archive is a 64-cell behavior grid — (mean kills) × (mean damage taken)
— each cell keeping up to 3 non-dominated elite programs. Emitters mutate /
crossover cell elites with novelty-biased cell selection, so niche
specialists survive instead of being dominated out. Extra action primitives:
`use:"off"` (shield block), `stopUse`, `jump` (crits), `zigzag` (serpentine
approach), `type:<mob>` / `targeting` targets; extra features
`targetingCount`, `mobHpSum`, `usingItem`. Players carry sword + shield.

```
python3 sim/optimizer/optimize_mapelites.py --gens 25 --pop 12 --nscen 12 --seed 11
```

Result (25 gens): training coverage 39% of the grid; the evolved standout is
a **shield-kite** structure (`use:"off"` + kite zombies at ~1.9m + always
attack): on 24 fresh scenarios — **100% clears, 100% survival, damage taken
4.9** (baseline: 87.5%/87.5%/10.7) at the cost of ~25% slower clears. A
second elite reaches baseline-level kills (3.0) with 40% less damage taken
(6.4 vs 10.7) and better survival (91.7%) — again only slower. Both are
non-dominated vs the hand baseline: the frontier now contains points the
baseline cannot reach in survival and damage-avoidance.

## Verified end-to-end

- Fake player joins, moves under its own physics, looks, and kills mobs.
- Rate-limit and no-legal-target rejections appear in the log.
- `freeze` pauses the sim clock; `sprint` bursts ticks.
- Baseline melee vs 4 zombies: `ALL_MOBS_CLEARED` in 198 ticks, 80 damage
  dealt, 6 taken.

## Known limits / next steps

- `damageDealt` is estimated from per-tick mob health diffs (counts overkill).
- Policy quality: the baseline wins easy fights but is not tuned — that's the
  optimizer's job (CMA-ES over tunables, then AST mutation).
- PvP (fake player vs fake player), terrain variety, and loadout randomization
  are the next environment features.
