# Airicraft Agent Notes

## Current Project State

- This repo is a Fabric mod for Minecraft `1.21.8`.
- It uses Mojang official mappings with Parchment parameter names, not Yarn.
- Java target is `21`, but the Gradle build JVM must be JDK 25+ (Fabric Loom `1.18` requires JVM 25). `.java-version` pins `25`; compile/test/runClient use a JBR 21 toolchain (`JvmVendorSpec.JETBRAINS`), auto-detected or downloaded via the Foojay resolver.
- Gradle wrapper is `9.7.1` (Loom `1.18` requires Gradle 9.7+).
- The build is a multi-project Gradle build with:
  - root project: Fabric mod
  - `wrapper/`: standalone Java CLI for agent-driven control
  - `navigation-core/`: pure Java library (no Minecraft dependency) nested into the mod jar: terrain view, movement policy, move catalog, A* search, motor executors, path follower, and the control arbiter

## Build And Run

- Full build: `./gradlew build`
- Mod version comes from axion-release: a `v*` tag on HEAD builds that exact version, other commits build a `-SNAPSHOT`. `mod_version` in `gradle.properties` only seeds tagless repos / SCM failure; `./gradlew printModVersion` prints the resolved version; there is no manual override — release versions exist only as git tags. `AIRICRAFT_RELEASE_CHANNEL=dev` turns a plain release tag into `-alpha` (CI release flow); channel builds fail when HEAD lacks a parseable `v*` tag or the tag shape is disallowed (`-SNAPSHOT` means axion silently skipped the tag). Do NOT run `./gradlew release`/`publish` — axion provides those tasks but this repo releases by pushing `v*` tags for CI; the plugin tasks bypass the dev/main channel checks.
- Requires git submodules: `git submodule update --init --recursive` (build fails on `action-plan-advisor` if absent).
- `ffmpeg` must be on PATH or `PlaytestVideoRecorder`-related tests fail with `IOException` (playtest screen recording spawns it). Install: `brew install ffmpeg`.
- External-driver mode: `scripts/external-driver` (or `scripts/external-driver-evaluator`) launches the client with the embedded planner dormant so an outside agent drives it through the wrapper CLI (`agent tools list|call`); status reports `externalDriverActive: true`. See `.agents/skills/airicraft-external-driver/SKILL.md`. It was called the "Codex driver"; `scripts/codex-driver*` and `-Pairicraft.codexDriver` still work as aliases.
- Normal Minecraft launches (`./gradlew runClient`, `scripts/external-driver`, `scripts/arthas kickstart`) enable all supported mod integrations by default.
- `runClient` starts JDWP by default on `127.0.0.1:5005` with `suspend=n`
- Attach a debugger with `jdb -attach 127.0.0.1:5005` or any JDWP client
- Override JDWP settings with Gradle properties, for example:
  - `./gradlew runClient -Pairicraft.jdwp.port=5006`
  - `./gradlew runClient -Pairicraft.jdwp.suspend=y`
- Compatibility smoke:
  - `scripts/compat run` and normal `runClient` use the production/intermediary client with all integrations; this avoids optional-mod dev remapping conflicts.
  - Use `-Pairicraft.includeCompat=false` only when explicitly testing the bare development client.
  - Debug port: JDWP `127.0.0.1:5007`.
  - Config shared with normal dev: `run/config/airicraft`.
  - Jar cache ignored: `.airicraft-compat/integration/`; never vendor optional-mod jars or copy them into `run/mods`.
  - Setup/list jars: `scripts/compat setup`, `scripts/compat mods`.
  - Test an extra mod (for example Create Fly for 1.21.8) without vendoring it: put its jar in a local directory and pass `-Pairicraft.extraModsDir=<dir>` to `scripts/compat` or `scripts/external-driver`.
  - Verify live: mod list has `airicraft` + `airicraft-journeymap-compat` + `journeymap` + `airicraft-rei-compat` + `roughlyenoughitems`; `airicraft map status` says `available: true`, `preferredProvider: journeymap`; planner still exposes `search_recipes`.
- Evaluation batches:
  - Each `scenarios/<id>/scenario.yml` declares `requiredMods: [journeymap, roughlyenoughitems]` as needed. Omitted or empty means no optional integrations; dependencies of a declared mod are included automatically.
  - Manual evaluator launches: set `AIRICRAFT_EVALUATOR_SCENARIO_MANIFEST` to the chosen manifest path. The evaluation harness sets it per worker; discovery loads no integrations.
  - Use `scripts/run-evaluation-scenarios --scenario <id>` for a serial run.
  - Add repeated `--scenario` options and `--jobs <count>` for isolated parallel clients.
  - Parallel clients use separate game directories and bridge files.
  - Passed worker directories are deleted. Failed, review, stalled, and interrupted directories remain under `run/evaluator-workers/`.
  - A scenario with no planner turn, in-flight planner call, or new agent event for `budget.maxStallTicks` (default 6,000; `0` disables) ends as `STALLED`, a terminal non-pass.
  - Batch clients disable JDWP. Manual evaluator launches keep JDWP on `127.0.0.1:5008`.
