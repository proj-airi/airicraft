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
| `POST /v1/episode` | start episode `{arena, policy, maxTicks?, obsRadius?}` |
| `GET /v1/episode?id=` / `POST /v1/episode/stop?id=` | query/stop |
| `POST /v1/reset` | reset arena `{arena}` |
| `POST /v1/tick` | `{mode: freeze|run|sprint, ticks?}` |

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
- `SPRINT` — only the dedicated sprint thread's `server.tick()` calls pass;
  used to burst N ticks without waiting for real time

`SimRuntime.beforeTick/afterTick` bracket each accepted tick: `preTick`
(snapshots obs → policy decides on `obsDelayTicks`-stale obs → queues intent)
and `postTick` (damage accounting, executor events, termination checks).

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

`CombatPolicy { id(); reset(); Intent decide(JsonObject obs) }` consumes the
delayed observation JSON — the same contract a learned policy will use.
`baseline-melee` is a hand-tuned melee script (approach/orbit/flee-centroid +
cooldown-gated attack) that clears a 4-zombie crowd on a flat arena.
`idle` does nothing. Parameterized policies tuned by CMA-ES land here later.

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
