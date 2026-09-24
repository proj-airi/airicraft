# Airicraft

Airicraft is a Fabric mod that exposes an in-game agent bridge and a CLI for automating common tasks. It targets Minecraft `1.21.8` with Java `21`, plus a `wrapper/` CLI subproject.

## Development And Verification

Prerequisites:

- JDK 25 on `PATH` for running Gradle; Fabric Loom `1.18` requires a Java 25+ build JVM. The repo pins `25` in `.java-version`, so jenv, asdf, or jolta can supply the JDK automatically (for jenv: `jenv add <jdk-home>` once, then the pin applies). A plain `.jdk` bundle under `~/Library/Java/JavaVirtualMachines` works too.
- A JetBrains Runtime JDK 21 toolchain for compiling, testing, and running the mod; the language target stays on Java 21 and the toolchain vendor is pinned to JBR (`JvmVendorSpec.JETBRAINS`), which is what enables enhanced class redefinition (`-XX:+AllowEnhancedClassRedefinition`) for HotSwap. Gradle auto-detects installed JDKs and can auto-download a JBR 21 toolchain via the Foojay resolver, so a `.jdk` bundle under `~/Library/Java/JavaVirtualMachines` or a version-manager install is picked up without configuration.
- Git submodules initialized; the build fails on a missing `action-plan-advisor`:

  ```shell
  git submodule update --init --recursive
  ```

- `ffmpeg` on `PATH`; playtest screen recording and its tests spawn it. Install: `brew install ffmpeg`.

`.envrc` is optional, gitignored, and only sourced when present (`scripts/*` handle this themselves). Use it for machine-local environment variables; it is not required for builds.

```shell
./gradlew --version
./gradlew build
./gradlew test wrapper:test --rerun-tasks
```

### Planner world inspection contract

The planner has a read-only `inspect_world` tool for exact local block state. It complements vision: use `take_a_look` for visual semantics, and `inspect_world` when the planner needs precise coordinates, block ids, block-state properties, or placement affordances.

`inspect_world` accepts fixed modes:

- `inspect_area`: compact exact local block records.
- `find_blocks`: exact `blockIds` plus optional `stateFilters` such as `age=7` or `moisture=7`.
- `find_placement_sites`: conservative candidate target positions using constraints such as `targetMaterial`, `supportBlockIds`, `supportStateFilters`, `requireAirAbove`, `requireStandableAdjacent`, `requireWithinInteractionRange`, and nearby required blocks.

Scopes are bounded to prevent broad world scans:

- `scope=self`: player origin with `horizontalRadius` and `verticalRadius`.
- `scope=center`: explicit `x/y/z` center with radii.
- `scope=box`: explicit `x1/y1/z1` and `x2/y2/z2` corners.

Every queried block must be within 64 blocks of the player. Radius defaults are 8 horizontal and 4 vertical, capped at 16 and 8. Search results default to 32 and cap at 64.

Planner-facing block modification is guarded by the world-read ledger. `place_block` and `use_block` take an intended modified target `x/y/z`; for example, planting seeds targets the crop position above farmland, while the runtime derives the support click. The target position must have been returned by `inspect_world` within the last 10 planner tool calls. If not, the runtime does not queue the modification. It returns a `place_block`/`use_block` tool result containing a small `inspect_world` `inspect_area` query centered on the target and tells the planner to call the same tool again only if it still wants to proceed.

### Location memory

The planner uses one interface: `remember_place`, `recall_place`, `list_places`, and `forget_place`. With JourneyMap installed, these operate directly on persistent native waypoints in the current world/server, including user-created and death waypoints. Names, coordinates, and native UI edits share the same store; notes and `preserveArea` are stored in namespaced waypoint custom data. `take_map_look` remains the map-image tool. The separate planner map-waypoint CRUD tools have been removed; the wrapper's map commands remain available.

