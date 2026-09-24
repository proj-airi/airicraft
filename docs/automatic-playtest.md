# Automatic playtest recordings

Automatic playtests sample the screen once per server tick (target 20 Hz at 20 TPS) and encode FPV at 20 fps. Capture remains asynchronous with one readback/encoding job in flight; slow rendering or encoding can reduce distinct-frame cadence. Identical frames are held, and frame anchors preserve actual capture timing. This applies to new recordings; existing videos retain their original cadence.

On startup, automatic playtests maximize the game window without fullscreen and enable the Airicraft debug conversation overlay (`/airicraft debug conversation`). These defaults apply once per run; you can change the window or overlay afterward.

The launcher uses the compatibility-enabled production client, including JourneyMap and REI (Roughly Enough Items), their Airicraft adapters, and dependencies. It shares the optional-mod cache used by `scripts/compat` while keeping the playtest world and config isolated.

Start an isolated local client with continuous flight recording, live RGB capture, the required external recording profile, and the planner's `something_wrong` tool:

```sh
scripts/automatic-playtest \
  --world 'run/saves/My World' \
  --recorder-jar /absolute/path/to/recorder-profile.jar \
  --objective 'Obtain an iron pickaxe in survival. Report suspected interface bugs.'
```

Install `ffmpeg` and `ffprobe` on PATH before launching. The launcher copies the world, joins it, and sends the objective. Omit `--objective` to use in-game chat. `AIRICRAFT_RECORDER_JAR` can supply the profile instead of `--recorder-jar`. Airicraft consumes the same prebuilt profile as evaluation runs; it does not build or fetch recorder dependencies. Both planner roles can report a suspected Airicraft/tool/harness defect with `something_wrong({"description":"What I tried, expected, and observed; relevant work IDs..."})`. The description is free text, up to 8192 characters. Certainty or a root cause is not required. Ordinary survival difficulty or missing ingredients alone are not interface defects.

Recording begins when the world loads. On a report, Airicraft commits the tool receipt, ends that planner turn, pauses both client and integrated-server ticks, and captures final evidence. Without a Codex parent, the launcher asks the client to flush and copy the world while paused, then close normally so the external Recorder Play can finalize. With a Codex parent, it keeps Minecraft paused and queues a live-debug handoff instead; shutdown and publication wait until the helper is stopped. It checks the Play's metadata/events/replay archive with the evaluation validator, publishes the planner and playtest extensions into the Play, and moves its recorder-owned server directory unchanged into `automatic_playtest/v1/`. A small run index remains at `automatic_playtest/<run-id>/summary.json`; `artifactPlayPath` locates the Play relative to the output root. No posthoc RGB rendering runs. Duplicate reports return the original incident ID. An active tick-debug trace is stopped so it cannot block the pause.

Every run is recorded from world entry, even if no bug is reported. Normal world exit, client quit, planner degraded mode, a time limit, or interrupting the launcher (Ctrl-C or SIGTERM) finalizes the flight records and live RGB stream. During recording, the launcher polls agent status and stops on `degraded: true`, using the normal graceful shutdown with `terminationReason: planner_degraded`. This also applies to open-ended runs. Degradation does not fabricate a bug report or imply incomplete recording: `recordingComplete` still describes the retained evidence. The launcher waits for Recorder Play shutdown, copies the saved world, and publishes its evidence inside the Play. The run index remains under `automatic_playtest/<run-id>/`. No run is discarded because it lacked a bug report.

Fatal JVM errors reported in the client log (including a caught `OutOfMemoryError`) and permanent compaction request failures stop the run with `terminationReason: runtime_fatal_error`. The launcher checks logs even while bridge requests fail. A bridge that stays unavailable for 60 seconds stops an open-ended or timed run with `terminationReason: bridge_unresponsive`; a successful probe clears that timer. Both paths attempt the existing graceful save-and-stop, then use bounded process shutdown if Minecraft cannot respond. Saved world data and partial evidence are retained, but a fatal JVM error cannot guarantee the latest unsaved changes or complete recording. These failures queue analysis, without automatically restarting gameplay.

The in-memory LLM flight history retains at most 2,048 calls and an estimated 64 MiB of payloads, evicting older calls when either limit is reached. Request, streamed/raw response, and parsed response growth all count toward that budget. Queries report truncation; disk records already written remain intact. An individual record exceeding the budget is evicted as well. Playtest JSON is streamed to disk instead of assembling entire journal/export strings in memory.

