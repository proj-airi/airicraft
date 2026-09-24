# System 1.5 experiment protocols

Each experiment lists its question, hypothesis, setup, exact commands, metrics, pass and kill criteria, and cost.
Hypotheses and arms are defined in the [proposal](proposal.md); the harness is
[`research/system15`](../../../research/system15/README.md).

Conventions:

- Run harness commands from `research/system15`. Outputs go under `run/system15/` (ignored by git); below, `$OUT`
  means `../../run/system15`.
- Splits are by **run**, never by document (`s15 split`). Tune everything (warm steps, thresholds, prompts) on
  `dev`; touch `test` once per gate. Confidence intervals resample runs.
- Fix the analysis before looking at `test`: this document is the analysis plan. Record deviations in the results.
- Record the commit, model IDs and harness flags with every result (`fill` rows carry `filler` and `schema`).

## E0. Baselines: what does System 2 latency cost today? (Phase 0, no GPU)

### E0a. Recording survey

**Question.** How slow and how frequent are System 2 decisions in existing recordings, and which recordings carry
structured decision contexts?

```sh
python3 -m s15 inspect --run /path/to/automatic_playtest/v1/<play>/extensions/airicraft.playtest \
  --run /path/to/eval-output/<run>/01-iron-pickaxe --out $OUT/e0/inspect.json
```

Any directory, Play or single file works (`.gz` streams and renamed dashboard exports are recognized).
**Read:** `system2_latency_ms`, `ticks_between_system2_calls`, `event_types`, and `structured_contexts`.
Evaluator outputs usually have only `llm-calls`/`events`, so their documents are prose-only; automatic playtests and
dashboard exports (`airicraft agent debug recording export`) include `live-recording` with structured contexts.

### E0b. Chat reaction probe

**Question.** How long does the current system take to react to an instruction given mid-task?
**Hypothesis (H0, part).** Median time to the first observable reaction ≥ 2 s.

Setup: a client busy with work, e.g. an automatic playtest with a gathering objective. Its bridge state is at
`run/automatic-playtest-workers/<run-id>/bridge-state.json` (evaluator workers:
`eval-output/<run>/<NN-scenario>/bridge-state.json`).

```sh
# operator messages (the local player; recorded as social.local_controller_spoke)
python3 -m s15 probe --bridge-state <state> --messages-file data/chat-bank.tsv --gap 60 --watch 45 \
  --out $OUT/e0/probe-operator.jsonl
# the same bank from a named player (prefixed "@agent ", recorded as social.player_addressed_agent)
python3 -m s15 probe --bridge-state <state> --messages-file data/chat-bank.tsv --sender Alex --gap 60 --watch 45 \
  --out $OUT/e0/probe-player.jsonl
python3 -m s15 probe-report --probe $OUT/e0/probe-operator.jsonl --out $OUT/e0/probe-operator.report.json
```

**Metrics.** Per intent: time to planner dispatch, to `planner.response_applied`, to the first observable reaction
(response applied, work change, cancellation or dialogue reply), and the count with no reaction within the watch
window. Repeat the bank at least 3 times per sender kind (≥ 60 probes).

### E0c. Latency tax (synchronous vs asynchronous System 2)

**Question.** If System 2 were instantaneous, would outcomes improve? (AgileThinker and the Minecraft
time-sensitive collaboration work show this can go either way.)
**Hypothesis (H0).** Freezing the world while System 2 thinks improves success, completion ticks or damage taken in at
least one of the chosen scenarios.

Arms: **async** (normal run) and **sync** (`freeze-planning` attached from the start). Scenarios: `pickup` (short),
`iron-pickaxe` (long), `underground`; add a night or chat-heavy scenario if these show nothing. Five runs per arm
per scenario to start; extend to ten where the difference is ambiguous.

```sh
scripts/run-evaluation-scenarios --scenario iron-pickaxe          # async arm
# sync arm: start the scenario, then as soon as its bridge-state.json exists:
python3 -m s15 freeze-planning --bridge-state eval-output/<run>/01-iron-pickaxe/bridge-state.json \
  --duration 7200 --out $OUT/e0/freeze-iron-pickaxe-<i>.jsonl
```

Tick budgets count ticks, so freezing does not eat the scenario budget. The driver always resumes on exit; if another
tool pauses the client (a bug report, a manual pause), the driver logs the stale epoch and stops pausing.

