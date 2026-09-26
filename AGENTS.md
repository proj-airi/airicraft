# Airicraft Agent Notes

## Current Project State

- This repo is a Fabric mod for Minecraft `1.21.8`.
- It currently uses Yarn mappings, not Mojang official mappings.
- Java target is `21`, but the Gradle build JVM must be JDK 25+ (Fabric Loom `1.18` requires JVM 25). `.java-version` pins `25`; compile/test/runClient use a JBR 21 toolchain (`JvmVendorSpec.JETBRAINS`), auto-detected or downloaded via the Foojay resolver.
- Gradle wrapper is `9.7.1` (Loom `1.18` requires Gradle 9.7+).
- The build is a multi-project Gradle build with:
  - root project: Fabric mod
  - `wrapper/`: standalone Java CLI for agent-driven control

## Build And Run

- Full build: `./gradlew build`
- Requires git submodules: `git submodule update --init --recursive` (build fails on `action-plan-advisor` if absent).
- `ffmpeg` must be on PATH or `PlaytestVideoRecorder`-related tests fail with `IOException` (playtest screen recording spawns it). Install: `brew install ffmpeg`.
- Normal Minecraft launches (`./gradlew runClient`, `scripts/codex-driver`, `scripts/arthas kickstart`) enable all supported mod integrations by default.
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
  - Verify live: mod list has `airicraft` + `airicraft-journeymap-compat` + `journeymap` + `airicraft-rei-compat` + `roughlyenoughitems`; `airicraft map status` says `available: true`, `preferredProvider: journeymap`; planner still exposes `search_recipes`.
- Evaluation batches:
  - Each `scenarios/<id>/scenario.yml` declares `requiredMods: [journeymap, roughlyenoughitems]` as needed. Omitted or empty means no optional integrations; dependencies of a declared mod are included automatically.
  - Manual evaluator launches: set `AIRICRAFT_EVALUATOR_SCENARIO_MANIFEST` to the chosen manifest path. The evaluation harness sets it per worker; discovery loads no integrations.
  - Use `scripts/run-evaluation-scenarios --scenario <id>` for a serial run.
  - Add repeated `--scenario` options and `--jobs <count>` for isolated parallel clients.
  - Parallel clients use separate game directories and bridge files.
  - Passed worker directories are deleted. Failed, review, and interrupted directories remain under `run/evaluator-workers/`.
  - Batch clients disable JDWP. Manual evaluator launches keep JDWP on `127.0.0.1:5008`.
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
- `airicraft servers list`
- `airicraft servers join --server-id <id>`
- `airicraft player focus`
- `airicraft player look-at --x <x> --y <y> --z <z>`
- `airicraft agent debug chat --message <text>`
- `airicraft agent debug idle-trigger`
- `airicraft agent debug state`
- `airicraft agent debug timeline [--since <entry-id>]`
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
- `GET /v1/servers`
- `POST /v1/servers/join`
- `GET /v1/focus`
- `GET /v1/world-snapshot`
- `GET|POST|DELETE /v1/highlights`

## Behavior Notes

- Location memory uses one planner interface: `remember_place`, `recall_place`, `list_places`, `forget_place`. JourneyMap is authoritative when installed; `places.json` is used only without it. No import, mirroring, or silent fallback while JourneyMap loads.
- Recall/forget accept exact name or stable ID; duplicate names require IDs. JourneyMap native and death waypoints are ordinary entries. Notes and preserved areas live in waypoint custom data.
- New location-memory consumers use `LocationMemoryService`/`LocationMemoryBridge`; only the fallback provider accesses the local file store. Protection follows the selected backend and fails closed when its data is unavailable.
- Each client automatically owns a read-only LAN debug dashboard. It scans upward from configured port `8765`, uses a viewer token distinct from the control bridge token, and prints the clickable URL in logs, `airicraft status`, and in-game chat.
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
