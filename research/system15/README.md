# System 1.5 experiment harness (`s15`)

Tooling for the experiments in [docs/research/system-1.5](../../docs/research/system-1.5/README.md). It turns Airicraft
flight recordings into **state documents**, fills the **READING** form with a filler (rules, an OpenAI-compatible
autoregressive model, or DiffusionGemma with warm start), scores the results, and runs two live tools against a
running client through the existing localhost bridge. No Java changes are needed for anything here.

```
recordings ──extract──▶ docs.jsonl ──label-hindsight──▶ hindsight.jsonl
                              │      └─label-teacher───▶ teacher.jsonl
                              └─fill (rules | openai | diffusiongemma)─▶ preds.jsonl ─▶ score / signals
live client ──probe (E0b: time System 2's reaction to an injected chat)
            ├─freeze-planning (E0c: freeze the world while System 2 thinks)
            └─shadow (E4a: run a filler every 250 ms, log only)
```

## Install

The core (extract, labels, rules, AR baseline, scoring, probe, shadow) is stdlib-only Python 3.11+:

```sh
cd research/system15
python3 -m s15 --help
```

DiffusionGemma needs a CUDA GPU (the 26B-A4B checkpoint is ~52 GB in bf16; an 80 GB H100/H200 fits it) and:

```sh
pip install -e 'research/system15[diffusion]'   # torch, transformers>=5.11.0, diffusers>=0.39.0, accelerate, peft
```

Write outputs under `run/system15/` (ignored by git with the rest of `run/`).

## Commands

| Command | Experiment | What it does |
| --- | --- | --- |
| `inspect --run DIR` | E0 | Files found, decision contexts, events, System 2 latency and spacing from `llm-calls` |
| `extract --run DIR... --out docs.jsonl [--granularity event]` | E2/E3 data | One doc per System 2 decision, plus one per salient event between decisions |
| `label-hindsight --run DIR... --docs ... --out ...` | E3 labels | What System 2 did next, whether it changed course, slow-mining notices, implied slot values |
| `label-teacher --docs ... --out ... --base-url URL --model M` | E2 labels | A strong model fills READING per doc (resumable; `--hindsight-window` gives it privileged future context) |
| `split --docs ... --out-dir ...` | all | Whole runs to train/dev/test by a seeded hash (never split a run) |
| `export-sft --docs ... --labels ... --out ...` | E5 | Teacher READINGs as chat SFT rows (OpenAI messages format) |
| `fill --backend B --docs ... --out ... [--previous self\|teacher\|none]` | E2 | Runs a filler in run/tick order; `self` = online (errors carry over), `teacher` = oracle previous READING |
| `bench --backend B --docs ...` | E1 | Cold vs warm refresh latency, steps, prefill vs denoise time |
| `dg-smoke --docs ...` | E1 | First GPU session: released diffusers pipeline vs this loop on a few docs |
| `score --preds ... --labels teacher.jsonl` | E2 | Per-slot accuracy (token F1 for text), parse/format rate, revision recall/retention, latency, bootstrap CI |
| `compare --a A.jsonl --b B.jsonl --labels ...` | E2 | Paired per-doc difference (B − A) with a run-clustered bootstrap CI |
| `teacher-as-preds --labels ... --out ...` | E0d | Teacher rows as predictions, to score the teacher against a human audit |
| `signals --preds ... --hindsight ... [--target ...] [--trigger social. --docs ...]` | E3 | AUROC and precision@0.9 recall of settledness signals vs the rule trigger, overall or per event regime |
| `probe --message "stop" --out ...` or `--messages-file data/chat-bank.tsv` | E0b | Injects a chat through `/v1/agent/debug/chat` (operator by default; `--sender` for a player, who must write "@agent ..."), times dispatch, response, work change, dialogue reply |
| `probe-report --probe ...` | E0b | Per intent: time to dispatch, to System 2's applied response, and to the first observable reaction |
| `freeze-planning --out ...` | E0c | Pauses ticks while a gameplay planner request is in flight (near zero-latency System 2), resumes after |
| `shadow --backend B --out ...` | E4a | Builds a doc from `/v1/agent/context` + `/v1/agent/events/recent` each period and logs the READING |
| `shadow-report --shadow ... --run DIR` | E4a | Flicker, escalation rate, chat reaction ticks (fast vs System 2), escalation vs System 2's next decision |
| `serve --backend B --port 9015` | E4 | Serves any filler over HTTP (`POST /fill`); pair with `--backend remote --remote-url ...` elsewhere |