**Metrics.** Scenario outcome (evaluator report), elapsed ticks, damage (`combat.damage_taken`), deaths, System 2
requests (`s15 inspect`), total frozen time (freeze log summary). **Analysis:** sync − async per scenario with bootstrap
CIs over runs (report IQM with rliable-style intervals when n ≥ 10).

**Gate 0.** Proceed to GPU work when some scenario shows a sync advantage, or E0b shows ≥ 2 s median reaction.
Otherwise find scenarios where latency binds (night combat, chat corrections during work) before spending GPU time.

### E0d. Dataset and teacher labels

1. **Collect** ≥ 15 automatic-playtest runs of 30–60 min with varied objectives (iron pickaxe, shelter before night,
   farm, cave return), with scripted operator chat every ~2 min so chat intents occur:

   ```sh
   scripts/automatic-playtest --world 'run/saves/<world>' --recorder-jar <profile.jar> \
     --objective '<objective>' --max-seconds 2400
   python3 -m s15 probe --bridge-state run/automatic-playtest-workers/<run-id>/bridge-state.json \
     --messages-file data/chat-bank.tsv --gap 120 --watch 30 --out $OUT/data/probe-<run-id>.jsonl
   ```

2. **Extract, split, label:**

   ```sh
   python3 -m s15 extract --granularity event --run <play-1> --run <play-2> ... --out $OUT/data/docs.jsonl
   python3 -m s15 split --docs $OUT/data/docs.jsonl --out-dir $OUT/data/split --dev 0.2 --test 0.2
   python3 -m s15 label-hindsight --run <play-1> ... --docs $OUT/data/docs.jsonl --out $OUT/data/hindsight.jsonl
   for part in dev test train; do
     python3 -m s15 label-teacher --docs $OUT/data/split/docs-$part.jsonl --out $OUT/data/teacher-$part.jsonl \
       --base-url "$TEACHER_BASE_URL" --model "$TEACHER_MODEL" --api-key-env TEACHER_API_KEY
   done
   ```

   `label-teacher` is resumable. Use the strongest model available (the existing planner endpoint is acceptable);
   `--hindsight-window 400` gives the teacher privileged knowledge of what happened next, which improves ambiguous
   labels but must not be used for escalation targets.

3. **Audit** 100 random test documents: copy their teacher rows to `human-test.jsonl`, correct each `reading` by hand
   against the rubric (`s15/prompts.py`), then:

   ```sh
   python3 -m s15 teacher-as-preds --labels $OUT/data/teacher-test.jsonl --out $OUT/data/teacher-preds.jsonl
   python3 -m s15 score --preds $OUT/data/teacher-preds.jsonl --labels $OUT/data/human-test.jsonl
   ```

   Accept the teacher when overall ≥ 0.90 and `chat.intent`, `anomaly.kind`, `escalate.level` ≥ 0.85; otherwise refine
   the rubric or the teacher and relabel.

**Cost.** No GPU. Teacher: ~3.5k prompt tokens per document; ~18M input tokens for 5k documents.

## E1. Latency (Phase 1, one 80 GB GPU)

**Question.** How fast is a warm refresh compared with cold diffusion and autoregressive fillers?
**Hypothesis (H1, latency).** Best warm configuration: p95 ≤ 250 ms per refresh, ≥ 3x faster than B3 in update mode.

Setup on the GPU host:

```sh
pip install -e 'research/system15[diffusion]'   # transformers >= 5.11.0, diffusers >= 0.39.0
python3 -m s15 dg-smoke --docs $OUT/data/split/docs-dev.jsonl --n 5 --out $OUT/e1/smoke.json
```

`dg-smoke` runs the released diffusers pipeline and this harness's loop on the same documents: both should parse, agree
on most slots, and have similar cold latency. Stop and debug here if not.

