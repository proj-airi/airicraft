# System 1.5 research

A fast layer between Airicraft's hand-written reflexes (System 1) and its LLM planner (System 2). It keeps a small typed
interpretation of the situation, the **READING**, current several times a second, from one structured **state
document**. The main bet is a warm-started diffusion language model (DiffusionGemma); the autoregressive fillers and
hand-written rules are first-class baselines, not afterthoughts.

Status (2026-09-24): proposal and tooling. No experiment has run. Nothing here changes the mod.

| Document | Contents |
| --- | --- |
| [proposal.md](proposal.md) | Motivation from this repository, hypotheses H0–H5, design and its literature basis, arms, roadmap with gates, budget, risks, Phase 2 Java plan |
| [literature.md](literature.md) | Survey by question (about 90 works, mostly 2024–2026) with what each contributes and how each claim was checked |
| [state-document.md](state-document.md) | State document sections and READING v0 slots, parsing, canvas layouts, warm start, commit rule, example |
| [experiments.md](experiments.md) | E0–E6 protocols with exact commands, metrics, pass/kill criteria and costs |
| [`research/system15`](../../../research/system15/README.md) | The harness: extraction, labels, fillers, scoring, live probe, freeze and shadow tools, with tests |

## Decisions and their evidence

| Decision | Evidence |
| --- | --- |
| Keep the old "one structured message" paradigm, but only for a fast auxiliary layer | Compact self-rewritten state trains well (MEM1, MemAgent); diffusion LMs fail as agent backbones but work as auxiliary modules (ACL 2026 reality check) |
| System 1.5 never owns decisions; it fills typed slots and later turns only reversible knobs | ADR 0002 (one decision owner); runtime already admits `configure_reflex` & co. during safety holds |
| DiffusionGemma as the primary model, its AR parent Gemma 4 26B-A4B as the matched baseline | Public warm-start API, uniform-noise revision, causal prompt KV cache, 256-token canvas, ~1,000 tok/s at batch 1 (vLLM, verified from source) |
| Warm start + renoise only dirty slots | SDEdit, Real-Time Chunking (freeze what is committed, inpaint the rest), persistent-context results for uniform diffusion |
| Escalate to System 2 from the denoising trajectory, calibrated per slot | Temporal oscillation and trajectory uncertainty work; counter-evidence that confidence can be uninformative, so E3 measures it |
| Measure the latency tax before building | Fast-only agents sometimes beat coupled ones (Latent Bridge); sync-vs-async evaluations (AgileThinker, Minecraft) |

## Start here

Phase 0 needs no GPU and no Java changes:

```sh
cd research/system15
python3 -m unittest discover -s tests                     # harness self-check
python3 -m s15 inspect --run <automatic-playtest Play or eval-output dir> --out ../../run/system15/e0/inspect.json
python3 -m s15 probe --bridge-state <worker bridge-state.json> --messages-file data/chat-bank.tsv \
  --gap 60 --watch 45 --out ../../run/system15/e0/probe.jsonl
python3 -m s15 freeze-planning --bridge-state <worker bridge-state.json> --out ../../run/system15/e0/freeze.jsonl
```

Then follow [experiments.md](experiments.md) from E0d (dataset and teacher labels) to E1 on a rented 80 GB GPU.

The name "System-1.5 Reasoning" is also used by an unrelated 2025 paper on latent shortcuts in chain-of-thought.