Use an exact, case-sensitive name or a returned stable `id` for recall/forget. If names are duplicated, the tool returns candidate IDs and requires an ID. `remember_place` requires a name and accepts an existing ID to update or rename that entry. Replacing a location without `note` or `preserveArea` clears that metadata. `read_logbook` resolves its `place` filter through the same backend and accepts `placeId` for ambiguous names. Native waypoints without Airicraft metadata have no note or protected area; arbitrary non-JSON custom data is left intact, and metadata writes to such a waypoint report a conflict.

JourneyMap starts fresh: existing `airicraft/places.json` files are neither imported nor updated while it is installed. A loading/unavailable JourneyMap returns a backend error instead of switching stores. Without JourneyMap, the local world-save backend uses `places.json`; old files remain readable and gain stable IDs when edited. Removing JourneyMap exposes that independent local store, not a copy of JourneyMap's locations. The local backend still requires a locally hosted world.

Protection follows the selected backend. Legacy local protected areas are inactive with JourneyMap selected. Native waypoint changes refresh protection on the next client tick; periodic refresh also catches file/backend changes. Invalid or unavailable protection data blocks automatic terrain edits until a valid snapshot is available. Location names and notes remain data, not instructions or evidence of safety/reachability.

### Evaluation scenario checks

Scenario evaluations load only the optional integrations declared by the scenario manifest:

```yaml
requiredMods: [journeymap, roughlyenoughitems]
```

