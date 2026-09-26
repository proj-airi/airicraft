# Diagnostic reports

Use **Airicraft Settings → Save bug report** during normal play, or from the title screen after an incident. The report is saved under `airicraft-reports` in the game directory; **Open report folder** opens that directory. Attach the file and describe what you expected, what happened, and roughly when. Saving does not reload settings, reset the agent, pause gameplay, or upload anything. It also works when the dashboard server is disabled.

The Runtime Observatory has the same **Save bug report** download. Its **Raw developer export** button retains the existing replayable recording export, including raw prompts, responses, chat, logs and captured images. The two formats serve different purposes: a diagnostic report is a summary, not a replay, Recorder Play, world backup or crash dump. A client that has already exited cannot export its in-memory history.

## Contents and limits

Reports reuse `DashboardObservationStore`; there is no new collector or telemetry pipeline. A capture freezes the store's metadata and immutable observation references under one lock, then projects and writes evidence off the client thread. IO and JSON processing do not hold the recording lock. Slow downloads cannot backpressure the game tick.

The incident window is the latest 1,200 completed integrated-server ticks (nominally one minute at 20 TPS). When server timing is unavailable, it uses existing client/agent ticks and explicitly labels that clock. Neither clock measures wall-clock duration. The report retains timestamps and original server/client stamps without inventing remote server ticks. The most recent retained runtime snapshot is included separately with its original stamps; it can predate the window or world disconnect. `runtimeState.available: false` means no retained snapshot exists.

Evidence includes:

- Packaged build revision (with `-dirty` for a modified build), mod/MC versions, loaded mod IDs/versions, Java version, configured provider hosts and model identifiers. Source archives without Git use `unknown` unless built with `-Pairicraft.buildRevision=<revision>`.
- Runtime availability, planner phase, task state/progress, survival-reflex state/cause, player health/food, dimension, and screen, where captured.
- Semantic event types, selected failure codes and work IDs, timeline domains/actions, model-call status/HTTP code/timing/token usage, and observation gaps.

Projection selects named scalar fields. It omits chat, goals and other free text, model inputs/outputs, logs, screenshots, credentials, endpoint paths/query strings, player names, and unknown fields. These omissions are intentional and counted separately from lost evidence; reports do not promise enough detail to resolve every bug. Model/provider identifiers and selected work IDs remain visible. Raw export is the explicit path for deeper investigation.

The newest matching observations take priority. Each report includes at most 2,000 incident observations and 2 MiB of encoded observation lines, plus manifest/runtime metadata and the footer. Selected string fields are capped at 256 UTF-16 code units and clipping is reported. These are report limits, independent of recording retention limits.

## Canonical format: version 1

The [JSON Schema](diagnostic-report-v1.schema.json) describes each JSONL record. The file uses UTF-8, one JSON object per line, with a final newline. Record order is:

1. Exactly one `manifest`.
2. Zero or more `observation` records, in original increasing sequence order.
3. Exactly one `integrity` footer.

### Manifest

| Field | Meaning |
| --- | --- |
| `schema`, `schemaVersion` | `airicraft.diagnostic-report`, integer `1`; separate from raw recording schema versions |
| `reportId`, `createdAtMs`, `mode` | New report UUID, export wall time in Unix milliseconds, `user_summary` |
| `correlation.recordingSessionId` | Existing recording session UUID; all selected observations come from this frozen session |
| `correlation.hostedSessionId` | `null` in M1; reserved for an explicit hosted-session identifier in M1.5, never inferred from a server address |
| `environment` | Build identifiers, loaded mods, Java and configured controller/vision/thinker providers/models; `unknown` denotes unavailable build/provider identity |
| `window` | `clock` (`server_tick` or `client_tick`), inclusive `fromTick`/`toTick`, `requestedTicks`, frozen `throughSequence`, server-clock availability and pause state |
| `runtimeState` | `available` flag and, when available, the most recent retained runtime `observation`, carrying its original stamps |
| `coverage` | Loss, omission and clipping counters described below |
| `privacy` | Projection identifier `allowlisted_summary_v1` and excluded data categories |

`coverage.truncated` is true when the source reports drops, an in-window observation gap exists, report limits omit records, or selected fields are clipped. `droppedByType` and `expiredByType` are **recording-session-wide** counters, not incident-local counts. A prior budget drop therefore conservatively marks later reports truncated. Normal age expiry alone is not a loss inside the requested window. `observationGap`, `reportLimitOmitted`, and `clippedFields` expose the other causes. `excludedByPolicy` counts omitted record types inside the window and does not itself imply truncation. `retainedObservationCount` counts the frozen source; `includedObservationCount` counts incident lines, excluding the separate runtime baseline.

### Observation

`sequence` retains the recorder's identity; gaps need not indicate damage because projection and window selection omit records. `tick` is the client/agent tick, `serverTickId` and `throughServerTickId` retain the original recording stamps, `capturedAtMs` is the original Unix timestamp, `type` identifies the original observation type, and `payload` contains only the versioned projection. Consumers must check `window.serverClockAvailable` before interpreting server stamps.

### Integrity

The footer contains `complete: true`, `algorithm: SHA-256`, `sha256`, `bytes`, and `observationCount`. The checksum and byte count cover **every exact UTF-8 byte before the footer**, including the manifest and newline separators. The count covers incident observation lines, not the embedded runtime baseline. Verify all three before treating a transferred file as intact. A missing footer, mismatched count/hash, or trailing data means the file is incomplete or altered. This is transfer integrity, not authenticity or a guarantee of complete source history; also inspect `coverage`.

Local saves use a temporary `.partial` file and atomic rename after successful writing. Failed saves remove their temporary file. HTTP downloads stream the same representation; interrupted downloads lack a valid footer. Existing developer export and playback contracts remain unchanged.
