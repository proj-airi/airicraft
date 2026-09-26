# Rolling live-playtest recording

For normal user bug reports, use [Save bug report](diagnostic-reports.md). The developer exports below preserve the raw recording format.

The live client keeps a rolling observation history for diagnosing playtest failures. Its clock is **completed integrated-server ticks**: 12,000 ticks is ten minutes at 20 TPS. Tick-debug pause freezes capture and retention; a step advances the window by one server tick. Rendering and reading the history remain available while paused.

Tick-debug also gates the client tick call, including agent decisions, Baritone and hand actions. A pause/step waits for its completed server boundary, permits one client tick, then captures and freezes both sides. Rendering and scheduled bridge commands continue. This was verified against an active Husk reflex; server-only pause previously let attack attempts and decision clocks advance outside the frozen recording.

This extends the Runtime Observatory's observation store. It does not require the evaluator, a recording profile, or a second Minecraft client. It is an observation replay, not a Recorder Play or a deterministic simulation checkpoint. Evaluation recordings retain their existing artifact contract.

## Evidence

- Runtime snapshots every 20 server ticks and at each paused boundary: player/inventory, active goal/job, task and mission execution, action graph summaries, planner and event routing state. Short-lived task/reflex decisions still have their own capture at client decision boundaries; LLM history is polled every five server ticks.
- Snapshot payload schema 4 separates repeated context: `recipe_catalog` holds known crafting/smelting knowledge, `dialogue_history` holds conversation history, `conversation_sources` holds the canonical/projected conversation view, and `action_graph_execution` holds each execution's detailed payload (including its existing bounded trace). Snapshots reference these with `recipeCatalogSequence` or `observationSequence`. Unchanged context extends its validity without another copy; changed context gets a new sequence. Root task/reflex/mission fields replace duplicate copies inside `agent`. The planner base request also references a recipe catalog, while keeping its original inventory and decision facts. Its catalog context is separate from current mission evidence so differing historical/current knowledge cannot repeatedly replace each other.
- Changed task/reflex/event-pipeline decision state at client decision boundaries. Reflex evidence includes threats, route assessments, escape targets, rejected targets, completed escape legs, close contacts, security counters and actuator failures.
- Incremental semantic events and debug timeline entries, including external tool arguments, returned text and failures linked by call ID. Embedded LLM request/response records are retained separately. The external Codex conversation is outside this client recorder.
- Sparse 640×360 JPEG frames sampled every 40 server ticks by default and read directly from the active client's world framebuffer, before hand/HUD rendering. No RGB sidecar or replay renderer runs alongside the game.

Every observation retains its producer's agent `tick` and wall timestamp, plus `serverTickId`, the latest completed server tick when collected. These are different clocks: a drained event's server stamp describes collection, not a claim that its original decision ran on the server thread. A frame is stamped at framebuffer readback request, before asynchronous encoding.

Sampling defaults to once per 20 server ticks. Identical sampled pixels are skipped **before JPEG encoding**; `throughServerTickId` extends the existing frame's observed validity without adding an image record. A paused boundary can produce an extra frame. A single readback/encoding job may finish after pause; subsequent rendering does not keep recording. Busy encoding drops sample opportunities instead of building a queue. Encoding runs on one background worker.

## Inspect a failure with the CLI

For survival progression tests, `airicraft world difficulty` reads the local world's difficulty, lock state and time of day. `airicraft world difficulty --set easy` changes it on the integrated server thread through the normal authenticated control bridge. Supported values are `peaceful`, `easy`, `normal`, and `hard`; locked and hardcore worlds cannot be overridden. Continue tick debugging before using this command. This is a manual driver control, not a planner tool.

Launch `scripts/codex-driver` and use the same `AIRICRAFT_BRIDGE_STATE_FILE` for every command. The wrapper distribution is `wrapper/build/install/airicraft/bin/airicraft`.

```sh
airicraft agent debug ticks pause --output-image /tmp/failure-now.png
airicraft agent debug recording status
airicraft agent debug recording query --from-server-tick 2400 --to-server-tick 2800 \
  --type decision_state,debug_timeline,semantic_event --limit 100
```

`query` returns bounded pages, frame metadata without image bytes, `nextCursor`, `hasMore`, and loss counters. Continue with `--since <nextCursor>` and the same tick range/types. `runtime_snapshot` gives compact current state; omit `--type` or query `recipe_catalog,dialogue_history,conversation_sources,action_graph_execution` to retrieve referenced detail. Match the exact sequence, since a later context version is not evidence for an earlier decision. Exports include context whose validity overlaps the requested interval, even when first recorded before that interval. Live and file playback seek also load the selected snapshot's retained references. To retrieve one selected image:

```sh
airicraft agent debug recording query --type visual_frame --from-server-tick 2400 --to-server-tick 2800
airicraft agent debug recording frame --sequence <frame-sequence> --output /tmp/failure-frame.jpg
```

Save evidence before rebuilding/restarting the client:

```sh
airicraft agent debug recording export --from-server-tick 2400 --to-server-tick 2800 \
  --frames --output /tmp/failure-window.jsonl
```

Omit the range to export the retained window. Omit `--frames` for structured evidence only. Export fixes the upper sequence and tick range, writes in pages, and refuses to overwrite an existing file. The final `export_complete` record distinguishes a finished export from an interrupted file. A world/runtime session change interrupts an export.

Use the `debugSessionId` and `pauseEpoch` returned by the existing tick debugger to step or continue. Recording requires no separate pause command. Read `status` after the final in-flight frame settles when comparing exact retained counts.

## Playback and retention

The dashboard's cursor seeks by server tick across the window, loading the nearest retained state/frame and recent events on demand. **Play history** advances the viewer only; it does not resume Minecraft. **Save session** downloads the retained JSONL with frames. **Open session** builds a byte-range index and reads selected records from the file instead of keeping all decoded frames and payloads in browser memory. CLI exports use the same observation format.

The time window is also bounded by memory: 64 MiB by default, with images limited to half that budget. Budget/type limits can shorten retained evidence before ten minutes; `droppedByType` reports this loss. `expiredByType` counts normal age eviction separately. A frame that is still pixel-identical can span the window boundary; its old capture timestamp remains visible instead of pretending it was captured again. The browser maintains a smaller working set and can seek back to the server's retained evidence.

World changes and runtime reloads start fresh recording sessions. Leaving a world freezes the last retained interval so it can still be exported before another world is joined. Normal clients do not continuously accumulate recording files on disk; only explicitly exported incidents persist. The opt-in [automatic playtest mode](automatic-playtest.md) streams flight records and live observations to disk from world load, then finalizes a required Recorder Play when the planner reports a suspected bug. Minecraft's own logs and evaluator output keep their existing retention behavior.

```yaml
debugDashboard:
  historyMegabytes: 64
  visualCaptureEnabled: true
  visualCaptureIntervalTicks: 40  # default 0.5 fps; 20 for 1 fps, 100 for 0.2 fps
```

Existing explicit `visualCaptureEnabled: false` settings remain effective. The server-clock window and RGB capture described here require a local integrated server. Remote-server dashboard observations remain available, with `serverClockAvailable: false` and unavailable server stamps; they are not presented as server-aligned replay.

## Verification

Focused tests cover tick-based expiry, pause/step retention, session isolation, image budget protection, unchanged pixels, query filtering/cursors, selective image retrieval, and export pagination/session changes. `./gradlew build` passed with 1,032 root tests passed, two skipped, and 92 wrapper tests passed.

Live validation on 2026-09-10 used a copied peaceful survival save through the production wrapper path:

- Two paused reads held server tick 1139, sequence 294 and 5,294,564 retained bytes. One debug step advanced to server tick 1140; continuing resumed recording.
- External inventory inspection and malformed navigation calls retained arguments and completed/rejected results under matching call IDs. Corrected nearby navigation reached a recorded `COMPLETED` task state.
- A stationary ground view skipped 75 identical sampled frames with zero reported capture failures.
- At server tick 12977 the exported window began at tick 977. All 2,689 retained records were within that window: 2,357 runtime snapshots, 312 frames, nine debug entries, seven decision states and four semantic events. Normal age eviction removed older records; there were no early budget drops. Estimated retained memory was 42,441,180 bytes; the JSONL export was 21,591,574 bytes.
- Browser checks loaded live history and a saved incident, sought to server tick 6200 within an exported 5200–7200 interval, rendered the 640-pixel frame and advanced history playback without JavaScript errors. The saved file was indexed for on-demand reading.

This verifies recording behavior, not the earlier survival gameplay hypotheses. A restart on the final runtime build separately verified pause at server tick 1171, an export with 228 expanded runtime snapshots, and zero frame-capture failures. A browser reload check confirmed that the session changed and the previous observation inspector cleared without JavaScript errors. The post-disconnect export behavior also has a lifecycle regression test.

## Streaming planner observations

Planner calls now expose a bounded live preview in `llm_call` records with status `STREAMING`. Final records preserve the assembled response and usage. Large request envelopes are stored once as `llm_request`; resolve `request.observationSequence` to its `requestBody` when reading JSONL. The dashboard supports these references and older inline envelopes. Export all observation types when preserving a call, so its shared request is included. Streaming capture follows the same five-server-tick poll and pause boundary as other LLM history. See [planner streaming](planner-streaming.md).