```sh
D=$OUT/data/split/docs-dev.jsonl
python3 -m s15 bench --backend diffusiongemma --cold --docs $D --n 200 --repeat 3 --out $OUT/e1/T1-hf.json
for W in 1 2 4 8; do
  python3 -m s15 bench --backend diffusiongemma --warm-steps $W --docs $D --n 200 --repeat 3 --out $OUT/e1/T2-w$W.json
done
python3 -m s15 bench --backend diffusiongemma --warm-steps 4 --clamp-template --docs $D --out $OUT/e1/T2-w4-clamp.json
python3 -m s15 bench --backend diffusiongemma --warm-steps 4 --no-trajectory --docs $D --out $OUT/e1/T2-w4-notraj.json

# optimized serving paths (see recipes.vllm.ai for current flags)
vllm serve RedHatAI/diffusiongemma-26B-A4B-it-FP8-dynamic --port 8001      # T1 on vLLM (cold only)
vllm serve google/gemma-4-26B-A4B-it --port 8002                          # B3
vllm serve google/gemma-4-E4B-it --port 8003                              # B2 (check the exact model id)
python3 -m s15 bench --backend openai --base-url http://127.0.0.1:8001/v1 \
  --model RedHatAI/diffusiongemma-26B-A4B-it-FP8-dynamic --docs $D --out $OUT/e1/T1-vllm.json
for M in full update; do
  python3 -m s15 bench --backend openai --base-url http://127.0.0.1:8002/v1 --model google/gemma-4-26B-A4B-it \
    --mode $M --docs $D --out $OUT/e1/B3-$M.json
  python3 -m s15 bench --backend openai --base-url http://127.0.0.1:8003/v1 --model google/gemma-4-E4B-it \
    --mode $M --docs $D --out $OUT/e1/B2-$M.json
done
```

If a server rejects JSON mode, add `--no-json-mode`. Run the bf16 HF path and the FP8 vLLM path on the same GPU,
one at a time.

**Metrics.** p50/p95 refresh latency, prefill vs denoise time, steps, parse rate; warm and cold rows separately.
**Pass.** H1 latency clause. **Kill.** Warm p95 > 1 s even at 2 steps: the HF path is not viable for live use. Then
either implement a warm-start `ModelState` in vLLM (engineering; its DiffusionGemma state already owns the canvas) or
continue with the best AR arm.

## E2. Slot quality and revision (Phase 1)

**Question.** Is warm-started diffusion as good as autoregressive fillers, and does warm start revise correctly?
**Hypotheses.** H1 (accuracy within 2 points of B3) and H2 (oracle revision recall ≥ 0.90, retention ≥ 0.97;
warm steps ≤ 50% of cold).

```sh
T=$OUT/data/split/docs-test.jsonl; L=$OUT/data/teacher-test.jsonl
python3 -m s15 fill --backend rules --docs $T --out $OUT/e2/B1.jsonl
for M in full update; do
  python3 -m s15 fill --backend openai --base-url http://127.0.0.1:8003/v1 --model google/gemma-4-E4B-it --mode $M \
    --docs $T --out $OUT/e2/B2-$M.jsonl
  python3 -m s15 fill --backend openai --base-url http://127.0.0.1:8002/v1 --model google/gemma-4-26B-A4B-it --mode $M \
    --docs $T --out $OUT/e2/B3-$M.jsonl
done
python3 -m s15 fill --backend diffusiongemma --cold --docs $T --out $OUT/e2/T1.jsonl
python3 -m s15 fill --backend diffusiongemma --warm-steps 4 --docs $T --out $OUT/e2/T2.jsonl
python3 -m s15 fill --backend diffusiongemma --warm-steps 4 --clamp-template --docs $T --out $OUT/e2/T2-clamp.jsonl
python3 -m s15 fill --backend diffusiongemma --warm-steps 4 --carry-self-conditioning --docs $T --out $OUT/e2/T2-sc.jsonl
# oracle previous READING isolates the update step from error accumulation
python3 -m s15 fill --backend diffusiongemma --warm-steps 4 --previous teacher --teacher $L --docs $T --out $OUT/e2/T2-oracle.jsonl
python3 -m s15 fill --backend openai --base-url http://127.0.0.1:8002/v1 --model google/gemma-4-26B-A4B-it --mode update \
  --previous teacher --teacher $L --docs $T --out $OUT/e2/B3-update-oracle.jsonl

for f in $OUT/e2/*.jsonl; do python3 -m s15 score --preds $f --labels $L --out ${f%.jsonl}.score.json; done
python3 -m s15 compare --a $OUT/e2/B3-update.jsonl --b $OUT/e2/T2.jsonl --labels $L --out $OUT/e2/T2-vs-B3.json
python3 -m s15 compare --a $OUT/e2/T1.jsonl --b $OUT/e2/T2.jsonl --labels $L --out $OUT/e2/T2-vs-T1.json
```