`airicraft status` exposes `automaticPlaytest.state`, `outputDir`, and `error` while the client is alive; use the worker's `bridge-state.json` through `AIRICRAFT_BRIDGE_STATE_FILE`. `CAPTURE_READY` means a reported incident is paused and Airicraft evidence is ready, but the Play still needs finalization. `FINISHED` means a normal exit closed the flight recording. The launcher archives stopped runs with these summary outcomes:

| `status` | Meaning |
| --- | --- |
| `COMPLETED` | Complete dataset recording, with no bug report |
| `REPORTED` | Complete dataset recording with a planner bug report and verified paused checkpoint |
| `INCOMPLETE` | Retained crash, capture failure, or partial recording; inspect `missingArtifacts`, `message`, and `harness-summary.json` |

Select `recordingComplete: true` when a dataset requires fully finalized evidence. `bugReported` and `terminationReason` distinguish why a run stopped from recording completeness. A top-level directory alone does not imply a complete run. An ordinary exit's `world-save.json` identifies a last-saved-world copy, not a paused incident checkpoint.

A JVM crash preserves streams already written and any unfinished Recorder Play/replay scratch files. It cannot guarantee the final in-flight LLM response, last buffered frame/event, final snapshots, a clean replay ZIP, or the latest unsaved world changes. The launcher archives that evidence as `INCOMPLETE`. If the launcher itself is killed or the machine loses power, files remain under `.in-progress`; once all writers have stopped, archive them with:

```sh
scripts/automatic-playtest --recover automatic_playtest/.in-progress/<run-id>
```

Recovery refuses live launcher/client processes and locked worlds. It preserves partial files rather than fabricating missing records or a finalized replay. It also supports older runs that only wrote an `INTERRUPTED` summary; missing final flight records keep those archives `INCOMPLETE`. It never restarts or stops an active run. Start another launcher invocation for another run.

Override the output root with `--output /absolute/path`. Each invocation uses a unique run ID, copied game directory and bridge state file, and disables JDWP, allowing independent invocations to share the output root. Workers remain under `run/automatic-playtest-workers/<run-id>/`; configs stay there rather than in the published bundle. The mode is opt-in and requires a local integrated server. `--max-seconds` bounds wall time after join (default 1800), so hung planners do not leave clients running forever. Use `--max-seconds 0` for an open-ended run with no time limit. It keeps running until a bug report, planner degraded mode, fatal error, sustained bridge failure, capture failure, client exit, or explicit interruption; choose an ongoing objective if you also want no goal completion condition.

## Codex parent-task follow-up

When launched with `CODEX_THREAD_ID` (normally inherited from a Codex task), the helper queues messages to that task using `codex queue --thread ... --message ...`. On `something_wrong`, it verifies both clocks are paused, queues one `live_debug` handoff, and keeps supervising the open client. The gameplay time limit is suspended during diagnosis. The handoff includes the bridge-state path, helper PID, pause evidence, and staging directory. The world checkpoint and finalized Recorder Play are produced on later shutdown. Other non-manual exits queue after shutdown and finalization. The repo-owned [airicraft-playtest-loop skill](../.agents/skills/airicraft-playtest-loop/SKILL.md) governs the follow-up: `something_wrong` reports are inspected live, apparent fixes checked, and the incident world resumed. Multiple plausible solutions or opinionated tradeoffs require the user's choice before editing or resuming. Crashes, planner degradation, incomplete captures, and other exits are analyzed and left stopped for user instructions.

The helper retains launch context and queue status under `run/automatic-playtest-handoffs/`. Manual launcher interrupts, normal game-window closes, and leaving the world do not queue follow-ups. Queue failures are reported on stderr without changing the recording exit code; no automatic delivery retry occurs. `--no-codex-notify` opts out and restores save-and-exit behavior, and runs outside Codex or `--recover` invocations never queue a follow-up. SIGKILL and power loss bypass the hook. The CLI must be available and authenticated in the helper's environment.

## Evidence for offline analysis

The evaluator and automatic playtests share `RuntimeFlightRecorder`; this is the same runtime evidence writer, not a separate recorder implementation.