- Navigation baseline: `xvfb-run -a python3 scripts/run-navigation-baseline --runs 5` launches an evaluator client, loads a scenario world, builds deterministic courses and drives `navigate_to` without a model (or run `scripts/navigation-baseline` by hand against `scripts/external-driver-evaluator` in a disposable world). See `docs/navigation-baseline.md`.
- Navigation is in-house: there is no Baritone dependency. Users with a Baritone jar in their mods folder should remove it.
- Perception baseline: `scripts/perception-baseline` against the same client walks the evaluator's `notice_walk` course without a model and checks honest noticing (exposed vein noticed, sealed ore never, garbage dropped) and sensor cost. See `docs/perception.md`.
- Arthas CLI live-debug:
  - Cold-start path: `scripts/arthas kickstart` starts `runClient`, waits for the bridge, joins the first saved world, opens LAN, and attaches Arthas for later probes. It is cold-only and fails fast if a client is already running.
  - Manual start: `./gradlew runClient`
  - Attach once: `./gradlew arthasAttach`
  - Default probe interface after attach: `scripts/arthas v`, `scripts/arthas sc 'ai.moeru.airicraft.*'`, `scripts/arthas sm <class> <method>`, `scripts/arthas w <class> <method>`, `scripts/arthas raw 'thread -n 1'`
  - If another JVM owns the default Arthas port, pass the Minecraft port: `scripts/arthas --port 8564 sc ai.moeru.airicraft.ModBridgeServer`
  - If select fails: `jps -lv`, then `./gradlew arthasAttach -Pairicraft.arthas.pid=<pid>`
  - Useful probes: `sc ai.moeru.airicraft.*`, `sm <class>`, `jad <class>`, `thread -n 5`, `dashboard`
  - Useful live observe: `watch <class> <method> '{params, returnObj, throwExp}' -n 1 -m 1 --timeout 10`
  - Useful path cost: `trace <class> <method> '#cost>10' -n 1 -m 1 --timeout 10`
  - Useful call source: `stack <class> <method> -n 1 --timeout 10`
  - Useful history: `tt -t <class> <method> -n 1 -m 1 --timeout 10`; cleanup with `tt --delete-all`
  - Use full class/method patterns. Broad `watch`/`trace`/`tt` can slow or hang client.
  - `ognl`, `vmtool`, `sysprop`, `vmoption`, `redefine`, `retransform`, `mc` mutate runtime. Ask before use.
  - OGNL runs on Arthas thread, not Minecraft client thread. Do not mutate MC world/player state with OGNL.
  - Airibridge = safe domain actions. Arthas = inspect/instrument. JDWP = pause/step.
  - If behavior weird after rebuild, restart `runClient`; Arthas sees loaded old classes until client restart.
- CLI entrypoint: `wrapper/src/main/java/ai/moeru/airicraft/wrapper/AiricraftCliMain.java`
- CLI artifact is built by the `wrapper` subproject as a runnable jar and application distribution.

## Architecture

- The public control surface is the standalone `wrapper` CLI.
- The Fabric mod exposes an internal localhost HTTP bridge.
- Bridge discovery defaults to `~/.airicraft/bridge-state.json`.
- Set `AIRICRAFT_BRIDGE_STATE_FILE` to an absolute path for an isolated client and CLI pair.
- The `airicraft.bridgeStateFile` Java property overrides the environment variable.
- The CLI reads the selected state file, calls the localhost bridge, and deletes stale state if the bridge is unreachable.

## Key Mod-Side Files

- `navigation-core/src/main/java/ai/moeru/airicraft/navigation/`
  - pure pathfinding and movement: `MovementPolicy` (per-request), `Moves`, `PathSearch`, `MoveExecutors`, `PathFollower`
- `navigation-core/src/main/java/ai/moeru/airicraft/control/`
  - `ControlArbiter`, `ControlLease`, `ControlFrame`, `Channel`, `Priority`, `ChannelIntent`: who controls the player, by priority (`REFLEX` over `FOREGROUND` over `BACKGROUND`); a stronger acquire revokes a weaker holder in the same call
- `src/client/java/ai/moeru/airicraft/agent/navigation/`
  - `AiricraftNavigationFacade` implements `NavigationFacade` (the interface every consumer uses; cancellation is synchronous and emits no event); `NavigationPlanner` (off-thread search), `MinecraftMotor`, `NavigationPolicies`, `NavigationOptions` (per-request walk-only and water penalty), `PathfindSettings` (the planner-tunable policy behind `configure_pathfind` and `inspect_pathfind`)
- `src/client/java/ai/moeru/airicraft/agent/control/`
  - `ControlPlane` (the one shared instance applies one merged frame per client tick after the navigation facade ticks), `MovementController` (locomotion lease holder with stuck detection), `Actuator` (hotbar, attack, break and use calls behind a tick-scoped lease; `holdUse` holds the use key), `CameraController`
  - `ActuationGuardTest` fails on any key, slot, rotation, auto-jump or interaction-manager write outside this package. Do not add an allowlist entry; take a lease instead.

- `src/client/java/ai/moeru/airicraft/agent/events/EventCatalog.java`
  - declared event types, producers, routing profiles, and observe visibility
- `src/client/java/ai/moeru/airicraft/agent/events/AgentEventBus.java`
  - event publication, provenance, subscriber delivery, and diagnostic counters
- `src/client/java/ai/moeru/airicraft/ModBridgeServer.java`
  - localhost bridge entrypoint
  - bridge auth, routing, error mapping
- `src/client/java/ai/moeru/airicraft/ClientRuntimeController.java`
  - bridge lifecycle and highlight ticking/rendering
- `src/client/java/ai/moeru/airicraft/HighlightManager.java`
  - block/region highlight registry and rendering
