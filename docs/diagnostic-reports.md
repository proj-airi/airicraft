# Diagnostic reports

Use **Airicraft Settings → Report a problem** during normal play or from the title screen after an incident. Opening **Report this moment** fixes the incident marker. Add an optional description, choose attachments, then select **Preview attachments**. **Save these attachments** is the explicit consent step. Changing the description or attachment mode requires a new preview. Canceling or previewing alone writes no file. **Inspect evidence** opens the exact prepared report as readable pages: the complete summary, build/session metadata, every included observation, integrity footer, and decoded screenshot pages. Use Previous/Next to switch pages and scroll or Page Up/Page Down for long text. Returning to the report preserves the preview.

The ZIP is saved under `airicraft-reports` in the game directory; **Open report folder** opens that directory. It contains `report.jsonl` and `summary.txt`. Review it before attaching it to your issue. Nothing is automatically uploaded. Reporting does not reload settings, reset the agent, or pause gameplay, and works with the dashboard server disabled. A client that has exited cannot recover its in-memory history.

The Runtime Observatory offers the same flow through **Report this moment**. Its preview includes expandable evidence records, decoded attached screenshots, the complete summary, and a read-only view of the exact `report.jsonl` attachment. Content is rendered as text; external image URLs are never fetched. Both viewers derive their contents from the frozen, redacted attachments, and editing clears the old evidence. Its separate **Raw developer export** retains the full replayable recording format, asks for confirmation, and does **not** redact credentials. Use it only for trusted debugging. Diagnostic ZIPs are not replay recordings, world backups or crash dumps.

## Attachment choices and privacy

| Choice | Included evidence |
| --- | --- |
| **Minimal** (default) | Description, build/mod/Java/provider/model identities, anonymous recording session ID, incident ticks and coverage counters. No recorded observations or runtime/world snapshot. |
| **Summary** | Minimal metadata plus allowlisted diagnostic events, model-call statistics, runtime/task state, world/session availability, dimension and player health/food. No chat, goals, model inputs/outputs, logs or screenshots. |
| **Developer** | Minimal metadata plus bounded retained observations, including chat, model inputs/outputs, logs, full world/session metadata and captured screenshots. The preview explicitly discloses these classes. |

Known configured controller/vision API keys, tracing header values, bridge credentials and dashboard viewer tokens are redacted from all report modes, including descriptions and metadata. Secrets are captured at the marker and refreshed when preparing the preview. Credential fields, authorization values, URL userinfo and common credential assignments are also redacted. Text redaction happens before summary-field clipping. Unknown secret formats and personal information in free text cannot be exhaustively recognized. **Screenshot pixels are not automatically redacted**; choose Developer only when you intend to share them, and inspect the bundle first.

The summary projection selects named scalar fields and omits unknown fields. It includes packaged build revision (`-dirty` for a modified build), mod/MC versions, loaded mod IDs/versions, Java version, configured provider hosts and effective model identifiers. Source archives without Git use `unknown` unless built with `-Pairicraft.buildRevision=<revision>`. It retains selected failure codes/work IDs, planner phase, task progress, reflex state/cause, timeline domains/actions, model-call status/HTTP code/timing/token usage and observation gaps. Summary omissions are intentional and do not promise enough detail to resolve every bug.

## Incident window and limits

Reports reuse `DashboardObservationStore`; there is no new collector or telemetry pipeline. Marking copies immutable observation references and recording metadata under one short lock. Selection, JSON processing and IO happen outside that lock. Preview builds an immutable report; saving uses that exact report rather than recapturing current history. Recording resets and new events do not move the marker.

The incident window is the most recent 1,200 completed integrated-server ticks at the marker (nominally one minute at 20 TPS). Without server timing, it uses existing client/agent ticks and labels that clock. Neither clock measures wall-clock duration. Original timestamps and server/client stamps are preserved without inventing remote server ticks. Summary and Developer also include an allowlisted most recent runtime baseline with its original stamps; it can predate the window or world disconnect. `runtimeState.available: false` means no retained baseline was included (always false in Minimal).

The frozen draft retains at most 8 MiB of source observation data, newest first, with source omissions counted. Each report includes at most 2,000 incident observations and 2 MiB of encoded observation lines, plus the manifest/runtime baseline and footer. Summary strings are capped at 256 UTF-16 code units, after redaction. Description input is capped at 2,000 characters. These bounds are independent of recording retention. Large developer observations, including screenshots, can be omitted by the limits; inspect coverage rather than assuming every class was captured.

The dashboard keeps one pending draft/preview per server. A new marker replaces it; it expires after ten minutes. Replaced or expired previews must be recreated explicitly. Native report screens own their bounded draft until closed.

