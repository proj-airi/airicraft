# System 1.5: a continuously refreshed state document for Airicraft

Status: research proposal, 2026-09-24. No experiment has run yet. The tooling for every offline experiment and for
the zero-Java live experiments is in [`research/system15`](../../../research/system15/README.md) and tested.

## 1. Summary

Airicraft has two ways of deciding what to do. System 1 is hand-written Java that runs every tick: survival reflexes,
Baritone, task executors, and observers such as `SlowMiningObserver`. System 2 is an LLM planner that reads a
decision context and calls tools; each decision takes seconds. Between them sits a growing pile of hand-written
judgement that decides when System 2 should be woken, what a player's chat means, and whether current execution
looks wrong.

This proposal adds a **System 1.5**: a model that keeps a small, typed interpretation of the situation, the
**READING** form, current several times a second. Its input is one **state document**, a single structured text of
everything relevant ordered from stable to volatile. Its output rewrites only the READING's writable slots. This is
the old Airicraft paradigm (all state in one structured message, the model mutates writable fields, no conversation
history), reintroduced for the layer where it fits: fast, short-horizon, stateless apart from the document.

The model bet is **DiffusionGemma** (Google DeepMind, open weights, June 2026). Its 256-token canvas is the size of the
form, it re-predicts every position at each step so stale values can be revised, public APIs accept a *starting*
canvas, and its prompt is encoded causally into a KV cache like an autoregressive model's. Each refresh can therefore
start from the previous READING, renoise only the slots whose inputs changed, and settle in a few steps. The matched
autoregressive parent, Gemma 4 26B-A4B, gives a clean comparison: same weights, different decoding.