- `src/client/java/ai/moeru/airicraft/SingleplayerWorldService.java`
  - list and join saved singleplayer worlds
- `src/client/java/ai/moeru/airicraft/SavedServerService.java`
  - list and join saved multiplayer servers
- `src/client/java/ai/moeru/airicraft/agent/attention/`
  - `ReferenceAttentionPolicy`: Java constitution (Stage A) and reference Stage B decisions
  - `RuleAttentionPolicy`: Stage B from the GraalJS rules, Stage C clamp, fallback and revert
  - `WakeScheduler`: pending task wakes, G4 admission and G5 release, `PREEMPT`, the supersede budget and coalesce window (ticks), the pending bound; `IdleHook`: goal continuation and idle think
  - `WakePresenter`: planner-trigger prose per event type
  - `AttentionDecisionLog`, `AttentionReplay`: decision history and recorded-run replay
- `src/client/java/ai/moeru/airicraft/agent/perception/`
  - `Sensor`, `SensorRegistry` (order, per-sensor timings), `NoticedMemory`, `Hysteresis`
  - noticing sensors: pure cores (`NotableBlockScanner`, `DroppedItemNoticer`, `EntityNoticer`, `EnvironmentWatcher`) and client adapters
  - `SaliencePolicy`: the salience rule hook (candidates to percepts); `SalienceReplay` re-runs recorded steps