| File | Contents |
| --- | --- |
| `bug-report.json` | Present only on a report: original natural-language description, report ID, wall time and client tick; classified as a suspected interface bug |
| `recording-start.json`, `summary.json` | Run identity, world/dimension, outcome and stream truncation flags |
| `events.jsonl`, `debug-timeline.jsonl`, `llm-calls.jsonl` | Incremental events, correlated tool/decision history and full LLM flight records from session start; a stream file appears when its first record arrives |
| `status-samples.jsonl` | Periodic session, active work and execution state |
| `planner-calls.jsonl` | Finalized planner call journal, matching evaluation output |
| `agent-*-final.json`, `world-evidence-final.json` | The evaluator's standard terminal runtime snapshots |
| `pause.json`, `paused.png` | Report-only debug session/epoch, frozen world snapshot and screenshot when capture succeeds |
| `pause-verification.json` | Report-only launcher verification that both client and server ticks were paused before shutdown |
| `live-recording.jsonl` | Full-run structured observations and frame metadata, with manifest and final `export_complete` record; image bytes live in the MP4 |
| `screen.mp4`, `screen-frames.jsonl` | Live H.264 screen capture and sampled-frame clock anchors |
| `recorder/` | Required finalized Recorder Play, including structured events, replay archive and Airicraft planner extension; locate it through the relative `recorderPlayPath` in the summary |
| `world-save/`, `world-save.json` | Report: paused world checkpoint with exact server tick/time. Ordinary exit or crash: last saved world copied after process exit; unsaved crash changes are unavailable |

For new recordings, the files in the table are staging names, not the published layout. The completed Play has three clear owners:

- `metadata.json` and `capture/`: unchanged Recorder Play inputs.
- `renders/fpv.mp4` and `renders/fpv.json`: first-person screen capture and its generic FPV timing manifest, used by the normal Monitor and Preview panels.
- `extensions/airicraft.playtest/`: one `playtest.json` analysis entry point plus compressed flight streams, one `flight-final.json.gz` containing all terminal snapshots, screenshots, and `world-save.zip`. The required extension `manifest.json` lists assets.

`playtest.json` consolidates run outcome/objective/times, environment, bug report, pause verification, checkpoint, client exit, stream integrity, and evidence sizes. `recording-start.json` names the client's own player; when the recording profile also captured other connections, as in a [hosted playtest](hosted-playtest.md), finalization selects that player's Play and lists the others under `participants`. Staging summaries and metadata are not copied as separate assets; process IDs and temporary worker paths are omitted. Interrupted metadata bytes remain separate recovery evidence if they cannot be parsed. The small run index retains `summary.json` for discovery/recovery; it is not needed to copy or open the Play. Earlier recordings remain unchanged.

Point the recorder viewer's `[paths].artifacts` at the absolute `automatic_playtest` output root, start its catalog server, and select a Play. The **Monitor** displays its FPV; dragging a completed Play to the timeline enables synchronized video playback. The **Playtest** panel opens automatically with parsed metadata, report-to-timeline seeking, and bounded/searchable evidence previews. An incomplete Play can still be selected for FPV and metadata inspection without becoming an admissible world replay.

Automatic playtests sample the framebuffer at up to one frame per server second (640×360 RGB; identical frames are skipped). Structured observations are streamed to disk each tick. Sampled JPEGs feed a background H.264 encoder and are omitted from the JSONL stream. The MP4 uses independently flushed fragments with a two-frame keyframe interval, preserving decodable earlier fragments after a process crash. Identical images are held during quiet periods. Staging `screen-frames.jsonl` provides video time and debug clock; the publisher writes authoritative Server tick anchors in `renders/fpv.json`. The server thread records both clock coordinates together. Monitor holds the last captured image rather than assuming video time equals ticks divided by 20, including after timeline trim/cut/move operations. No audio is captured. Reported incidents also include a full paused screenshot.

Recorder Play supplies the structured recorded world/packet state for replay and inspection; it covers client-visible capture, not an authoritative server checkpoint at every historical tick. `world-save.zip` is one saved checkpoint, not a sequence of historical saves. Normal disconnect can add a short tail to the Play after a report tick; use `playtest.json` report, pause, and checkpoint ticks to locate the incident. Extracting the checkpoint into a separate saves directory lets an investigator resume from there. A failed run before player join remains an ordinary harness archive because no Recorder Play identity exists yet. Publishing a crash extension never fills in recorder metadata end fields or repairs an unfinished replay.