The guard rails come from the literature and ADR 0002. Current diffusion LMs fail as agent backbones and tool callers
([Bitter Lesson study](literature.md#q5-structured-output-do-diffusion-models-keep-a-form-valid)), so System 1.5 never
owns decisions or calls tools. It fills typed slots, and in the final phase only reversible knobs the runtime already
lets the active role turn during a reflex hold. Fast-only agents sometimes beat coupled ones
([Latent Bridge](literature.md#q8-dual-process-and-blackboard-architectures-what-worked)), so every experiment keeps
fast-only and slow-only arms and first measures how much System 2's latency actually costs.

After Phase 0 we will know what System 2 latency costs today. After Phase 1 we will know whether warm-started
diffusion is fast and accurate enough, and whether its denoising trajectory predicts when System 2 is needed. After
Phase 2 we will know whether it helps in the game.

## 2. Motivation from this repository

- **System 2 is slow relative to the world.** In one live run requests took roughly 1.9–5.7 s and later ones were
  slower; one took 43 s ([playtest log](../../autonomous-playtest-log.md), lines 913 and 1056). The iron-pickaxe evaluation made 69 gameplay
  requests in 6 min 10 s, about one decision every 5.4 s (line 1518). One task finished and the next gameplay context
  arrived 319 ticks (~16 s) later (line 1351).
- **Hand-written in-between judgement keeps growing.** `SlowMiningObserver` (task notices), `PhysicalEventObserver`,
  event-policy rules (`ALLOW`/`IGNORE`/`SEMANTIC_ONLY`/`TRIGGER_ONLY` matched on type and a few fields), goal
  continuation triggers, and the proposed "execution check-ins" each encode a piece of judgement that needs language
  understanding but not deliberation.
- **Known incidents need that judgement.** During the 2026-09-20 surface return the agent held a furnace while
  excavating for 317 s across 314 snapshots, 242 of them with the planner idle, although two pickaxes were carried
  ([experiment log](../../experiments/policy-continuation-2026-09-20.md)). The fix was another hand-written observer.
- **Reflexes cannot take language constraints.** `ReflexPolicy` has four fields (combat, drowning, max threat
  distance, line of sight). "Don't hit my dog" or "leave the villagers alone" can only reach the reflex through a
  System 2 round-trip.
- **The planner already treats its context as a state document.** Decision contexts carry a full state baseline
  followed by `stateChanges` deltas (`PlannerSnapshotPresentation`); tool results are replaced in place; `TOOL QUEUE`
  is a snapshot (ADR 0002). What remains conversational is mostly the reasoning trace.

## 3. Research questions and hypotheses

| ID | Question | Hypothesis (falsifiable) | Experiment |
| --- | --- | --- | --- |
| H0 | What does System 2 latency cost today? | With the world frozen while System 2 thinks, scenario outcomes improve measurably (success, completion time or damage taken), and chat reactions take ≥ 2 s median without freezing. If neither holds, System 1.5 has little to buy in these scenarios. | E0 |
| H1 | Is warm-started diffusion fast enough? | For a ≤ 4K-token document and the 256-token form, a warm DiffusionGemma refresh has p95 ≤ 250 ms on one H100 and is ≥ 3x faster than the matched AR model producing the same form, at slot accuracy within 2 points. | E1, E2 |
| H2 | Does warm start revise correctly? | Warm refreshes need ≤ 50% of cold-start steps; with the oracle previous READING they recover ≥ 90% of changed slots and keep ≥ 97% of unchanged ones. | E2 |
| H3 | Does the trajectory know when System 2 is needed? | A trajectory signal (flip count, temporal entropy, intermediate/final disagreement, late entropy) predicts "System 2 changed course next" with AUROC ≥ 0.75 and beats the rule trigger (salient event types) by ≥ 0.05. | E3 |
| H4 | Does it help in the game? | In live scenarios, System 1.5 cuts median chat-to-first-reaction time from seconds to < 1 s and System 2 requests by ≥ 30% at equal or better task success, with no safety regression (deaths, damage, reflex epochs). | E4 |
| H5 | Can System 2 teach it? | LoRA on ≤ 10k teacher-labelled documents raises slot accuracy by ≥ 10 points over zero-shot and closes ≥ 50% of the gap to the teacher. | E5 |

Each hypothesis has a kill criterion in [experiments.md](experiments.md). H1–H3 can fail independently: if diffusion
loses to a small AR model, the paradigm continues with the AR filler and diffusion becomes a research track.

## 4. Design

### 4.1 One document, three writers

The state document has fixed sections. Each has exactly one writer, and a writer's sections are clamped for everyone
else. In diffusion terms, *who owns a field* is *who may add noise to it*. The order puts stable text first so the
prompt KV cache can be reused.

| Section | Writer | Changes | Source today |
| --- | --- | --- | --- |
| SELF | config | never | persona, hard rules, output contract |
| OBJECTIVE | System 2 | per goal change | `current.objective`, travel restrictions |
| PLAN | System 2 | per decision | the last System 2 tool calls and reply |
| NOW | System 1 | every refresh | `current` vitals, position, reflex, work, inventory (`PlannerStateText`/`PlannerInputText` renderers in Java) |
| RECENT | System 1 | every event | semantic events since the last decision, chat lines kept even when crowded out |
| READING | System 1.5 | every refresh | the 256-token canvas |

Full specification: [state-document.md](state-document.md).

### 4.2 What System 1.5 may change

ADR 0002 forbids two concurrent decision owners. System 1.5 is not one. Its slots are advisory until Gate B, and
afterwards each slot maps to an existing, reversible path:

| Slot | After Gate B, drives | Why it is allowed |
| --- | --- | --- |
| `reflex.*` | `configure_reflex` | one of the four knobs the runtime admits even during a safety hold (`EmbodiedAgentRuntime`, lines 2613–2617) |
| `threats.*.stance` | per-entity reflex target filter | needs a small `ReflexPolicy` extension; advisory until then |
| `fix.action` = equip / pause_work / resume_work / look_at | `equip_item`, existing hold/resume, look-at | reversible within one System 2 decision; like planner tools, refused during a safety hold |
| `say` | a chat acknowledgement | no world effect |
| `escalate` | a coalesced `PlannerTriggerType.SYSTEM` wake with the reason | replaces hand-written wake rules |
| everything else | nothing (context for System 2 and the dashboard) | |

Actuation also requires the slot to be **settled** (argmax stable for k denoising steps and across two refreshes,
entropy below a threshold), the same staleness guard `PolicyContinuationPlanner` applies before admitting a
continuation (world, objective, travel restrictions, guidance, safety epoch, health), and a per-slot rate limit.
Safety-relevant slots use a lower bar under threat, in the spirit of urgency-gated decisions.

### 4.3 Why DiffusionGemma

Verified from primary sources (vLLM blog, transformers 5.11 source, diffusers source and docs, NeMo guide):

- **Warm start is a public API.** `decoder_input_ids` sets the starting canvas; the diffusers pipeline callback can
  replace the canvas and read logits every step.
- **Stale values can be revised.** The canvas is uniform-noise, not masked: every step re-predicts every position and
  renoises the unaccepted ones. Training corrupts a random fraction of positions and supervises all of them, so a
  mostly-correct canvas resembles a low-noise training input. Whether it converges quickly from our warm canvases is
  the core empirical question (E1/E2).
- **The prompt is cached like an AR model's.** The causal encoder writes the KV cache; vLLM prefix caching works
  unchanged. (In the transformers path, KV reuse stops once the prompt exceeds the sliding window; E1 measures prefill
  separately.)
- **Speed.** FP8 serving at batch 1: 1,008 tok/s on H100 and 1,288 on H200, ~5–6x the AR baseline; ~20 tokens per
  forward pass.
- **Fit.** The 256-token canvas holds the whole form (the fixed-width layout is budgeted at roughly 230–250 tokens;
  the harness checks the fit with the real tokenizer at runtime); image input allows frames later; Apache 2.0; LoRA
  recipes (NeMo AutoModel, Unsloth); matched AR parent for clean ablations.

Alternatives kept as arms or fallbacks: small AR models (Gemma 4 E4B) for the "paradigm without diffusion" baseline;
LLaDA2.1 (masked family with token editing) if DiffusionGemma's warm start fails; Mercury 2 (hosted) for AR-style
comparisons without a GPU.

### 4.4 The refresh loop

```
every refresh (target 4 Hz):
  doc   = render(SELF, OBJECTIVE, PLAN, NOW, RECENT)           # stable → volatile
  dirty = sections whose content changed (tick counters ignored)
  slots = READING slots whose declared inputs are dirty
  prefill(doc)                                                  # reuse the cached prefix when possible
  canvas = previous READING, tokens of `slots` replaced by uniform noise   # cold start: all noise
  for step in schedule tail (warm) or full schedule (cold):
      logits  = decoder(canvas, self_conditioning)
      accept  = lowest-entropy positions within the entropy bound (released sampler)
      canvas  = accepted candidates, renoise the rest; clamp template tokens (fixed-width mode)
      record per-slot value and entropy                         # trajectory → settledness, escalation
      stop when the argmax is stable and mean entropy < 0.005
  READING = decoded values (enums snapped, text clipped), per-slot confidence, trajectory
```

Implemented in [`s15/backends/diffusiongemma.py`](../../../research/system15/s15/backends/diffusiongemma.py); it mirrors
the diffusers `DiffusionGemmaPipeline` + `EntropyBoundScheduler` step exactly, except for the warm canvas, clamping
and trajectory recording.

### 4.5 Free JSON or clamped form

Both are implemented. **Free**: the canvas holds the canonical READING JSON; value lengths vary, so positions shift and
slots are located by re-parsing each step. **Clamped**: keys and punctuation are tokenized once and clamped, every
value has a fixed token budget padded with spaces, so a slot's positions never move. Clamping makes the structure
impossible to break and gives exact per-slot trajectories, at the cost of an unusual token layout. E2 decides.

### 4.6 Where the literature pushes back, and the response

| Evidence | Response |
| --- | --- |
| LLaDA/Dream fail as agent backbones: repeated actions, broken JSON under noise | No decision ownership, no tool calls; typed slots; enum snapping; optional clamping; System 2 remains the planner |
| Fast-only beats slow+fast coupling on some real-time games | Fast-only (rules) and slow-only arms in every comparison; E0c measures whether latency matters per scenario |
| DiffusionGemma's commit confidence carries no signal for factual recall | E3 calibrates signals per slot type against hindsight labels instead of trusting confidence |
| Calibration drifts: final-step confidence is overconfident | Use trajectory signals (flips, temporal entropy, intermediate/final disagreement), not just final entropy |
| Warm starting is not a trained regime | Measured directly (warm vs cold, E1/E2); E5 fine-tunes on our documents; E6 adds warm-start-style corruption to training |
| Self-reinforcing mistakes in retained state (seen with Airicraft's inspection findings) | Only clamped sections are facts; READING values are re-derived every refresh; staleness renoises slots whose inputs changed |

## 5. Arms

| Arm | What | Harness |
| --- | --- | --- |
| B0 | Current system, no System 1.5 | live only |
| B0-sync | B0 with the world frozen while System 2 thinks (latency upper bound) | `s15 freeze-planning` |
| B1 | Hand-written rules filling READING | `--backend rules` |
| B2 | Small AR model (e.g. Gemma 4 E4B on vLLM), full and update modes | `--backend openai --mode full\|update` |
| B3 | Matched AR model (Gemma 4 26B-A4B), full and update modes | `--backend openai` |
| T1 | DiffusionGemma, cold start (released sampler) | `--backend diffusiongemma --cold` |
| T2 | DiffusionGemma, warm start (variants: warm steps, clamping, carried self-conditioning) | `--backend diffusiongemma` |
| T3 | T2 with a LoRA distilled from teacher READINGs | `--lora PATH` |

## 6. Roadmap and gates

| Phase | Weeks | Work | Needs | Exit gate |
| --- | --- | --- | --- | --- |
| 0 | 1 | E0a recordings survey; E0b chat-reaction probes; E0c latency tax (sync vs async); extract documents; teacher labels | existing client, planner endpoint, teacher API | **Gate 0**: a scenario set where B0-sync beats B0, or chat reactions ≥ 2 s median. Otherwise pick scenarios where it does before spending GPU time. |
| 1 | 2–3 | E1 latency; E2 slot quality and revision; E3 escalation signals | 1x H100/H200 (80 GB) | **Gate A**: an arm meets H1 latency with slot score ≥ 0.9x B3's; E3 shows a signal at AUROC ≥ 0.7. If only AR arms qualify, continue with AR. If none do, stop. |
| 2 | 3–6 | E4a shadow (no Java); E4b advisory (READING shown to System 2); E4c actuation of reversible knobs | GPU during playtests; small Java changes for E4b/E4c ([§9](#9-java-integration-for-phase-2)) | **Gate B**: in shadow, actuation-worthy slots (reflex, fix, escalate) agree with System 2's next decision ≥ 80%, < 5 false "now" escalations per hour, flicker < 2%, median fast chat reaction < 1 s; then advisory must not lower task success. |
| 3 | 6–10 | E5 LoRA distillation (DAgger rounds on shadow trajectories); E6 per-slot noise, carried self-conditioning, expected-event slots | 1–8 GPUs | Report |

## 7. Budget (estimates to be replaced by E1 measurements)

- **GPU**: E1 ≈ 6 GPU-hours; E2 ≈ 6 arms x 5k documents x ~0.5 s ≈ 4 GPU-hours plus loading and sweeps, ≈ 12 GPU-hours;
  E3 reuses E2 trajectories; E4 keeps a GPU up during playtests (≈ 20–40 hours); E5 LoRA ≈ one 8-GPU node for a few
  hours (NeMo recipe) or a single GPU for longer (Unsloth). On the order of 100 H100-hours in total.
- **Teacher labels**: 5k documents x ~3.5k prompt tokens ≈ 18M input tokens plus reasoning output, once per labelling
  pass; use the existing planner endpoint or a stronger model.
- **People**: Phase 0 and the E4a shadow runs need no Java; E4b/E4c need roughly two focused Java changes.

## 8. Risks

| Risk | Early signal | Mitigation |
| --- | --- | --- |
| Warm canvases do not converge fast or drift to old values | E1 steps, E2 revision recall | renoise more (all dirty-section slots), cold fallback, E6 training with warm-start corruption |
| Zero-shot READINGs are poor | E2 slot score vs B3 | LoRA distillation (E5); keep AR filler |
| Latency on the HF path is much worse than vLLM | E1 prefill/denoise breakdown | vLLM for cold refreshes; a small ModelState extension in vLLM for warm starts |
| Signals are uncalibrated | E3 AUROC, reliability curves | per-slot thresholds; escalate on self-reported `escalate` plus rules |
| 1.5 flickers | cross-refresh flicker rate in E4a | hysteresis: two-refresh stability before any effect |
| Hardware: development so far ran on macOS (paths in the playtest log) | – | CUDA GPU in the cloud behind an SSH tunnel (`s15 serve` / `--backend remote`); the Mac runs the game, Phase 0 and shadow orchestration |
| Teacher labels encode the teacher's mistakes | disagreement between teacher and hindsight labels | report both; hindsight labels for escalation; spot-audit 100 labels by hand |

## 9. Java integration for Phase 2

Only E4b and E4c need Java. The shape, following existing seams:

- **Observation**: a new dashboard observation type `s15_reading` (READING, confidences, latency, dirty sections) so
  recordings, replay and export include System 1.5.
- **Client**: an `agent/fast/` component sends the state document, rendered from the canonical decision context
  (`PlannerStateText`/`PlannerInputText`) plus the events since it, to the System 1.5 sidecar at the configured period
  and stores the latest READING. The sidecar protocol is the one `s15 serve` already implements (`POST /fill` with the
  document, previous READING and dirty sections). No blocking on the client thread.
- **Advisory (E4b)**: the latest settled READING is appended to the decision context as a clearly labelled advisory
  paragraph, after `current`, so the stable prefix is unchanged.
- **Actuation (E4c)**: settled slots map to the table in §4.2 through the same executors the planner tools use,
  behind the `PolicyContinuationPlanner`-style staleness guard, a per-slot rate limit and a kill switch. Every
  actuation is a semantic event (`fast.actuated`) with its inverse, and System 2 sees it in RECENT.
- **Config**: `fastLayer: {enabled, endpoint, periodMs, mode: shadow|advisory|act, actuate: [...]}`, reloadable like
  other config.

## 10. Non-goals

Replacing System 2 or the tool-calling controller; pixel-level or 20 Hz control (System 1 keeps it); multiplayer
servers; training a diffusion model from scratch.