Use either supported Fabric mod ID, both, or `[]`. Omitting `requiredMods` means no optional integrations. Dependencies (such as REI's Architectury and Cloth Config) are included automatically. Unknown IDs or malformed declarations fail before launch. The evaluation harness passes each worker its own manifest; the discovery client has no optional integrations. Manual launches use `AIRICRAFT_EVALUATOR_SCENARIO_MANIFEST=/absolute/path/scenario.yml scripts/eval run` (also supported by `scripts/codex-driver-evaluator`), or pass `-Pairicraft.evaluator.scenarioManifest=/absolute/path/scenario.yml` to Gradle. Restart with the appropriate manifest before switching to a scenario requiring a different mod set. The recording profile is configured separately from `requiredMods`.

Evaluation scenarios in `scenarios/*/scenario.yml` can use deterministic checks. `inventory_contains` verifies an item count, `block_state` verifies one exact block position, and `block_count` verifies at least `count` matching blocks in either `scope: self` with `horizontalRadius`/`verticalRadius` or `scope: box` with `x1/y1/z1/x2/y2/z2`.

Planner-driven evaluations seed the scenario prompt as one world-persisted native planner goal. The planner owns continuation, work waiting, and controller/thinker handoffs; the evaluator never sends heartbeat prompts. Legacy `heartbeatIntervalTicks` is ignored, including in frozen manifests. `maxPlannerTurns` counts gameplay model requests across both roles (including tool follow-ups, excluding transport retries and discarded requests), measured from scenario admission. Time budgets still apply while waiting or blocked. A terminal goal claim cannot substitute for deterministic physical checks; subjective outcomes need review. Budget exhaustion stops the run without declaring the gameplay objective completed. External-driver runs remain manually driven.

Run scenarios through the batch harness. Parallel execution is opt-in with `--jobs`; the default remains one client:

```shell
scripts/run-evaluation-scenarios \
  --scenario pickup \
  --scenario underground \
  --recorder-jar /path/to/recorder-profile.jar \
  --jobs 2
```

Recorder-enabled runs require a recording profile. Set `AIRICRAFT_RECORDER_JAR`
to use the same profile without passing `--recorder-jar` each time. Use
`--no-recorder` to keep the existing run without integrated-server capture.

Each scenario gets an isolated bridge file, game directory, process, and artifact directory. Parallel runs stop every client. Recorder-disabled serial runs can leave the final client active unless `--stop-client-after-scenario` is set.

Recorder-enabled scenarios always stop their client after a terminal scenario
outcome. The runner writes the recorder and ServerReplay configuration into the
isolated game directory. The recorder artifact root is under that scenario's
result directory. Recorder-disabled scenarios keep the existing final-client
behavior.

The harness copies the base `run/config`, JourneyMap configuration, and `run/options.txt` into each worker. It does not copy saves, logs, screenshots, or JourneyMap world data. Passed worker directories are deleted. Failed, interrupted, and review worker directories remain under `run/evaluator-workers/<run-id>/`.

The scenario manifest and reports remain under `eval-output/<run-id>/`. Use each result's `workerId`, `clientPid`, `gameDir`, and `bridgeStatePath` to correlate live evidence.

To make frozen scenario worlds visible in the Minecraft singleplayer menu, unpack the archived fixtures into the dev game directory:

```shell
scenarios/unpack-worlds --dry-run
scenarios/unpack-worlds
```

By default this installs every frozen `scenarios/*/world.zip` archive into `run/saves/<scenario-id>` and marks the installed save read-only. The evaluator uses that visible frozen world as a menu entry, then loads a disposable copy for the actual run. To refresh an existing installed fixture, pass `--force`; to install only one scenario, pass its id:

```shell
scenarios/unpack-worlds farm_easy --force
```

### Normal dev client

Normal development runs include all supported integrations, use the production-style Fabric client with HotSwap, and open JDWP on `127.0.0.1:5005`.

```shell
./gradlew runClient
jdb -attach 127.0.0.1:5005
```

### Automatic playtests

Run `scripts/automatic-playtest --world <saved-world-directory> --recorder-jar <profile> --objective <instruction>`. The planner can call `something_wrong` with a natural-language bug report to pause the game and archive the shared evaluation flight records, live RGB, required Recorder Play, and paused world checkpoint under `automatic_playtest/`. See [automatic playtest reports](docs/automatic-playtest.md) for the shutdown/finalization flow and offline review.

### Realtime debug dashboard

Every Airicraft client starts its own read-only debug dashboard. The client binds the first available LAN port starting at `8765` and prints a clickable viewer-token URL in the log, in `airicraft status`, and once in Minecraft chat after a world loads.

The dashboard provides:

- full LLM request/response envelopes, parsed responses, failures, timing, and token usage
- embodied runtime, planner, conversation, task, mission, survival-reflex, behavior-tree, and action-graph snapshots
- semantic events, the correlated debug timeline, and Minecraft/Airicraft log history
- a global time cursor for inspecting all panels at an earlier observation
- bounded history with explicit eviction/gap reporting
- JSONL session export and replay through **Open session**

The dashboard is observation-only. It has a separate viewer token and does not expose the localhost bridge token or any bridge mutation route. Runtime state is sampled every five client ticks while discrete transitions are captured as they arrive. The browser keeps a recent memory-bounded window; **Save session** exports the full retained server history. A slow or disconnected browser never backpressures the game.

Configure it in `config/airicraft/airicraft.yml`:

```yaml
debugDashboard:
  enabled: true
  basePort: 8765
  portScanLimit: 100
  historyMegabytes: 64
  visualCaptureEnabled: false
  visualCaptureIntervalTicks: 20
```

Visual context is intentionally off by default because screenshot capture and PNG encoding cost more than structured observation. When enabled, it captures at most one correlated frame per configured interval. This development dashboard preserves raw LLM content and does not redact it.

### Main-mod HotSwap

All existing client tasks use HotSwap by default. This includes `runClient`, `scripts/compat run`, and `scripts/eval run`.

HotswapAgent `2.0.3` is a regular Maven dependency; Gradle resolves it on first use. Enhanced class redefinition (`-XX:+AllowEnhancedClassRedefinition`) is a JetBrains Runtime feature: the client always runs on the JBR 21 toolchain (the Gradle JVM itself is JDK 25). Non-JBR JDK 21 installs are not selected by the toolchain vendor spec, so there is no silent fallback to method-body-only reloads.

Fabric uses its Knot class loader. HotswapAgent cannot watch Knot class roots directly.

The continuous Gradle task watches class output and sends changed classes through JDWP. HotswapAgent handles each class redefinition inside the client.

Use two terminals:

1. Start the required existing client task in the first terminal.
2. Start the continuous main-mod build in the second terminal:

   ```shell
   ./gradlew hotswapMain --continuous
   ```

3. Edit Java code under the main Airicraft mod.
4. Wait for a `HotSwap reloaded` message in the second terminal.

The client log also prints a `[redefine,class]` message. The first continuous run records the current class baseline.

The task watches named output for the normal client. It watches remapped root-mod output for each production client.

HotSwap has these limits:

- It is set up and verified on macOS ARM64; other platforms are untested.
- The first setup needs network access (Gradle toolchain and Maven downloads). An offline first setup fails without a partial installation.
- Only main Airicraft mod classes reload.
- Evaluator-addon and compatibility-addon changes need a client restart.
- Mixin changes, resource changes, and Fabric initialization changes need a client restart.
- New classes, deleted classes, and superclass changes need a client restart.
- HotSwap does not run constructors or initialization code again for existing objects.
- A debugger and the Gradle uploader cannot attach to the same JDWP port at the same time.
- If no client is available, Gradle keeps the changes for the next `hotswapMain` run.
- A final evaluator proof needs a clean client start.
- A code change during an evaluator run invalidates that run as final evidence.

Related upstream references:

- [JetBrains Runtime](https://github.com/JetBrains/JetBrainsRuntime)
- [HotswapAgent](https://github.com/HotswapProjects/HotswapAgent)
- [Fabric HotSwap guide](https://docs.fabricmc.net/develop/getting-started/intellij-idea/launching-the-game)

### Agent debug CLI

The agent has turns, not ticks. These commands use the client tick as the only debug clock. Minecraft targets 20 client ticks each second.

A frame capture uses the first rendered frame for its client tick. The next client tick waits until that frame capture finishes.

Build the CLI before its first use or after a wrapper change:

```shell
./gradlew wrapper:installDist
AIRICRAFT_CLI=wrapper/build/install/airicraft/bin/airicraft
"$AIRICRAFT_CLI" agent debug --help
```

The Minecraft client and bridge must run for all commands. The `ticks`, `world`, and `trace` groups also need a loaded world.

The top-level commands have these uses:

| Command | Use |
| --- | --- |
| `chat --message <text>` | Inject one local-controller chat message. |
| `idle-trigger` | Start one idle-think planner trigger. |
| `state` | Show correlated planner, dialogue, chat, and task state. |
| `timeline [--since <entry-id>]` | Show recent debug events after an optional entry ID. |
| `ticks` | Pause, step, inspect, or continue client ticks. |
| `world` | Query the world for the current paused snapshot. |
| `trace` | Record selected debug information on each client tick. |

Add `--verbose` to a command when you need its full structured output. Use `--help` on any command group for its exact options.

#### Agent state and test triggers

Use these commands without pausing client ticks:

```shell
"$AIRICRAFT_CLI" agent debug chat --message '@agent get me 4 wood logs'
"$AIRICRAFT_CLI" agent debug idle-trigger
"$AIRICRAFT_CLI" agent debug state --verbose
"$AIRICRAFT_CLI" agent debug timeline --since 125 --verbose
```

The timeline cursor is exclusive. The command returns entries with an `entryId` greater than the `--since` value.

#### Pause and step client ticks

Pause the client and optionally write its captured frame to a PNG file. Add
`--player-actions` to capture actions and block break progress during this
pause session:

```shell
"$AIRICRAFT_CLI" agent debug ticks pause \
  --player-actions \
  --output-image /tmp/airicraft-pause.png
```

The response includes `debugSessionId`, `pauseEpoch`, `snapshotId`, and `clientTickId`. The `snapshotId` gives read-only access to the paused world.

`playerActions` contains action states for attack, use, pick, drop, hand swap,
and hotbar keys. Its optional `breakProgress` gives the block position, a
progress value from 0 through 1, a break stage from 0 through 9, and a
`started` flag. Each later `ticks step` keeps this capture option.

Use the returned session ID and pause epoch to run exactly one client tick:

```shell
"$AIRICRAFT_CLI" agent debug ticks step \
  --debug-session-id <debug-session-id> \
  --pause-epoch <pause-epoch> \
  --output-image /tmp/airicraft-step.png
```

Each step returns a new `pauseEpoch` and `snapshotId`. Use these new values for the next command.

Continue normal client ticks with the latest session ID and pause epoch:

```shell
"$AIRICRAFT_CLI" agent debug ticks continue \
  --debug-session-id <debug-session-id> \
  --pause-epoch <pause-epoch>
```

Use `ticks state` to inspect the current phase and identifiers:

```shell
"$AIRICRAFT_CLI" agent debug ticks state --verbose
```

A snapshot stays valid only while its client tick is paused. A step or continue command makes the old `snapshotId` stale.

#### Query a paused world snapshot

All world queries require the latest `snapshotId` from `ticks pause` or `ticks step`.

Get snapshot metadata or the full captured player state:

```shell
"$AIRICRAFT_CLI" agent debug world metadata \
  --snapshot-id <snapshot-id> --verbose

"$AIRICRAFT_CLI" agent debug world player-state \
  --snapshot-id <snapshot-id> --verbose
```

The player state includes identity, game mode, position, rotation, velocity, bounds, movement, and vitals. It also includes hunger, experience, abilities, input, inventory, equipment, effects, and attributes.

Query entities in a radius around the captured player:

```shell
"$AIRICRAFT_CLI" agent debug world entities \
  --snapshot-id <snapshot-id> \
  --radius 16 \
  --type minecraft:zombie,minecraft:skeleton \
  --alive \
  --limit 64 \
  --verbose
```

Omit the radius center to follow the captured player position. Set all three center coordinates to query around another position.

The radius can be from 0 through 4096 blocks. Use either a region or a radius in one query.

Query an arbitrary inclusive block region:

```shell
"$AIRICRAFT_CLI" agent debug world entities \
  --snapshot-id <snapshot-id> \
  --min-x -32 --min-y 50 --min-z -32 \
  --max-x 32 --max-y 100 --max-z 32 \
  --living-only \
  --limit 64 \
  --verbose
```

You can filter entities by `--entity-id`, `--uuid`, `--name`, `--type`, or `--alive`. You can also use `--living-only`, `--player-only`, and `--include-self`.

Name matching is exact and ignores letter case. Type matching uses an exact namespaced entity type ID.

The query excludes the local player unless you use `--include-self`. Results sort by distance and then by runtime entity ID.

Omit both spatial selectors to query all loaded entities. Use `--cursor` and `--limit` to page through the filtered result.

Entity pages contain 32 records by default and allow up to 256. Living entity records include vitals, equipment, effects, and attributes.

Read one block or query any region:

```shell
"$AIRICRAFT_CLI" agent debug world get-block \
  --snapshot-id <snapshot-id> --x 10 --y 64 --z -4 --verbose

"$AIRICRAFT_CLI" agent debug world scan-box \
  --snapshot-id <snapshot-id> \
  --min-x 0 --min-y 60 --min-z 0 \
  --max-x 31 --max-y 80 --max-z 31 \
  --limit 4096 --verbose

"$AIRICRAFT_CLI" agent debug world find-blocks \
  --snapshot-id <snapshot-id> \
  --min-x 0 --min-y 60 --min-z 0 \
  --max-x 31 --max-y 80 --max-z 31 \
  --block-id minecraft:diamond_ore,minecraft:deepslate_diamond_ore \
  --limit 4096 --verbose

"$AIRICRAFT_CLI" agent debug world region-stats \
  --snapshot-id <snapshot-id> \
  --min-x 0 --min-y 60 --min-z 0 \
  --max-x 31 --max-y 80 --max-z 31 \
  --limit 4096 --verbose
```

Region coordinates are inclusive. Region commands inspect 256 cells by default and allow up to 4096 cells per page.

Use the returned `nextCursor` as `--cursor` to read the next page. Unloaded blocks appear as unloaded records or unloaded counts.

#### Stream a client tick trace

A trace writes JSON Lines while normal client ticks run. The client keeps a rolling record buffer. The CLI polls it at 20 Hz and uses a buffered file writer.

Start a bounded trace for player state, nearby hostile entities, and client frames:

```shell
"$AIRICRAFT_CLI" agent debug trace start \
  --info metadata,player-state,entities,frame \
  --window-ticks 100 \
  --once \
  --output /tmp/airicraft-trace.jsonl \
  --entity-query '{"radius":16,"entityTypeIds":["minecraft:zombie","minecraft:skeleton"],"alive":true,"limit":64}'
```

Start a no-frame mining trace:

```shell
"$AIRICRAFT_CLI" agent debug trace start \
  --info metadata,player-actions \
  --window-ticks 120 \
  --once \
  --output /tmp/airicraft-mining-trace.jsonl
```

The file starts with a `trace_start` record. Each captured tick writes a `trace_record` record. The file ends with a `trace_end` record.

A frame record keeps `record.frame.imageBase64`. This keeps its screen render in the same output file. The stream holds a pending frame until its capture completes.

With `--once`, the client stops the trace after it records `--window-ticks` ticks. The command exits after it writes the final record and `trace_end`.

Without `--once`, the command continues to stream. Its initial response gives the `traceId`. Use another terminal to stop that trace:

```shell
"$AIRICRAFT_CLI" agent debug trace stop --trace-id <trace-id>
```

If the client buffer evicts unread ticks, the file writes a `trace_gap` record. This record gives the missing range boundary.

The supported `--info` values are `metadata`, `player-state`, `entities`,
`blocks`, `frame`, and `player-actions`. Repeat `--info` or use a
comma-separated list.

`player-actions` adds `record.playerActions`. It contains action states and
optional `breakProgress`. A direct attack or use call sets `started: true`. A
break progress record includes `position`, `progress`, `stage`, and `started`.

The client captures only selected trace information. A trace without `frame`
does not request a screen render.

An entity trace accepts an optional JSON query. Its fields match the paused entity query:

- Spatial fields: `minX`, `minY`, `minZ`, `maxX`, `maxY`, and `maxZ`.
- Radius fields: `centerX`, `centerY`, `centerZ`, and `radius`.
- Identity fields: `entityId`, `uuid`, `name`, and `entityTypeIds`.
- State fields: `alive`, `livingOnly`, `playerOnly`, and `includeSelf`.
- Result field: `limit` from 1 through 256.

Use either a region or a radius. A radius without a center follows the captured player on each client tick.

Without `--entity-query`, an entity trace keeps the nearest 32 loaded entities and excludes the local player.

Add a block region when the trace includes `blocks`:

```shell
"$AIRICRAFT_CLI" agent debug trace start \
  --info player-state,blocks \
  --window-ticks 40 \
  --once \
  --output /tmp/airicraft-block-trace.jsonl \
  --block-query '{"minX":0,"minY":63,"minZ":0,"maxX":7,"maxY":65,"maxZ":7}'
```

The general window limit is 1200 client ticks. A trace with frames has a limit of 200 client ticks.

A trace block region can contain at most 4096 blocks. The window size multiplied by its block count cannot exceed 250000.

The window size multiplied by the entity limit cannot exceed 100000. A trace cannot start while ticks are paused, and ticks cannot pause during a trace.

### Compatibility client

Normal `./gradlew runClient` launches include all supported integrations through the production-style Fabric client with the remapped Airicraft jar. `scripts/codex-driver` and `scripts/arthas kickstart` inherit this default. For an explicit bare development-client test, use `./gradlew runClient -Pairicraft.includeCompat=false`.

The client includes JourneyMap, REI, Fabric API, Architectury, Cloth Config, and local runtime mods. Normal `runClient` uses JDWP on `127.0.0.1:5005`; `scripts/compat run` uses `127.0.0.1:5007`.

The helper keeps downloaded/runtime jars out of the repository in ignored `.airicraft-compat/`, and uses the shared dev game directory `run/`. That means normal `runClient` and compatibility runs read the same Airicraft config:

```text
run/config/airicraft
```

Common flow:

```shell
scripts/compat config
scripts/compat setup
scripts/compat mods
scripts/compat run
jdb -attach 127.0.0.1:5007
wrapper/build/install/airicraft/bin/airicraft status
wrapper/build/install/airicraft/bin/airicraft map status
wrapper/build/install/airicraft/bin/airicraft map image --kind worldmap --output /tmp/airicraft-worldmap.png
wrapper/build/install/airicraft/bin/airicraft map image --kind worldmap --origin-x 128 --origin-z -64 --output /tmp/airicraft-worldmap-origin.png
```

Expected smoke signal:

- Minecraft starts without a remap crash.
- Mod list includes `airicraft`, `airicraft-journeymap-compat`, `journeymap`, `airicraft-rei-compat`, and `roughlyenoughitems`.
- Wrapper status reports `available: true` and `bridgeAvailable: true`.
- `airicraft map status` reports `available: true` and `preferredProvider: journeymap`.
- `search_recipes` remains available to the planner while map tools are also available.
- `/tmp/airicraft-worldmap.png` exists and is non-empty after map image capture.

Do not copy optional-mod jars into `run/mods` or vendor them into this repository. Let `scripts/compat` sync the external jars into `.airicraft-compat/integration/`.

### Live JVM debugging with Arthas

Airicraft includes dev-only Gradle helpers for attaching the Arthas CLI to the running Minecraft dev client. Arthas is external tooling: it does not add a mod dependency and does not replace the Airicraft bridge or JDWP.

Start the client:

```shell
./gradlew runClient
```

For a cold dev client, use the helper to start the client, wait for the bridge, join the first saved world, open LAN, and attach Arthas. This command is cold-only and fails fast if a client is already running:

```shell
scripts/arthas kickstart
```

Attach Arthas manually when the client is already running:

```shell
./gradlew arthasAttach
```

Use the low-noise HTTP helper for probes after Arthas is attached. It auto-selects the Minecraft Arthas HTTP port when possible and prints compact results:

```shell
scripts/arthas --help
scripts/arthas v
scripts/arthas sc 'ai.moeru.airicraft.*'
scripts/arthas sm ai.moeru.airicraft.ModBridgeServer createStatusResponse
scripts/arthas w ai.moeru.airicraft.ModBridgeServer createStatusResponse
scripts/arthas raw 'thread -n 1'
```

If another JVM already owns the default Arthas port, pass the Minecraft port explicitly:

```shell
scripts/arthas --port 8564 sc ai.moeru.airicraft.ModBridgeServer
```

If process-name selection misses the dev client, find the JVM and attach by PID:

```shell
jps -lv
./gradlew arthasAttach -Pairicraft.arthas.pid=<pid>
```

Useful Airicraft inspection commands include `sc`, `sm`, `jad`, `watch`, `trace`, `stack`, `tt`, `thread`, `dashboard`, and `ognl`.

Arthas starts with full command power by default. Mutation commands such as `ognl`, `vmtool`, `sysprop`, `vmoption`, `redefine`, `retransform`, and `mc` can alter the live JVM; use them deliberately. To restrict commands for a session, pass a comma-separated list:

```shell
./gradlew arthasShell -Pairicraft.arthas.disabledCommands=stop,dump,heapdump,redefine,retransform,mc
```

## Weave / OpenTelemetry Observability Setup

Airicraft now supports optional LLM-call tracing via OpenTelemetry.
The default mode is vendor-neutral OTLP; if you want W&B Weave, you only need to switch a single profile in config.

### 1) Why this setup exists

- The `planner` and `vision` outbound requests emit spans with:
  - request metadata (provider/model/endpoint)
  - response metadata (status/usage tokens)
  - sanitized request/response summaries
  - failures and error typing
- `generic` profile exports only standard OTEL fields.
- `weave` profile adds a small set of Weave-friendly attributes.

### 2) Edit config

Airicraft writes/reads:

`config/airicraft/agent.yml` under the active Minecraft game directory.

For local development, both `runClient` and `scripts/compat run` use:

`run/config/airicraft/agent.yml`

The file is based on `src/client/resources/config/airicraft/agent.yml.example`.

A practical OTLP setup is already scaffolded in that template under `observability`.

### 3) Generic OTLP (default)

Set:

```yaml
observability:
  enabled: true
  exporter: "otlp_http"
  otlpEndpoint: "http://127.0.0.1:4318/v1/traces"
  otlpHeaders: {}
  vendorProfile: "generic"
  captureInputs: false
  captureOutputs: false
  captureImages: false
```

### 4) W&B Weave profile

Use this to send the same spans to Weave with minimal changes:

```yaml
observability:
  enabled: true
  exporter: "otlp_http"
  otlpEndpoint: "https://trace.wandb.ai/otel/v1/traces"
  otlpHeaders:
    wandb-api-key: "<W&B_API_KEY>"
  resourceAttributes:
    wandb.entity: "shinohara-rin"
    wandb.project: "airicraft"
  vendorProfile: "weave"
  captureInputs: false
  captureOutputs: false
  captureImages: false
```

For your project slug `shinohara-rin/airicraft`, set:

- `wandb.entity: "shinohara-rin"`
- `wandb.project: "airicraft"`

### 5) Optional data capture controls

- `captureInputs`: include sanitized input summaries.
- `captureOutputs`: include sanitized output summaries.
- `captureImages`: allow screenshot/tool-capture spans to export image payloads for media-capable backends like Weave. Regular LLM request traces still redact image bytes.

If all are false, only non-content structural tracing metadata is sent.

### 6) Reload workflow

1. Start Minecraft or keep existing session.
2. Edit `config/airicraft/airicraft.yml` and/or `config/airicraft/agent.yml`.
3. Run `airicraft reload` from the wrapper CLI, or `/airicraft reload` in-game.
4. The runtime reloads config live without restarting Minecraft; active agent state is reset during reload.
5. Confirm traces appear in your OTLP collector/Weave dashboard.

## Troubleshooting

### `./gradlew runClient` says `Unable to locate a Java Runtime`

No JDK 25 is visible to the shell that launched Gradle. Check:

```shell
java -version
which java
/usr/libexec/java_home -V   # lists JVMs under Library/Java/JavaVirtualMachines
```

Fix:

1. Install a JDK 25 (any distro). Either drop a `.jdk` bundle into `~/Library/Java/JavaVirtualMachines`, or use a version manager that reads the repo's `.java-version`.
2. Make sure `java` is on `PATH` (or `JAVA_HOME` is set) in the shell that runs Gradle.
3. Re-run `./gradlew --version` to confirm Gradle sees Java 25 or newer.

If Gradle runs but toolchain resolution fails instead, no JBR 21 JDK was detected. Install one under `~/Library/Java/JavaVirtualMachines` (or via your version manager), or let the Foojay resolver download it — toolchain downloads need network access on first setup.

## Notes

- If `observability.enabled` is false, no OTLP network calls are made.
- If `otlpEndpoint` is empty or unsupported, observability is disabled at runtime with a warning.
- This integration is wired for outbound planner and vision API calls only.
