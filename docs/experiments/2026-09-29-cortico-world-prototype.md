# Experiment: airicraft as a Cortico World (AI VTuber prototype)

Status: **exploratory, not adopted.** Nothing here commits the project to a direction. The prototype is meant to live
in forks (a Cortico fork, an AIRI fork) plus, at most, small opt-in bridge additions here. If it fails, delete the forks.

## Question

Can a single brain, [Cortico](https://github.com/Pal-AI-Lab/Cortico), with Minecraft and the AIRI stage mounted as
Worlds, keep a streamer companion present and reactive without a second character agent, and without the "one tool,
then idle" stall the embedded planner shows today?

## Why

Symptom: after one reflex-triggered `say`, the embedded planner sits idle. What the code does (read at `a1c05d8`-era
`main`):

- Wakes come from events (attention-gated, budgeted), `goal_continuation` (needs an active goal, and holds, resetting
  lower generators, while a goal is blocked or work is accepted) and `idle_think` (30 s quiet, then 90 s cooldown, only
  with an idle job, reset by every delivered event wake).
- Turns can carry 20 tool calls (`PlannerOrchestrator.MAX_TOOL_CALLS_PER_TURN`); the one-call turns are model habit
  encouraged by the prompt ("Do not create work to mean idle", "simply acknowledge, without inventing filler work").
- `idle_think` is written as survival free time, not presence.

Two-agent designs (a persona LLM plus an airicraft LLM behind AIRI's `spark:notify`/`spark:command`) were rejected:
two agents playing one character split decisions and context ("octopus"). Cortico avoids this by construction: one
consumer, and Worlds communicate only through events and tools.

## Architecture

```
 AIRI stage (Tamagotchi fork)              Cortico                          airicraft (Fabric mod)
 ┌────────────────────────────┐   WS   ┌───────────────────────┐   HTTP   ┌─────────────────────────┐
 │ avatar, TTS, viewer chat,  │◄──────►│ Persona + Memory      │◄────────►│ sensors, reflex, nav,   │
 │ voice in                   │        │ Core: WakeBus + loop  │  bridge  │ action graphs           │
 │  = World "airi-stage"      │        │ Worlds: airi-stage,   │          │ embedded planner dormant│
 └────────────────────────────┘        │  airicraft, (bilibili)│          │  = World "airicraft"    │
                                       └───────────────────────┘          └─────────────────────────┘
```

| Concern | Owner |
|---|---|
| Persona, memory, speech decisions, promises | Cortico Persona/Memory |
| When the persona wakes | Cortico `WakeBus` (trigger modes chosen by the producing World) |
| Reflexes, navigation, execution, receipts | airicraft (no LLM on this path) |
| Speech playback, avatar, viewer/voice input | AIRI stage |

Speech promises are not commitments: nothing turns a spoken line into a goal. Only an explicit tool call acts.

## Pieces

### A. `airicraft` World (Cortico extension `cortico-world-airicraft`, TypeScript)

Cortico loads a World from an npm package exporting a `WorldDefinition` (`docs/extensions.md`), so no Cortico core
change is needed. It talks to the existing localhost bridge; the embedded planner is dormant, as with
`scripts/codex-driver` (`enableExternalDriver`).

Verified in this repo, so slice 1 needs **no Java change**:

- `GET /v1/agent/tools` lists the driver tools; `POST /v1/agent/tools` runs one with a timeout and returns text plus an
  optional image (`ModBridgeServer.handleAgentTools`).
- `GET /v1/agent/events/recent?since=<seqNo>` is a cursor feed with `oldestSeqNo`, `latestSeqNo`, `truncated`.

Mapping:

- Tools → `ToolDef` (`tags` read/act/flow, prefix names, e.g. `ac_`, since Cortico requires globally unique names).
  Images → blobs. `AbortSignal` → the airicraft cancel-work tool.
- Events → `pushEvent` with a trigger mode chosen in the adapter from event type, mirroring the attention rules: reflex
  start → `preempt`; direct guidance and work finished/failed → `flush`; percepts → `debounce`; everything else →
  `piggyback`. `truncated` becomes an explicit gap event (Cortico: state only facts).
- Current state → `pushDeferred` + `piggyback`, rendered at delivery time (the same idea as `observe`'s `current`).
  Open: which bridge endpoint supplies it (`/v1/agent/context` looks closest; unverified).
- A `ENV_PROMPT.md` for the World: queued work, receipts ("accepted" is not "done"), what perception honestly covers.

Later (only if the prototype earns it): an SSE feed instead of polling, and the attention decision attached to each
event so the adapter stops re-deriving trigger modes.

### B. `airi-stage` World: start from moeru-ai/airi PR 2634, do not rebuild it

[moeru-ai/airi#2634](https://github.com/moeru-ai/airi/pull/2634) (read at its tip, `2598138`; review state not
visible from here) already implements most of this piece as `packages/cortico-bridge`:

- A Node process embeds Cortico Core plus the reference persona `corti-soulmate` (vendored as the `vendor/cortico`
  submodule) and exposes the stage as a World `airi` over a WebSocket (`AiriWorld`, port 6122).
- Stage input frames become `airi.user_message` / `airi.<kind>` events. Persona tools are `airi_speak`, `airi_act`
  (emotion/motion/delay), `airi_name_session` and `airi_call`. `outputTap` streams draft deltas; `onTurnEnded`
  sends `turn_end`.
- Stage side, `stores/cortico.ts`: speak/act/delta frames are replayed through the existing chat hooks
  (`emitTokenLiteralHooks`, ...), so TTS, Live2D and the chat UI work unchanged. This is a lighter sink than the
  `speechRuntimeStore.openIntent` route considered earlier.
- The stage pushes its active provider to the bridge, which generates through it, so AIRI settings stay the single
  source of provider config.
- A server-channel client joins AIRI as module `cortico`, takes over `input:text`, and turns `spark:notify`,
  `spark:command` and `context:update` from other mods into persona events (`flush`/`debounce`/archive).

Gaps this experiment should fix in a fork (observations from reading the code, not a review verdict):

- **Separation is a switch inside the old pipeline.** `if (useCorticoStore().enabled) return` is threaded through
  `chat.ts`, `context-bridge.ts` and the character orchestrator store, plus a rewritten memory settings page that
  replaces the long-term and short-term pages. Fine for a prototype, hard to carry; keep the diff to the switch.
- **No playback feedback.** `airi_speak` returns `[spoken]` at once; nothing reports that TTS finished or was
  interrupted, so the persona has no "I finished talking" beat and no way to avoid talking over herself. This is the
  presence signal we need. Add `speak`-tagged playback events from the stage.
- **No trigger modes chosen for stage input.** User text takes Cortico's external default (`debounce`). Direct chat
  should likely be `flush`.
- **Spark stays as the mod path.** Other mods reach the persona through `spark:*`, which keeps a sub-agent arm behind
  each mod. airicraft should mount as a first-class World instead (piece A), not through spark.
- **`vendor-patches/cortico-local-fixes.patch`** (274 lines) is lint-style rewrites of the Cortico submodule
  (regex to `startsWith`, spread removal), no integration behaviour. Use a fork of Cortico rather than a patch file.

Mounting airicraft is then close to one line in `host.ts`: add the `airicraft` World to the `withWorlds(...)` list
beside `TERMINAL`, `WEBSEARCH` and `QQ`.

### C. Persona

Cortico's reference persona with a streamer prompt. Character from airicraft's card
(`config/airicraft/character.json`, CCv3) copied by hand for the prototype. Memory is Cortico's.

Known losses: AIRI's persona memory and its plugin-hub connectors (YouTube, Discord, ...) do not carry over. Only what
Cortico already has as Worlds does (e.g. `bilibili` livestream chat).

## Where the code lives (no fork yet)

Goal: keep everything on this branch, modify neither upstream repo's history, and keep the Gradle build and CI
untouched.

- **Cortico: unmodified.** Worlds load as extensions (`docs/extensions.md`): an npm package exporting a
  `WorldDefinition`, loaded from `extensions/` or from `CORTICO_EXTENSIONS_DIR`, installable from a local directory.
  Both new Worlds (`airicraft`, and the stage World adapted from PR 2634's `world.ts` without the spark/channel code)
  live in this repo and are linked in. `worlds.<id>.enabled` in the deployment config turns them on for the reference
  persona, so no bot change either.
- **AIRI: unmodified base plus a small patch.** The stage only emits `output:gen-ai:chat:*` toward modules
  (`context-bridge.ts`) and does not consume them to speak, so a stage-side change is unavoidable: the speech/act
  replay store from PR 2634 (`stores/cortico.ts`), the `enabled` early-returns, and a toggle. The bridge package,
  channel/spark client, memory-page rewrite, i18n and vendor patch are not needed, because Cortico's own launcher hosts
  the persona. The patch is kept as `git format-patch` files against a pinned AIRI commit.
- **Not a submodule.** A submodule pointer must name a commit reachable on some remote, so a patched AIRI needs a fork
  to host it; PR head refs are also fragile if the PR is rebased. AIRI is a ~5.7k-file pnpm monorepo, which would make
  every clone heavier. This repo's precedent for third-party artifacts is to fetch them into an ignored cache and never
  vendor them (`.airicraft-compat/`).
- **Layout.**

  ```
  experiments/cortico-world/          # outside the Gradle build
    worlds/airicraft/                 # Cortico World extension
    worlds/airi-stage/                # stage World (from PR 2634)
    e2e/                              # Cortico Core + both Worlds + a scripted model, against a live client
    patches/airi/*.patch              # stage-side changes on the pinned AIRI commit
    pins.json                         # Cortico and AIRI commit SHAs
  scripts/cortico-experiment          # setup: clone pins into .cortico-experiment/ (gitignored),
                                      # apply patches, link extensions; run
  ```

- **When to fork after all:** the AIRI patch outgrows a few hundred lines or needs constant rebasing, or the work is
  ready to go upstream (which needs a fork branch for the PR anyway).
- **Testing here:** everything runs in the session container, including a real Minecraft client (recipe under
  "Running it"). Unit tests use an in-process mock bridge and a socket stand-in for the stage; `e2e/run.ts` runs
  Cortico Core against the live client.

## Slices (each with an exit check)

0. **Baseline.** From an existing recording, measure today's stall: idle gaps, wakes per minute, tokens per minute.
   *Not done.*
1. **Text loop.** Cortico + `airicraft` World. Exit: the persona plays and keeps itself present between tool
   completions; no Java change. *Plumbing done and verified live with a scripted model; behavior with a real model
   not yet measured (no model endpoint in the session).*
2. **Speech out.** Stage World and stage patch. *Verified in the real stage: the patched stage-web in headless
   Chromium sends the typed message, receives `speak` frames and shows the persona's reply in chat
   (`e2e/stage-browser.ts`). Not verified: TTS playback and Live2D (no provider or model in the session). The stage
   does not yet send `speech_end`.*
3. **Input.** Viewer text and voice into the persona. *Text path exists in the patch; voice routing omitted.*
4. **Soak.** 30+ minutes with simulated or bilibili chat. Compare with slice 0. *Not done.*

## Verified so far (session container, live client)

- **Recipe.** JDK 25 for the Gradle JVM (Oracle tarball, outside the repo), `git submodule update --init`, then
  `./gradlew build -x test` (Maven Central rate-limits parallel downloads with HTTP 429 through the proxy; a retry loop
  with `--max-workers=1` got through in one extra pass, and Gradle caches what it fetched). Client:
  `xvfb-run -a -s "-screen 0 1280x720x24" scripts/codex-driver` with `LIBGL_ALWAYS_SOFTWARE=1` (Mesa llvmpipe). A
  world from `scenarios/<id>/world.zip` unzipped into `run/saves/<name>` shows up in `airicraft worlds list`; the CLI is
  `wrapper/build/install/airicraft/bin/airicraft`.
- **Bridge shapes match the adapter.** 72 driver tools in OpenAI function format; the events feed answers with
  `oldestSeqNo`, `latestSeqNo`, `truncated`, `events`; `POST /v1/agent/tools` runs real tools (`inspect_inventory`,
  `observe`, `navigate_to`).
- **The dashboard notice is noise.** The mod announces its (public, read-only) debug dashboard URL as a
  `social.system_message`; the World archives it instead of waking the persona with it.
- **The embedded planner's own tools are hidden** from the persona by default (goal, decision, event-policy, `say`,
  `report_to_me`): the persona owns objectives, speech and wake policy.
- **Cortico Core boots with both Worlds mounted** through its real extension path (`withWorlds`, config-enabled), tools
  and prompts included.
- **End to end with a scripted model, against the live client:** audience chat wakes the persona (`flush`); its
  `airi_speak` reaches the stage stand-in and `ac_inspect_inventory` / `ac_navigate_to` run in the game; the job's
  final state (`work.changed`, top-level, SUCCEEDED) wakes it again; perception events (a biome change) arrive;
  playback results (`airi.speech_ended`, `speak`-tagged, `piggyback`) ride along with the next wake instead of
  causing one.
- **The real AIRI stage works with the patch.** stage-web from the pinned commit plus `patches/airi/0001` runs under
  the Vite dev server (after building its workspace dependencies); with `onboarding/skipped` and the Cortico toggle set
  in localStorage, a message typed in the chat box reaches the persona over the World socket and the reply appears in
  the chat, next to the heartbeat-driven line.
- **Cortico already has a presence mechanism: the persona heartbeat** (`bots/cormini/persona/heartbeat.ts`). A
  baseline interval (`tick.intervalMinutes`) injects `[system] quiet for N seconds`; consecutive quiet ticks back off
  exponentially (x2, capped at x8) and any external event resets it. In the run (baseline 12 s) wakes came at about 8,
  20 and 44 s of quiet. This is the "dead-air generator" from the early brainstorm, with bounded cost built in. It is
  a fixed schedule, not chosen by the model.

## Running with a live model

`e2e/run.ts` switches from the scripted client to a real model when these are set in the process environment:

- `CORTICO_LLM_BASE_URL`: an endpoint that speaks the OpenAI **Responses** API (Cortico's `openai-responses-compat`
  provider; for example OpenAI or OpenRouter). A plain Chat Completions endpoint will not work.
- `CORTICO_LLM_MODEL`: the model name (on OpenRouter, `deepseek/deepseek-v4.1-flash`).
- `CORTICO_LLM_API_KEY`: the key. Cortico reads provider secrets from the process environment by name; nothing is
  written to disk.

Other knobs: `E2E_TICK_MIN` (heartbeat baseline in minutes), `E2E_WALK="x y z"` (an audience message asking to walk
there), `E2E_STREAMER=1` (use `e2e/prompts/streamer-orientation.md` as the persona's orientation).

### First live results (single 150 s runs, `deepseek/deepseek-v4.1-flash` via OpenRouter, 30 s heartbeat)

Anecdotal: one sample each, small model, short runs. Groundedness of what it said was not audited.

| | default orientation | streamer orientation |
|---|---|---|
| model requests | 13 | 17 |
| prompt / completion tokens | 255k / 0.9k (91% cached) | 348k / 0.8k (93% cached) |
| cost | $0.0055 | $0.0068 |
| spoken lines | 3 | 6 |
| heartbeat wakes answered with speech | 0 of 3 (all `end_turn`) | 3 of 4 |

- The first request is about 16k prompt tokens (persona prefix plus 72 tool schemas); later ones grow by roughly 100 to
  600 tokens per round. A model call takes about 1.7 s.
- With Cortico's default orientation ("silence is a normal action") the model ended every heartbeat wake silently:
  the dead-air problem again, as inaction by default. Swapping only the orientation text made the same wakes produce
  varied, non-repeating remarks about the scene, at about a tenth of a cent per minute.
- The model did multi-tool turns on its own (`survey_surroundings` with `take_a_look`, `airi_act` with `end_turn`), so
  "one tool per turn" is not a limit of this setup.
- One invented tool call (`ac_speak_placeholder`, no arguments) got an `[unknown tool]` receipt and was harmless.
- The heartbeat backs off (30 s, then 60 s) while nothing happens, so idle chatter also gets cheaper as it goes.

## Measurements

Dead-air ratio; wakes per minute; LLM tokens and cost per minute; tool calls per wake; latency from reflex event to
first comment; groundedness (sample utterances against the recorded state); reflex-vs-persona conflicts.

## Risks and unknowns

- The persona model must do what the purpose-built planner prompt does today (receipts, queue discipline). Expect
  prompt work in the World's `ENV_PROMPT.md`.
- Polling latency and the recent-events ring buffer (`truncated`) under event storms.
- Tool granularity: too fine-grained means many persona turns per task.
- Cortico's docs and reference prompts are Chinese; the persona and world prompts need a language decision.
- Two forks to keep in step with upstreams.

## Evidence read

Cortico: `PHILOSOPHY.md`, `src/core/README.md`, `src/core/types.ts`, `docs/worlds.md`, `docs/extensions.md`, the
Minecraft `definition.ts`, `ENV_PROMPT.md`, `tools.ts`. AIRI: `packages/plugin-protocol/src/types/events.ts`,
`packages/server-sdk`, `packages/stage-ui/src/stores/character/orchestrator/store.ts`,
`packages/stage-ui/src/stores/speech-runtime.ts`, `packages/stage-ui/src/services/speech/pipeline-runtime.ts`,
`integrations/minecraft` (bridge). PR 2634: `packages/cortico-bridge/src/{host,channel}.ts`, `world/world.ts`,
`world/ENV_PROMPT.md`, `server-sdk-shared/src/cortico.ts`, `stores/cortico.ts`, the diffs to `chat.ts`,
`context-bridge.ts` and the orchestrator store, and the vendor patch. Not read: Cortico `loop.ts`, its Minecraft
engine, `airi-llm.ts`, the memory settings page.