Pick `--warm-steps` and the layout on `dev` first (same commands with the dev split); report `test` once.

**Metrics.** Overall slot score with run-clustered CI; per slot; parse and exact-format rates; revision recall and
retention (online and oracle); steps; latency. **Error analysis:** the 50 test documents where T2 is furthest below
B3, grouped by slot and document kind (chat, anomaly, combat, routine).
**Kill.** T2 overall < 0.8x B3 zero-shot: run E5 before any live diffusion arm, and use the best AR arm for E4a.

## E3. Knowing when System 2 is needed (Phase 1)

**Question.** Does the denoising trajectory predict that System 2 will change course?
**Hypothesis (H3).** Some trajectory signal reaches AUROC ≥ 0.75 on `s2_changed_plan` and beats the rule trigger
(`salient_input`) by ≥ 0.05.

```sh
H=$OUT/data/hindsight.jsonl
python3 -m s15 signals --preds $OUT/e2/T2.jsonl --hindsight $H --target s2_changed_plan --out $OUT/e3/T2-s2.json
python3 -m s15 signals --preds $OUT/e2/T2.jsonl --hindsight $H --teacher $L --target teacher_escalate --out $OUT/e3/T2-teacher.json
for R in social. combat. task.notice; do
  python3 -m s15 signals --preds $OUT/e2/T2.jsonl --hindsight $H --trigger $R --docs $T --out $OUT/e3/T2-$R.json
done
python3 -m s15 fill --backend openai --base-url http://127.0.0.1:8002/v1 --model google/gemma-4-26B-A4B-it --logprobs \
  --docs $T --out $OUT/e2/B3-logprobs.jsonl
python3 -m s15 signals --preds $OUT/e2/B3-logprobs.jsonl --hindsight $H --out $OUT/e3/B3-s2.json
```

Signals: `flip_count`, `temporal_entropy`, `final_disagreement`, `late_entropy`, `low_confidence`, `flicker`,
`self_report`; baseline `rule_baseline_salient_input`. Report AUROC and precision at 0.9 recall, overall and per regime.
Expect regime differences: DiffusionGemma's commit confidence was uninformative for factual recall in one analysis.
**Kill.** No signal above 0.65 AUROC: escalate on `self_report` plus rules, and drop signal-based escalation from E4.

**Gate A** (after E1–E3): continue to Phase 2 with the best arm that meets H1 on latency and scores ≥ 0.9x B3;
carry forward the E3 signal and thresholds chosen on dev.

## E4. Live (Phase 2)

### E4a. Shadow mode (no Java)

System 1.5 runs beside the unchanged agent and only logs. The bridge accepts localhost connections only, so live
tools run on the game machine; the model runs on the GPU host behind a tunnel.

```sh
# GPU host
python3 -m s15 serve --backend diffusiongemma --warm-steps 4 --port 9015
# game machine
ssh -N -L 9015:127.0.0.1:9015 <gpu-host> &
scripts/automatic-playtest --world '<world>' --recorder-jar <profile.jar> --objective '<objective>' --max-seconds 2400
S=run/automatic-playtest-workers/<run-id>/bridge-state.json
python3 -m s15 shadow --backend remote --remote-url http://127.0.0.1:9015 --bridge-state $S \
  --duration 2400 --period 0.25 --run-id <run-id> --out $OUT/e4/shadow-<run-id>.jsonl &
python3 -m s15 probe --bridge-state $S --messages-file data/chat-bank.tsv --gap 120 --watch 30 \
  --out $OUT/e4/probe-<run-id>.jsonl
# afterwards, with the published Play
python3 -m s15 shadow-report --shadow $OUT/e4/shadow-<run-id>.jsonl \
  --run automatic_playtest/v1/<play>/extensions/airicraft.playtest --out $OUT/e4/shadow-<run-id>.report.json
```

Scenarios (≥ 5 runs each):

| Scenario | World and objective | Scripted input | Key measure |
| --- | --- | --- | --- |
| Chat during work | `scenarios/iron-pickaxe` world; "obtain an iron pickaxe" | chat bank every 2 min | fast vs System 2 reaction ticks per intent |
| Wrong tool | the 2026-09-20 underground checkpoint (furnace selected, pickaxes in inventory); "return to the surface" | none | ticks until 1.5 proposes `equip` vs until the held item changes |
| Night threat | a survival world saved at dusk; "collect 16 logs near spawn" | none | escalations vs reflex epochs and System 2 wakes; damage |
| Protect | a world with a tamed wolf and hostiles nearby (needs authoring) | "don't hit my dog" | threat stances; any hit on the wolf |