## Canonical format: version 2

The [v2 JSON Schema](diagnostic-report-v2.schema.json) describes each `report.jsonl` record. The [v1 schema](diagnostic-report-v1.schema.json) remains available for older standalone JSONL reports. The ZIP also contains `summary.txt`: description, window, attachment classes, observation/failure-indicator counts, truncation, build/model metadata and recording session ID. Failure indicators are counts of captured FAILED statuses or failure/error event types, not inferred root causes.

JSONL uses UTF-8, one JSON object per line, with a final newline. Record order is exactly one `manifest`, zero or more `observation` records in increasing original sequence order, then exactly one `integrity` footer.

### Manifest

| Field | Meaning |
| --- | --- |
| `schema`, `schemaVersion` | `airicraft.diagnostic-report`, integer `2`; separate from raw recording versions |
| `reportId`, `createdAtMs`, `markedAtMs` | Marker UUID and marker wall time in Unix milliseconds; stable across previews of that marker |
| `description`, `mode` | Redacted description; `minimal`, `user_summary` or `developer` |
| `correlation.recordingSessionId` | Existing recording session UUID; all evidence comes from this frozen session |
| `correlation.hostedSessionId` | `null`; reserved for an explicit hosted-session identifier, never inferred from an address |
| `environment` | Build, loaded mod, Java and controller/vision/thinker provider/model identifiers |
| `window` | Clock, inclusive `fromTick`/`toTick`, requested ticks, frozen `throughSequence`, server-clock availability and pause state |
| `runtimeState` | Availability flag and optional allowlisted baseline `observation`, carrying its original stamps |
| `coverage` | Loss, omission and clipping counters |
| `privacy` | Projection (`allowlisted_summary_v1` or `redacted_developer_v1`), included classes, credential redaction and screenshot warning |
| `summary` | Human-readable `text`, `failureIndicatorCount` and `observationCount` |

`coverage.truncated` is true for recording drops, in-window observation gaps, source/report limit omissions or clipped fields. `droppedByType` and `expiredByType` are recording-session-wide counters, not incident-local counts. A prior budget drop conservatively marks later reports truncated. Normal age expiry alone is not loss inside the requested window. `observationGap`, `sourceLimitOmitted`, `reportLimitOmitted` and `clippedFields` expose other causes. `excludedByPolicy` counts intentionally omitted record types during Summary projection; Minimal skips observations altogether. `retainedObservationCount` counts the bounded frozen source; `includedObservationCount` counts incident lines, excluding the runtime baseline.

### Observations and integrity

`sequence` retains recorder identity; gaps can result from selection. `tick` is the client/agent tick; `serverTickId` and `throughServerTickId` retain original recording stamps; `capturedAtMs` is the original Unix timestamp. `type` identifies the observation; `payload` is the allowlisted projection in Summary and redacted retained content in Developer. Consumers must check `window.serverClockAvailable` before interpreting server stamps.

The footer contains `complete: true`, `algorithm: SHA-256`, `sha256`, `bytes` and `observationCount`. The checksum/byte count cover every exact UTF-8 byte before the footer, including the manifest and newlines. The count excludes the embedded baseline. Verify all three; a missing footer, mismatch or trailing data means incomplete/altered evidence. Integrity is not authenticity or proof of complete history; also inspect coverage.

Every local save gets a unique filename, including repeated saves of the same preview. Editing a description or changing attachments for the same incident creates another ZIP and leaves earlier files intact; the `reportId` inside remains the original incident marker. Local saves write a temporary `.partial` ZIP and atomically rename after success, cleaning up failed temporary files. HTTP downloads stream the same bundle. Existing raw export/playback contracts remain unchanged.

### Dashboard API

All report routes require the viewer Bearer token and POST JSON (maximum 16 KiB). They affect report drafts only, never gameplay.

1. `/api/report/mark` with `{}` returns `draftId`.
2. `/api/report/preview` with `{"draftId":"…","request":{"mode":"MINIMAL","description":"…"}}` returns the manifest plus `previewId`, `attachments` (exact `summary.txt` and `report.jsonl` strings), and `evidence` (readable text/image pages). Request modes are `MINIMAL`, `SUMMARY`, `DEVELOPER`.
3. `/api/report/save` with `{"previewId":"…","consent":true}` downloads the exact reviewed ZIP. It accepts no replacement evidence/options. Missing consent is rejected; stale IDs return 409.

The old immediate GET `/api/report` returns 405. A later preview replaces the earlier preview ID; a new marker replaces both marker and preview. No network IO holds the report-state lock or recording lock.