Backends: `rules` (hand-written baseline B1), `openai` (any OpenAI-compatible server; `--mode update` puts the
previous READING in the prompt, the autoregressive analogue of warm start; `--logprobs` gives per-slot confidence),
`diffusiongemma` (`--cold`, `--warm-steps`, `--clamp-template`, `--carry-self-conditioning`, `--no-renoise`,
`--lora`), and `remote` (a filler served by `s15 serve`, e.g. on a GPU host).

The bridge listens on 127.0.0.1 only, so live tools run on the game machine. When the model is on a GPU host, run
`s15 serve --backend diffusiongemma` there and forward it (`ssh -L 9015:127.0.0.1:9015 gpu-host`), then use
`--backend remote` on the game machine.

## Worked example on the bundled fixture

```sh
cd research/system15
F=tests/fixtures/run-a; O=../../run/system15/fixture
python3 -m s15 extract --run $F --out $O/docs.jsonl
python3 -m s15 label-hindsight --run $F --docs $O/docs.jsonl --out $O/hindsight.jsonl
python3 -m s15 fill --backend rules --docs $O/docs.jsonl --out $O/rules.jsonl
python3 -m s15 score --preds $O/rules.jsonl --labels $F/teacher-gold.jsonl
python3 -m s15 signals --preds $O/rules.jsonl --hindsight $O/hindsight.jsonl --teacher $F/teacher-gold.jsonl --target teacher_escalate
```

The fixture's four decision contexts are real (captured from the iron-pickaxe run in
`src/test/resources/planner/semantic-followups.json`); the chat, slow-mining and damage events, extra System 2
calls and the hand labels in `teacher-gold.jsonl` are synthetic. Regenerate with `python3 tests/build_fixture.py`.

## Tests

```sh
cd research/system15
python3 -m unittest discover -s tests            # stdlib-only; torch-dependent tests are skipped
pip install torch 'transformers>=5.11' 'diffusers>=0.39' tokenizers
python3 -m unittest discover -s tests            # adds the denoising-loop, tiny-model and dg-smoke tests
```

What the tests establish, and what they do not:

- Recording readers, state documents, labels, rules, metrics, CLI, probe, freeze-planning and shadow mode run end
  to end on the fixture and against fake bridge / OpenAI-compatible servers.
- The denoising loop (entropy-bound acceptance, temperature schedule, warm start, renoising, clamped fixed-width
  layouts, trajectories) behaves as designed against a stub model.
- The transformers adapter runs against a **tiny random** DiffusionGemma built from the real transformers 5.11 code:
  its cached prefill plus decoder call reproduces the model's one-shot forward, KV-prefix reuse is exact, and the
  sliding-window guard triggers. `dg-smoke` runs the real diffusers 0.39 `DiffusionGemmaPipeline` next to this loop on
  the same tiny model.
- Nothing here has run against the released 26B weights or real recordings yet; `dg-smoke` and E1 are the first
  steps that do. Output quality is entirely untested.

## Notes

- Decision contexts come from the canonical conversation (`conversation_sources` in `live-recording.jsonl`, or
  `canonicalConversation` from `/v1/agent/context`). Provider request bodies are rendered prose with state deltas,
  so `llm-calls.jsonl` alone yields only prose documents (`meta.structured == false`).
- Canonical contexts carry native IDs; they are copied as-is.
- Every bridge route is served on the Minecraft client thread. The live tools therefore read only new events and new
  LLM records each poll (a far cursor gets the latest IDs without transferring records), use `/v1/agent/goals` for
  the reflex/task snapshot, and read the heavy `/v1/agent/context` (whole conversations) only at start and when a new
  System 2 call appears.
- Run IDs come from the directory layout (Play directory name; `evaluation-run__NN-scenario`; file stem for renamed
  exports); commands refuse two inputs with the same run ID.
- KV-prefix reuse in the transformers path only applies while the prompt is shorter than the model's sliding window
  (transformers refuses to crop a sliding-window cache layer that has seen more tokens); longer documents are
  re-encoded each refresh and `bench` reports that prefill cost separately.