**Metrics** (`shadow-report`): refresh latency and interval; flicker rate; "now" escalations per hour; fast vs
System 2 reaction ticks for each chat; escalation agreement with System 2's next decision (confusion matrix).
**Gate B.** Actuation-worthy slots agree with System 2's next decision ≥ 80%; < 5 false "now" escalations per hour;
flicker < 2% of refresh pairs; fast reaction to chat < 1 s median.

### E4b. Advisory and E4c. Actuation (Java changes in proposal §9)

Arms: B0, advisory (READING in System 2's context), act (settled slots drive reversible knobs). Same scenarios, ≥ 10
runs per arm per scenario, alternating arms to spread provider-latency drift.

**Metrics.** Task success; completion ticks; chat-to-first-observable-reaction time; System 2 requests per minute and
prompt tokens; damage and deaths; regret (1.5 actuations reverted by System 2 within 10 s); flicker.
**Pass (H4).** Median reaction < 1 s; ≥ 30% fewer System 2 requests; success not lower (CI); no safety regression.
**Kill.** Any safety regression in the act arm: disable actuation of that slot and report.

## E5. Distillation from System 2 (Phase 3)

**Hypothesis (H5).** LoRA on ≤ 10k teacher READINGs: ≥ 10-point gain over zero-shot T2 and ≥ 50% of the gap to the
teacher closed.

```sh
python3 -m s15 export-sft --docs $OUT/data/split/docs-train.jsonl --labels $OUT/data/teacher-train.jsonl \
  --out $OUT/e5/sft-train.jsonl
python3 -m s15 export-sft --docs $OUT/data/split/docs-dev.jsonl --labels $OUT/data/teacher-dev.jsonl \
  --out $OUT/e5/sft-dev.jsonl
```

Train with NeMo AutoModel's DiffusionGemma LoRA recipe: copy `examples/dllm_sft/diffusion_gemma_lora.yaml`, point
its dataset at `sft-train.jsonl` (the same OpenAI chat-messages format as its GSM8K example), keep its custom chat
template, and run `torchrun --standalone --nproc-per-node=8 examples/dllm_sft/finetune.py -c <yaml>` (8 GPUs, EP=8).
Unsloth supports DiffusionGemma fine-tuning on one GPU as an alternative. Then:

```sh
python3 -m s15 fill --backend diffusiongemma --lora <adapter-dir> --warm-steps 4 --docs $T --out $OUT/e5/T3.jsonl
python3 -m s15 score --preds $OUT/e5/T3.jsonl --labels $L --out $OUT/e5/T3.score.json
python3 -m s15 compare --a $OUT/e2/T2.jsonl --b $OUT/e5/T3.jsonl --labels $L --out $OUT/e5/T3-vs-T2.json
```

**DAgger round.** Label the documents from E4a shadow runs with the teacher, add them to training, retrain, re-run E2
and E4a. Stop when a round gains < 2 points.

## E6. Research extensions (after Gate B)

| Idea | Change | First test |
| --- | --- | --- |
| Per-slot noise by staleness (Diffusion Forcing) | training corrupts each slot with its own noise level, higher for slots whose inputs changed or aged | E2 revision recall at equal steps |
| Warm-start corruption in training | SFT inputs start from the previous READING with dirty-slot spans renoised (GameNGen-style context noise against drift) | E2 online (`--previous self`) vs oracle gap |
| Carried self-conditioning | reuse the last refresh's logits as step-0 self-conditioning (`--carry-self-conditioning`) | steps to settle |
| Expected-event slots | READING predicts the next salient events; prediction error drives renoising and escalation (surprise) | E3 AUROC of surprise |
| Idle "sleep" refreshes | extra refreshes and teacher relabelling while the world is quiet (sleep-time compute) | E4 reaction latency |
| Frames in the prompt | add the latest FPV frame (DiffusionGemma accepts images) | E2 on threat/scene slots |

## Result template

For each experiment record: date, commit, hardware, model IDs and revisions, harness command lines, data split
(`split.json`), the summary JSON files, the pass/kill decision, and deviations from this plan.