LLM calls can appear first as pending and later as completed or failed under the same `record.sequenceId`. When analyzing `llm-calls.jsonl`, keep the last record per sequence. Intermediate streaming previews remain in dashboard history; the disk flight stream retains the initial observation and terminal response.

Reports are candidates for review, not confirmed defects. Self-reporting complements harness timeout/failure detection: a planner or process that hangs cannot call `something_wrong`. Partial recordings remain available for that investigation. Disk recording has no automatic retention cleanup. An already-running client keeps its loaded code; these exit hooks apply to new launches, while existing runs continue streaming their evidence without interruption.

The game directory is isolated, but the production launcher still reads the checkout's built mod JARs. Do not rebuild that checkout while its client is running: lazily loaded classes can fail if the open JAR changes. Use a separate checkout for concurrent development until runtime JARs are isolated too.

## Verification

The 2026-09-17 controlled live smoke used the embedded planner and supplied recording profile. `something_wrong` paused both clocks at server tick 218; `world-save.json` recorded that same tick, and the saved `level.dat` time matched its world time of 246329. Shutdown completed through the bridge with exit code 0. The published Play had completed metadata, events, a CRC-valid replay ZIP with Flashback data and chunk caches, and the planner extension. All three LLM calls retained completed responses, the report receipt reached the debug timeline, and the live RGB stream completed without sequence gaps. No posthoc RGB rendering was used.

Additional isolated live runs verified a time limit and SIGTERM to the launcher both produced `COMPLETED` datasets with finalized Recorder Plays and no bug report. SIGKILL to a disposable Minecraft process produced an `INCOMPLETE` archive retaining every previously measured stream byte, external recorder events, and unfinished replay files. The existing open-ended client was left running throughout. Three-second samples of that client's live RGB and external Recorder Play event files showed both growing continuously; status samples advanced every five seconds. The supplied recorder uses a 256 KiB event buffer, not a fixed periodic fsync, so this observation is not a power-loss durability guarantee. Crash recovery preserves raw bytes, including a possible partial final JSONL line; consumers should read complete records and ignore an invalid trailing record.

The original open-ended client subsequently crashed after about 30 minutes with `ZipFile invalid LOC header` while loading `DropItemsTaskExecutor$DropSlot`, consistent with the shared build JAR being rebuilt during development. Offline recovery archived 5,996,268,380 bytes, including 1,771 RGB frames with no malformed JSONL records, flight evidence, raw Flashback chunks/chunk caches, and the saved world. Its replay ZIP was unfinished, so the archive remains `INCOMPLETE`; the original summary and crash report were retained.

## Playtest extension contract

`airicraft.playtest` uses the existing Artifacts V1 manifest, identity, containment, and `SERVER_TICK` time-domain conventions. Airicraft owns the payload schemas:

- Role `playtest`, schema `airicraft.playtest.v1`, `playtest.json`: version 1, consolidated run metadata, observed timeline bounds, debug clock offset, bug report, environment, pause, checkpoint, execution, stream integrity, optional paused state, and file sizes. An unfinished Play's observed bounds are evidence coverage, not a completion marker.
- Roles `final_state`, `observations`, `world_checkpoint`, `recorder_scratch`, and `evidence`, schema `airicraft.evidence.v1`: terminal snapshots grouped under their named fields, and retained streams with original evaluation/dashboard payloads; stream gzip decompresses to the original bytes, including a possible incomplete final line. Original client ticks, debug tick IDs, and wall times stay explicitly named. Translate a dashboard `serverTickId` by subtracting `debugTickOffset`; do not reinterpret an agent `tick` as a Server tick.

The extension is staged beside the Play and published before its server directory moves into the shared `v1/` collection. Source evidence is removed only after publication. The run index journals the target before the move, allowing `--recover` to finish an interrupted publication without duplicating or modifying capture inputs. An encoder failure or missing video excludes a new run from complete datasets. This is process-crash recovery, not a power-loss durability guarantee.

The FPV is ordinary Play media, not an extension asset. `renders/fpv.json` follows recorder `FpvVideoManifest` ProtoJSON: exact Play identity, geometry, encoded frame rate/count/duration/size, completeness, and sampled `{serverTick, videoSeconds}` anchors covered by retained packets. It does not fabricate replay-render provenance.