- `src/main/java/ai/moeru/airicraft/rules/` and `src/main/resources/airicraft/rules/`
  - sandboxed GraalJS rule engine, kernel, `lib.js` and the bundled `attention/default.js` and `salience/default.js`
  - `RulesStore` (the planner's versions per hook, rollback, edit rate), `RuleRevert`, and `docs.md` (what `read_rules_docs` returns)
- `src/client/java/ai/moeru/airicraft/agent/rules/`
  - `PlannerRules`: checks a planner edit off the tick and activates it on the tick; `RuleDryRun`: the replay diff
  - `agent/llm/RulesToolProvider`: `inspect_rules`, `update_rules`, `read_rules_docs`

## Key Wrapper Files

- `wrapper/src/main/java/ai/moeru/airicraft/wrapper/AiricraftCliMain.java`
  - CLI command tree, text output, error handling
- `wrapper/src/main/java/ai/moeru/airicraft/wrapper/HttpBridgeTransport.java`
  - bridge HTTP client and stale-state handling

## Current CLI Commands

- `airicraft status`
- `airicraft reload`
- `airicraft worlds list`
- `airicraft worlds join --world-id <id>`
- `airicraft worlds leave` (save and quit to the title screen; use it before stopping a client so the world keeps its progress)
- `airicraft servers list`
- `airicraft servers join --server-id <id>`
- `airicraft player focus`
- `airicraft player look-at --x <x> --y <y> --z <z>`
- `airicraft agent debug chat --message <text>`
- `airicraft agent debug idle-trigger`
- `airicraft agent debug state`
- `airicraft agent debug timeline [--since <entry-id>]`
- `airicraft agent debug navigation plan --x <x> --y <y> --z <z> [--no-exact-y] [--highlight-seconds <0-600>]`
- `airicraft agent debug navigation state`
- `airicraft agent debug ticks state`
- `airicraft agent debug ticks pause [--player-actions] [--output-image <path>]`
- `airicraft agent debug ticks step --debug-session-id <id> --pause-epoch <epoch> [--output-image <path>]`
- `airicraft agent debug ticks continue --debug-session-id <id> --pause-epoch <epoch>`
- `airicraft agent debug trace status`
- `airicraft agent debug trace start --info <names> --window-ticks <ticks> --output <path> [--once] [--entity-query <json>] [--block-query <json>]`
- `airicraft agent debug trace stop --trace-id <id>`
- `airicraft agent debug world metadata --snapshot-id <id>`
- `airicraft agent debug world player-state --snapshot-id <id>`
- `airicraft agent debug world entities --snapshot-id <id> [region, radius, identity, name, type, and state filters]`
- `airicraft agent debug world get-block --snapshot-id <id> --x <x> --y <y> --z <z>`
- `airicraft agent debug world scan-box --snapshot-id <id> --min-* <n> --max-* <n> [--cursor <n>] [--limit <n>]`
- `airicraft agent debug world find-blocks --snapshot-id <id> --min-* <n> --max-* <n> --block-id <ids>`
- `airicraft agent debug world region-stats --snapshot-id <id> --min-* <n> --max-* <n> [--cursor <n>] [--limit <n>]`
- `airicraft world snapshot [--x <x> --y <y> --z <z>] [--radius <0-4>]`
- `airicraft highlights block --x <x> --y <y> --z <z> [--color <hex>] [--duration-seconds <1-86400>] [--overlay-text <text>]`
- `airicraft highlights region --x1 <x> --y1 <y> --z1 <z> --x2 <x> --y2 <y> --z2 <z> [--color <hex>] [--duration-seconds <1-86400>] [--overlay-text <text>]`
- `airicraft highlights list`
- `airicraft highlights clear --highlight-id <id>`
- `airicraft highlights clear-all`
- `airicraft help [command...]`

## CLI Output Contract

- Operational commands print deterministic plain text to `stdout`.
- Success starts with:
  - `status: ok`
  - `command: <command path>`
- Failure starts with:
  - `status: error`
  - `command: <command path>`
  - `error_code: <stable_code>`
  - `message: <text>`
- `help` and `--help` are text-only usage output.
- Exit codes:
  - `0` success
  - `2` CLI parse or validation failure
  - `3` bridge discovery or transport failure
  - `4` bridge/domain/state failure
  - `1` unexpected internal failure

## Current Bridge Endpoints

- `GET /v1/status`
- `POST /v1/reload`
- `GET /v1/worlds`
- `POST /v1/worlds/join`
- `POST /v1/worlds/leave`
- `GET /v1/servers`
- `POST /v1/servers/join`
- `GET /v1/focus`
- `GET /v1/world-snapshot`
- `GET|POST|DELETE /v1/highlights`

## Behavior Notes

- Every routed event gets one attention decision (stage, rule id, reason), summarized by `airicraft agent debug state` (decisions with `--verbose`) and shown in the dashboard Attention view. Recorded runs write `attention-decisions.jsonl`; replay with `./gradlew attentionReplay -Pairicraft.replayRun=<dir>` and `python3 scripts/wake_ledger.py replay-summary <dir>`.
- The companion notices what a player could: sensors hand honest candidates (line of sight, blocks with an exposed face; no X-ray) to the GraalJS salience module (`salience/default.js`, override `config/airicraft/rules/salience.js`), which publishes `perception.*` events. Percepts wake the planner debounced (10 quiet ticks); a percept about the running job's own target does not. Budgets are `perception:` in `agent.yml`. See `docs/perception.md`.
- Wake priority: a reflex start preempts a running planner turn that its new safety epoch made stale, unless the turn already ran a side-effect tool (then it is rejected on completion); both backends cancel the discarded call (`planner.turn_preempted`). Direct guidance supersedes a running turn at most 3 times per 600 ticks, then waits behind it; the coalesce window after a supersede is counted in ticks. The bundled attention rules budget `NORMAL`/`LOW` wakes with a leaky bucket (burst 10, then 12/min; `budget.autonomous_wakes`). See `docs/attention-rules.md`.
- Planner loops: `PlannerLoopWatchdog` notices autonomous turns that change nothing (same tool set 3 times, or 8 turns, with the same items, block position and goal; starting or cancelling work is not progress), tools failing identically 3 times, and the planner cancelling its own running work of one tool 3 times (`abandoned_work`) (`planner.loop_detected`, an `observe.hints` entry, no wake of its own). A no-progress loop that goes on for 3 more turns holds autonomous wakes for 60 s, doubling to 10 min (`planner.loop_held`, gates `G5.planner_loop`/`G8.planner_loop`; tool-queue reviews wait); player messages, damage, high-urgency, attention and reflex wakes pass; `planner.loop_released` wakes the planner again. A `report_to_me` with nothing queued before it is not queued. See `docs/planner-loop-watchdog.md`.
- Provider errors recover without a human: a timeout, transport failure or transient HTTP status (408, 425, 5xx except 501/505) retries the same frozen turn up to 4 times (250 ms, 1 s, 4 s, 16 s); 429 waits for `Retry-After` and is not counted. Three failed turns in a row enter degraded mode for 30 s, doubling per further degraded period up to 10 min; when the cooldown ends (`planner.degraded_cleared`, `reason: cooldown_elapsed`) wakes reach the provider again, and one more failure re-enters it. A successful turn resets the cooldown. `@agent reset` still clears it at once. While degraded, a singleplayer world with no other players is held (`ProviderOutagePause`: server tick rate frozen, client game ticks gated, chat still open) until the cooldown ends; `/v1/agent/status` reports `providerOutagePaused` and `providerOutageMillis`.
- Observations report `current` as changes (inventory `+n (total)`, vitals, block moves, a JSON Patch for the rest); full state comes first, after world changes, gaps and compaction, when the planner calls `observe` itself, and every 20 observations or 6,000 ticks. Both backends.
- Each observe says why the planner woke (`observe.wake`: an event `seqNo`/type, or `goal_continuation`, `idle_think`, `delegation`, `evaluation`, `tool_queue_review`); the evidence is `observe.events` (types the catalog marks `PLANNER`) and `current`; per-event advice is `observe.hints` (`provenance: runtime_hint`). Event wakes, goal continuation, idle think and task wakes carry no prose; standing rules live in `prompts/planner-system.md` and tool descriptions.
- Task-specific planner rules live in skills, not the system prompt: `src/client/resources/skills/<name>/SKILL.md` (frontmatter `name`, one-line `description`, then the body), listed in `skills/index.txt`. The system prompt carries only the one-line index (`SkillToolProvider.promptInstructions`) and the fixed read tool `use_skill` returns a body as a tool result, so the frozen tool prefix holds. Add new task or mod guidance as a skill instead of `planner-system.md` or a provider's `promptInstructions`; a changed index or body changes the wake goldens' system hash.
- Wake behavior is pinned by golden transcripts in `src/test/resources/planner/wakes/`. Update with `AIRICRAFT_UPDATE_WAKE_GOLDENS=1` (the update run deliberately fails), review every golden diff, then rerun without the variable.

- Nether portals: `build_nether_portal` builds the minimal 4x5 frame (10 obsidian; corners take cobblestone or another full block so the top bar always has a support) through the blueprint construction program, lights it and succeeds only when portal blocks fill the opening (otherwise it names the wrong cells). With no coordinates it reuses a lit portal within 16 blocks or picks a flat site within 8. `enter: true` walks into the portal, succeeds on the dimension change and steps out of the arrival portal. Code: `src/main/java/ai/moeru/airicraft/portal/`, `src/client/java/ai/moeru/airicraft/portal/`.
- Obsidian: `collect_obsidian` (`CollectObsidianTaskExecutor`, `MinecraftObsidianEnvironment`) casts and mines obsidian at lava within 12 blocks, needing a water bucket and a diamond pickaxe. It works only from solid ground with no lava beside the body: it pours where the water turns the most lava sources into obsidian and scoops the water back at once, mines only obsidian no lava touches and with solid ground under its drop, and over deeper lava breaks a covering block (losing its drop) to cast the lava under it. `use_block` may also pour water touching lava. The survival reflex's `LAVA` cause walks and jumps the body out of lava toward the nearest dry standing cell before anything else. Its `FIRE` cause does the same for a body standing in a fire block (ghast fireballs leave fire); where water evaporates (the Nether) it never pours its water bucket to put itself out.
- Flint: `collect_flint count=<1-8, default 1>` (`CollectFlintTaskExecutor`, `MinecraftFlintEnvironment`) turns carried gravel into flint without the body moving: it places one gravel in an open cell beside the feet (foot level, else one below with open air over it) on a solid, non-falling, non-interactive block with no water or lava around, breaks it with the best carried tool (never silk touch; fortune first, then speed), waits for the pickup and walks onto a drop only when it stays out of reach within 2.5 blocks (walk only), and repeats until the inventory holds `count` flint. It ends `flint_collected flint=n made=m gravelLeft=g cycles=c` or fails `no_gravel`, `gravel_used_up` (with `flint=n`), `no_place_spot`, `placement_failed`, `drop_lost` or `timeout` (2,400 ticks, or 1,200 per flint wanted). The work watchdog skips it.
- Night shelter: `shelter_for_night maxWaitTicks=<20-14000, default 14000>` (`ShelterForNightTaskExecutor`, `MinecraftShelterEnvironment`) waits out the overworld night in the survival reflex's `EmergencyBunker` (its plan/step/intruder core is public): it digs 3 down where the body stands (blocks up to 100 break ticks), or in one of the 8 directions 1-2 blocks away (walk only) when that column has fluid, falling blocks, open sides or unbreakable blocks, roofs it with a carried non-falling block (dirt, throwaway stone, planks, logs; refused `no_roof_block` up front unless the dig yields dirt or harvestable stone), and waits. Shelter time is day time [11000, 24000) and [0, 300); called outside it, it ends `already_day` without digging; `wrong_dimension` outside the overworld. The wait ends in the morning or after `maxWaitTicks`, but not while a hostile mob is within 6 blocks of the shaft top (up to 1,200 ticks more). While waiting the idle eating hook and `eat_food` run (`waitingInShelter`); a mob inside the shaft is the reflex's. The exit breaks the roof and pillars on carried blocks (filling the shaft back) or digs a staircase into the cheapest safe wall, failing `exit_failed` after 200 ticks without progress. It ends `sheltered_until_morning waited=<ticks> slept=false exit=<pillar|stairs>` (or `sheltered_until_max_wait`), or fails `no_shelter_site`, `no_roof_block`, `dig_failed`, `exit_failed` or `timeout`, each with health, food and position. A reflex pause resumes the same shelter. The work watchdog skips it. Planner guidance is in the `beds` skill.
- Beds: `sleep_in_bed [x y z]` (`SleepInBedTaskExecutor`, `MinecraftBedEnvironment`) sleeps in a bed as one task instead of a raw `use_block`, which reported success whether or not the body lay down. Without a position it takes the nearest bed within 16 blocks the eyes can see (a ray reaches it; no X-ray). Overworld only: `wrong_dimension` elsewhere without touching the bed. It walks (walk only) to a standable cell beside the bed and uses it through the `Actuator`. The server's answers come from `agent/social/GameMessageLog`, which `MessageHandlerMixin` fills with every system message, chat and action bar, with its translation key. By day (outside vanilla's sleep window: clear 12,542-23,459, rain 12,010-23,991, any time in a thunderstorm) it ends `respawn_set` on "Respawn point set" (`respawn_set unchanged=true` on "You can sleep only at night" alone: vanilla sets the point before that check and says nothing when it is already this bed), else fails `respawn_unconfirmed`. At night it waits up to 600 ticks while a `Monster` (not a zombified piglin) is in vanilla's box 8 across and 5 up or down of the bed head, then fails `monsters_nearby` naming types and distances; it confirms `isSleeping()` within 20 ticks of a use, retries up to 3 uses and fails `not_sleeping` with the last server message; asleep, it waits for the wake and ends `slept_until_morning timeOfDay=<n> respawnSet=<bool> slept=<ticks>` once the day time leaves the window or jumps ahead of the client's ticks (night skipped), or fails `woken_early hurt=<bool>` (at once when hurt, else after 40 ticks for the day time to arrive). Also `no_bed`, `bed_unreachable` and `timeout` (2,400 ticks plus the monster wait; asleep, it gets out of bed through `Actuator.leaveBed`). Every result carries health, food and position. A reflex pause keeps the body in bed and resumes the same task. The work watchdog skips it. Planner guidance is in the `beds` skill.
- Food: `hunt_food count=<1-16, default 6> cook=<bool, default true> maxDistance=<8-48, default 32>` (`HuntFoodTaskExecutor`, `MinecraftHuntEnvironment`) hunts passive animals and cooks the meat as one task. It targets adult cows, mooshrooms, pigs, sheep, chickens and rabbits the eyes can see (eye-to-eye line of sight) within `maxDistance`, never babies, named or leashed animals or ones inside a preserved place, and leaves the last 2 adults of a kind alive; nearest reachable first (water and climbs cost more). It walks only (falls of at most 3, water penalty 40, no edits), refreshes the chase route only when the animal moved 2 blocks or the navigator is idle (`EntityInteractionTaskExecutor.shouldRefreshChaseGoal`), hits with the reflex's best weapon on the attack cooldown, picks drops up (`DropPickup`) and skips an animal it has not killed in 300 ticks or cannot reach. It stops when the inventory holds `count` raw meat or nothing huntable is in sight for 400 ticks; with `cook` it then smelts the raw meat through its own `SmeltingProcessManager` with `SmeltingTaskExecutor` children (a furnace or smoker nearby, else a carried furnace it places and reclaims, else one crafted from 8 carried cobblestone-like blocks), choosing a carried fuel by cost and never burning tools. It ends `food_gathered raw=<item xN,...> cooked=<item xN,...> killed=n skipped=m stopped=<reason>` (cooking problems are notes: `cookSkipped=no_furnace`, `no_fuel` or `smelt_failed:<reason>`), or fails `no_animals`, `night` (overworld surface in the sleep window), `no_weapon_and_low_health` (no weapon of 4 damage and health under 8) or `timeout` (3,600 ticks of hunting), each with what was gathered, health, food and position. A reflex pause resumes the same hunt with its counters. The work watchdog skips it. Planner guidance is the `food` skill.
- Strongholds: `locate_stronghold` (`LocateStrongholdTaskExecutor`, `MinecraftStrongholdEnvironment`, geometry in `StrongholdTriangulation`) throws eyes of ender in the overworld, reads each eye's horizontal flight from its client-side entity as a bearing, picks the dropped eye back up, walks about 200 blocks at 45 degrees to the bearing, intersects the bearings by least squares and walks to the estimate, throwing again on the way when it is far and once near it to confirm (`maxEyes`, default 4). An eye that flies down means the stronghold is under the throw point. It never digs; it ends `stronghold_located estimate=x,z` or fails with a reason carrying the best estimate and every bearing. `find_world_features` `end_portal` finds exposed end portal frames. Planner guidance is the `strongholds` skill.
- Exploring: `explore_for featureKind=<forest|water_body|lava_pool|nether_fortress|end_portal> maxDistance=<32-1024, default 320>` (`ExploreForTaskExecutor`, `MinecraftExploreEnvironment`) finds a feature that is not loaded yet. It re-runs the loaded-chunk feature search (`WorldFeatureSearchService.nearest`), then walks outward in ~48-block legs (walk only, `exactY` false, water penalty 50) toward headings with dry ends, new ground and unloaded chunks, and searches again after each leg. It ends `feature_found` with the position or fails `max_distance`, `route_blocked`, `low_health` or `night_fell`, each with the explored extent (start, furthest point, distance walked, legs). Planner guidance is the `exploring` skill.
- Blaze rods: `collect_blaze_rods count=<1-16, default 7>` (`CollectBlazeRodsTaskExecutor`, `MinecraftBlazeEnvironment`) fights blazes at a Nether fortress's blaze spawner as one task. It finds an exposed blaze spawner (`find_world_features` `blaze_spawner`: a spawner whose client-side display entity is a blaze) or a blaze in sight, picks a fighting spot 4 to 12 blocks from it (standable, no lava/fire/magma within two blocks, no neighbour drop deeper than 3, line of sight to the spawner, scored for walls and roof) and routes with `maxSafeFall` 3. Each tick: shield (offhand) or strafe off an incoming small fireball's line, melee a blaze in reach, at 10 health hide out of every blaze's sight and eat (resume at 16), with more than three blazes in sight hide so they come one at a time, fetch dropped rods only from safe ground with no blaze near, shoot farther blazes with a leading bow arc (`ReflexArchery.aimPoint`) or without a bow hide so they fly into reach. It ends `blaze_rods_collected` or fails `no_blazes_found`, `no_safe_position`, `low_health`, `out_of_food`, `wrong_dimension` or `timeout`, each with `rods=n`. While the job is not terminal the survival reflex leaves blazes to it (`SurvivalReflexRuntime.taskOwnsThreatTypes`); other mobs, lava and drowning stay the reflex's. Planner guidance is the `blaze-rods` skill.
- Ender pearls: `collect_ender_pearls count=<1-16, default 12>` (`CollectEnderPearlsTaskExecutor`, `MinecraftEnderPearlEnvironment`) fights endermen as one task in the overworld, the Nether or the End (`wrong_dimension` elsewhere; `raining` at once in overworld rain, which makes them teleport). Once one is in sight (eye-to-eye line of sight within 48 blocks) it picks a pit within 4 blocks: a cell whose block below breaks in at most 100 ticks with solid floor under it, no fluid beside it, no lava or fire around, and no room for an enderman (2.9 tall) in any of the 8 neighbouring columns at the pit level or up to two below (`ringSafe`). It digs that block, drops in and places a roof at feet+2 against a solid neighbour, else on a two-block column it builds beside the pit (3 blocks on flat ground), from carried non-falling blocks an enderman cannot pick up (throwaway stone, planks, logs; never `ENDERMAN_HOLDABLE`). An enderman on the ground beside the pit overlaps the body, but vanilla melee needs eye-to-eye line of sight, which the roof blocks within about 2 blocks; the body hits its legs through the one-block gap. From the closed pit it stares at the eyes of the nearest calm visible enderman 5 to 48 blocks away (one that stays calm for 100 ticks is left for 600), up to 3 angry at once, watches the legs of angry ones and melees any in reach (the reflex's weapon ranking, attack cooldown). Pearls within the body's pickup box come in by themselves; for a farther one on safe ground with no angry enderman within 16 blocks it breaks the roof, walks out (walk only, falls of at most 3), and walks back in and re-roofs. At 8 health it stops provoking and eats in the pit (resumes at 16; `low_health` after 2,400 ticks, `out_of_food` without food below 18 hunger). Without a pit (no blocks, no diggable ground, 3 failed pits) it fights in the open: it provokes only at 14 health or more and one at a time, raises a carried shield between swings and toward an angry one within 6 blocks, and ends `low_health` at 8 with an angry one within 8 blocks. It ends `ender_pearls_collected` or fails `no_endermen_found` (none seen in 600 ticks, or nothing in sight for 2,400), `low_health`, `out_of_food`, `raining`, `wrong_dimension` or `timeout` (6,000 ticks or 1,200 per pearl), each with `pearls=n`. It never shoots an enderman. While the job is not terminal the survival reflex leaves endermen to it (`EmbodiedAgentRuntime.taskOwnedThreatTypes`); the work watchdog skips it. Planner guidance is the `ender-pearls` skill.
- Unarmed flight: without a real melee weapon (the weapon the reflex would equip does less than a wooden sword's 4 attack damage) the combat reflex runs instead of punching (`UnarmedFlight`, action `FLEE`, `reflex.fled_unarmed` with a reason), unless the fight is trivial: one melee mob with at most 10 health, not a creeper, while the body has 80% health. It sprints through the navigator in ~20-block legs to dry open ground away from the threats' centroid (no edits, falls of at most 3, water penalty 40), ends `reflex.resolved escaped_unarmed` once no threat is within 16 blocks (10 if out of sight), and falls back to fighting with `reflex.flight_abandoned` (`no_escape_route`, `outrun`, `armed`, `timeout`). Armed at half health or less, it also runs (reason `outmatched`) when the melee mobs would land what health is left before the weapon kills them (swings at 12 ticks, a 3-damage hit per mob every 20). A bow shot at a ranged mob and the low-health bunker both come first; the bunker waits until no melee mob is within 6 blocks. Against shooters alone, or once a flight from a mixed group is given up, it takes cover instead of punching (`RangedCover`, action `TAKE_COVER`): it walks (same no-edit options) to a standable cell within 8 blocks that no shooter's eye can see, preferring roofs and walls, then short walks, away from the threats, re-picks when a shooter sees it again, and ends `reflex.resolved hidden_from_ranged` (`reflex.took_cover`; `reflex.cover_abandoned` when a melee mob comes within 4 blocks, a creeper within 8, a weapon or bow shot turns up, or no cell is found or reached). A flight given up with `no_escape_route` is tried again after 60 ticks (at most 3 times per episode). A ghast is engaged up to 64 blocks away and a blaze up to 48 (their fireball reach) once it targets the body, is charging, or has hit it; a weapon does not count against ghasts or blazes farther than 6 blocks, so the body takes cover from them unless a bow shot is ready.
- Navigation walks around body hazards (cobweb, berry bush, portal, fire, wither rose, powder snow) and never breaks them, even with `allowBreak`; a request may end inside one only when its goal is exactly that cell or column (`MovementPolicy.hazardEntries`), and a body starting in one may leave it.
- Carried armor that protects more than what is worn is put on automatically (`agent/equipment/ArmorAutoEquip`, one piece per 20 ticks through the player's own inventory menu, so it waits while a container is open; `equipment.armor_equipped`). Zero-armor wearables (carved pumpkin, elytra) and binding-cursed pieces are left to the planner's `equip_item`. The planner reads or replaces the policy with `configure_armor` (`enabled`, `reservedItemIds` kept in the inventory, e.g. armor for another player); it is in observe facts as `armorPolicy` and resets with the agent session.

- `fight_ender_dragon` runs the dragon fight as one task without planner turns (`DragonFightTaskExecutor`): each tick it steps out of breath clouds and fireball paths, hits the head while the dragon perches, otherwise shoots uncaged end crystals with a bow from end-stone spots it walks to (`ArrowBallistics` aims, lobbing over pillar rims), otherwise waits near the exit portal. Routes bridge only off the arrival platform; on the island they walk. It ends `dragon_defeated` or hands back with a reason (`caged_crystals`, `low_health`, `portal_unreachable`, `no_progress`, `fight_timeout`, ...). Caged crystals heal the dragon to full between perches; with a pickaxe and building blocks it opens them while the dragon perches (pillar up beside the cage, break the 3x3 of roof bars, dig its own pillar back down) and then lobs arrows through the hole, otherwise it hands back `caged_crystals` naming what is missing. Survival reflexes ignore the dragon and the work watchdog skips the task; the task drops a route stuck for 70 ticks itself so the dispatcher's `PATH_STUCK` never ends the fight. Stage it with a cheats-enabled world copy and `/v1/player/command`; the arrival platform needs building blocks to bridge.
- Portable stations: a crafting table `craft_recipe` placed from the inventory is broken and picked back up before the task ends, success or failure (`PlacedStationReclaim`: a break-blocks child, then the pickup wait, walking onto a drop out of reach); the result gains `tableReclaimed=x,y,z` or `tableLeftAt=x,y,z reason=...` and a failed reclaim never fails the craft. A furnace `smelt_items` placed (`SmeltingProcessManager.markPlacedStation`) is picked up the same way by `collect_smelted_items` once input, fuel and output are empty, no other process uses it and a pickaxe is carried, else `furnaceLeftAt=x,y,z reason=no_pickaxe|furnace_not_empty|...`; tables and furnaces that stood in the world are never broken.
- Location memory uses one planner interface: `remember_place`, `recall_place`, `list_places`, `forget_place`. JourneyMap is authoritative when installed; `places.json` is used only without it. No import, mirroring, or silent fallback while JourneyMap loads.
- Recall/forget accept exact name or stable ID; duplicate names require IDs. JourneyMap native and death waypoints are ordinary entries. Notes and preserved areas live in waypoint custom data.
- New location-memory consumers use `LocationMemoryService`/`LocationMemoryBridge`; only the fallback provider accesses the local file store. Protection follows the selected backend and fails closed when its data is unavailable.
- Each client automatically owns a read-only LAN debug dashboard. It scans upward from configured port `8765`, uses a viewer token distinct from the control bridge token, and prints the clickable URL in logs, `airicraft status`, and in-game chat.
- The companion plays a character from `config/airicraft/character.json` (Character Card V3, as AIRI exports, plus `extensions.airicraft`), or the built-in generic Minecraft player. It opens both planner prompts, drives idle free time, and voices fixed failure/reset lines. `airicraft reload` applies edits. See `docs/character-card.md`.
- Attention rules (Stage B: which events reach the planner and which wake it) run as sandboxed GraalJS. `config/airicraft/rules/attention.js` overrides the bundled module; `airicraft reload` rejects an invalid override with `invalid_config`, startup reverts to the bundled module. The Java constitution and clamp cannot be overridden. See `docs/attention-rules.md`.
- The planner tunes its own attention and salience modules with `inspect_rules`, `update_rules` and `read_rules_docs` (session-local, `airicraft reload` drops the edits). An edit is loaded, run once and replayed against the recent decision or step log next to the running module; the replay diff is returned and a module that fails to evaluate is rejected. Versions are bounded (8 per hook); a rollback or automatic revert is a new version, and a failing version reverts to the one it replaced (`rules.reverted`). Edits are rate-limited (6 per hook per 12,000 ticks) and refused during a safety hold; `rules.updated` is evidence in `observe`; recorded runs write `planner-rules.jsonl`.
- Hosted playtests: `scripts/hosted-playtest --world <template> --recorder-jar <profile>` hosts a fresh world copy on one fixed LAN port for human testers and records it like an automatic playtest, plus `players.jsonl` and tester Recorder Plays. The planner has no `something_wrong` there; a degraded planner gets at most one automatic operator reset per session instead of ending the run. See `docs/hosted-playtest.md`.
- Live playtest diagnosis: pause server ticks, query the rolling decision history, and export the incident before rebuilding. See `docs/live-playtest-recording.md` for CLI queries, selective frames, playback, and loss checks.
- Dashboard observations include full LLM envelopes, runtime snapshots, decision states, events, and sparse client RGB. The default window is 12,000 completed server ticks, capped at 64 MiB; tick-debug pause freezes it. Pixel-identical frames are skipped before encoding.
- Dashboard export is replayable JSONL. The dashboard must never expose bridge mutation routes or backpressure the client tick when a viewer is slow.
- The bridge is tied to the Minecraft client process, not world load state.
- `airicraft reload` and `/airicraft reload` reload both config files live without restarting the client.
- Reload preserves the bridge session, highlights, and current world connection, but resets active agent/planner/task state.
- `airicraft status` is a probe command and still exits `0` when Minecraft is unavailable, reporting `available: false`.
- World-bound read/action commands still return `world_not_loaded` when no world is active.
- `airicraft worlds join` and `airicraft servers join` return `already_in_world` if a world is already loaded.
- `airicraft servers list` can still safely enumerate saved servers while out of world.
- `airicraft servers join` opens a normal login connection, never a server transfer (`SavedServerService.startLogin` passes no `TransferState`), so it can join LAN-opened worlds, which reject transfers with `transfers_disabled`. To join a LAN world from a second client, add its address to that client's saved server list.
- Highlights support:
  - persistent by default
  - optional timeout
  - custom `overlayText`
  - block and region highlights
  - list, clear-one, clear-all

## Important Caveat

- If behavior changes in bridge handlers do not appear in a running dev client, restart `runClient`.
- A running Minecraft dev process keeps the old classes loaded even if the repo has already been rebuilt.
- The in-mod verification scenarios are stateful. Running multiple planner/follow scenarios back to back in one client session can produce cross-scenario interference.
- The batch evaluator avoids this interference because each scenario uses a separate client and game directory.
- In particular, `llm.degradation_goal_preserved` intentionally drives the runtime into degraded mode before reset, so later planner/follow scenarios should be run individually or after restarting `runClient`.

## Agent skills

### Issue tracker

Issues are tracked as GitHub issues on `shinohara-rin/airicraft`, managed with the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-label vocabulary (`needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout: root `CONTEXT.md` plus `docs/adr/`. See `docs/agents/domain.md`.
