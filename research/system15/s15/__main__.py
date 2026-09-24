"""System 1.5 experiment harness CLI. Run `python -m s15 <command> --help` from research/system15."""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from . import labels as labels_mod
from . import metrics, reading, signals
from .backends.base import FillRequest
from .recordings import load_run
from .statedoc import DEFAULT_SELF, build_docs, dirty_sections, read_docs, write_docs


def _runs(paths: list[str]):
    return [load_run(Path(p)) for p in paths]


def _read_jsonl(path: str) -> list[dict]:
    with open(path, "r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _write_json(path: str | None, value) -> None:
    text = json.dumps(value, indent=2, ensure_ascii=False, default=str)
    if path:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(text + "\n", encoding="utf-8")
    print(text)


# -- inspect / extract / label -------------------------------------------------------------------------------
def cmd_inspect(args) -> None:
    report = []
    for run in _runs(args.run):
        gameplay = [c for c in run.calls if c.request_kind in labels_mod.GAMEPLAY_KINDS]
        latencies = [float(c.latency_ms) for c in gameplay if c.latency_ms is not None and c.status == "COMPLETED"]
        gaps = [b.dispatch_tick - a.dispatch_tick for a, b in zip(gameplay, gameplay[1:])
                if a.dispatch_tick >= 0 and b.dispatch_tick >= 0]
        report.append({
            "run": run.run_id, "files": {k: str(v) for k, v in run.files.items()},
            "contexts": len(run.contexts), "structured_contexts": sum(1 for c in run.contexts if c.current is not None),
            "events": len(run.events), "calls": len(run.calls), "gameplay_calls": len(gameplay),
            "system2_latency_ms": metrics.latency_summary(latencies),
            "ticks_between_system2_calls": metrics.latency_summary([float(g) for g in gaps]),
            "event_types": _top_types(run.events),
            "docs_decision": len(build_docs(run, "decision")), "docs_event": len(build_docs(run, "event")),
        })
    _write_json(args.out, report)


def _top_types(events, limit: int = 15) -> dict:
    counts: dict[str, int] = {}
    for event in events:
        counts[event.type] = counts.get(event.type, 0) + 1
    return dict(sorted(counts.items(), key=lambda item: -item[1])[:limit])


def cmd_extract(args) -> None:
    self_text = Path(args.self_file).read_text(encoding="utf-8") if args.self_file else DEFAULT_SELF
    docs = []
    for run in _runs(args.run):
        docs.extend(build_docs(run, args.granularity, self_text))
    write_docs(docs, Path(args.out))
    print(f"wrote {len(docs)} docs to {args.out}")


def cmd_label_hindsight(args) -> None:
    docs = read_docs(Path(args.docs))
    rows = []
    for run in _runs(args.run):
        rows.extend(labels_mod.hindsight_labels(run, [d for d in docs if d.run_id == run.run_id], args.window))
    labels_mod.write_labels(rows, Path(args.out))
    print(f"wrote {len(rows)} hindsight labels to {args.out}")


def cmd_label_teacher(args) -> None:
    from .oai import OpenAICompatClient
    from .teacher import label_docs

    docs = read_docs(Path(args.docs))
    client = OpenAICompatClient(args.base_url, args.model, api_key_env=args.api_key_env, timeout_s=args.timeout)
    runs = {run.run_id: run for run in _runs(args.run or [])}
    written = 0
    for run_id in sorted({d.run_id for d in docs}):
        subset = [d for d in docs if d.run_id == run_id]
        remaining = None if args.limit is None else args.limit - written
        if remaining is not None and remaining <= 0:
            break
        written += label_docs(subset, client, Path(args.out), runs.get(run_id), args.hindsight_window,
                              args.max_tokens, remaining)
    print(f"wrote {written} teacher rows to {args.out}")


# -- fillers -------------------------------------------------------------------------------------------------
def make_filler(args):
    if args.backend == "rules":
        from .backends.rules import RulesFiller
        return RulesFiller()
    if args.backend == "openai":
        from .backends.openai_compat import OpenAIFiller
        from .oai import OpenAICompatClient
        client = OpenAICompatClient(args.base_url, args.model, api_key_env=args.api_key_env, timeout_s=args.timeout)
        return OpenAIFiller(client, mode=args.mode, json_mode=not args.no_json_mode, logprobs=args.logprobs)
    if args.backend == "diffusiongemma":
        from .backends.diffusiongemma import DenoiseConfig, DiffusionGemmaFiller, HFDiffusionGemmaAdapter
        adapter = HFDiffusionGemmaAdapter.load(args.model_id, reuse_prefix=not args.no_reuse_prefix,
                                               adapter_path=args.lora)
        config = DenoiseConfig(max_steps=args.max_steps, warm_steps=args.warm_steps, warm_start=not args.cold,
                               clamp_template=args.clamp_template, carry_self_conditioning=args.carry_self_conditioning,
                               renoise_dirty=not args.no_renoise, greedy=args.greedy, seed=args.seed,
                               record_trajectory=not args.no_trajectory, entropy_bound=args.entropy_bound)
        return DiffusionGemmaFiller(adapter, config)
    raise SystemExit(f"unknown backend {args.backend}")


def _add_backend_args(parser) -> None:
    parser.add_argument("--backend", required=True, choices=("rules", "openai", "diffusiongemma"))
    group = parser.add_argument_group("openai backend")
    group.add_argument("--base-url", default="http://127.0.0.1:8000/v1")
    group.add_argument("--model", default="google/gemma-4-26B-A4B-it")
    group.add_argument("--api-key-env", default="OPENAI_API_KEY")
    group.add_argument("--mode", choices=("full", "update"), default="full",
                       help="update = previous READING in the prompt (AR analogue of warm start)")
    group.add_argument("--no-json-mode", action="store_true")
    group.add_argument("--logprobs", action="store_true", help="per-slot confidence from token logprobs")
    group.add_argument("--timeout", type=float, default=60.0)
    group = parser.add_argument_group("diffusiongemma backend")
    group.add_argument("--model-id", default="google/diffusiongemma-26B-A4B-it")
    group.add_argument("--lora", default=None, help="PEFT adapter directory (E5)")
    group.add_argument("--cold", action="store_true", help="always start from a random canvas (T1)")
    group.add_argument("--warm-steps", type=int, default=8)
    group.add_argument("--max-steps", type=int, default=48)
    group.add_argument("--entropy-bound", type=float, default=0.1)
    group.add_argument("--clamp-template", action="store_true")
    group.add_argument("--carry-self-conditioning", action="store_true")
    group.add_argument("--no-renoise", action="store_true", help="keep dirty slots' old tokens instead of noise")
    group.add_argument("--no-reuse-prefix", action="store_true")
    group.add_argument("--no-trajectory", action="store_true", help="skip per-step slot decoding (pure timing)")
    group.add_argument("--greedy", action="store_true")
    group.add_argument("--seed", type=int, default=0)


def _ordered(docs):
    return sorted(docs, key=lambda d: (d.run_id, d.tick, d.doc_id))


def cmd_fill(args) -> None:
    filler = make_filler(args)
    docs = _ordered(read_docs(Path(args.docs)))
    if args.limit:
        docs = docs[: args.limit]
    teacher = {}
    if args.previous == "teacher":
        from .teacher import read_teacher
        teacher = read_teacher(Path(args.teacher))
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    previous_doc = previous_result = None
    with open(args.out, "w", encoding="utf-8") as handle:
        for doc in docs:
            if previous_doc is None or previous_doc.run_id != doc.run_id:
                filler.reset()
                previous_doc = previous_result = None
            dirty = dirty_sections(previous_doc, doc)
            previous = None
            if args.previous == "self" and previous_result is not None:
                previous = previous_result.reading
            elif args.previous == "teacher" and previous_doc is not None and previous_doc.doc_id in teacher:
                previous = teacher[previous_doc.doc_id]["reading"]
            result = filler.fill(FillRequest(doc, previous, reading.dirty_slots(dirty), dirty))
            handle.write(json.dumps({"doc_id": doc.doc_id, "run_id": doc.run_id, "tick": doc.tick,
                                     "filler": filler.name, "previous": args.previous,
                                     "dirty_sections": sorted(dirty), "result": result.to_json()},
                                    ensure_ascii=False) + "\n")
            previous_doc, previous_result = doc, result
    print(f"wrote {len(docs)} predictions from {filler.name} to {args.out}")


def cmd_bench(args) -> None:
    filler = make_filler(args)
    docs = _ordered(read_docs(Path(args.docs)))[: max(args.n, 2)]
    for doc in docs[: args.warmup]:
        filler.fill(FillRequest(doc))
    rows = {"cold": [], "warm": []}
    for _ in range(args.repeat):
        filler.reset()
        previous_doc = previous_result = None
        for doc in docs:
            dirty = dirty_sections(previous_doc, doc)
            previous = previous_result.reading if previous_result is not None and doc.run_id == previous_doc.run_id else None
            result = filler.fill(FillRequest(doc, previous, reading.dirty_slots(dirty), dirty))
            rows["warm" if previous is not None else "cold"].append(result)
            previous_doc, previous_result = doc, result
    summary = {"filler": filler.name, "docs": len(docs), "repeat": args.repeat}
    for kind, results in rows.items():
        summary[kind] = {
            "latency_ms": metrics.latency_summary([r.latency_ms for r in results]),
            "steps": metrics.latency_summary([float(r.steps) for r in results if r.steps is not None]),
            "prefill_ms": metrics.latency_summary([r.timings["prefill_ms"] for r in results if "prefill_ms" in r.timings]),
            "denoise_ms": metrics.latency_summary([r.timings["denoise_ms"] for r in results if "denoise_ms" in r.timings]),
            "parse_rate": (sum(r.parse_ok for r in results) / len(results)) if results else None,
        }
    _write_json(args.out, summary)


def cmd_dg_smoke(args) -> None:
    """First GPU session: the released sampler (diffusers pipeline) and this harness's loop on the same docs."""
    import torch
    from diffusers import DiffusionGemmaPipeline, EntropyBoundScheduler

    from . import prompts
    from .backends.diffusiongemma import DenoiseConfig, DiffusionGemmaFiller, HFDiffusionGemmaAdapter

    adapter = HFDiffusionGemmaAdapter.load(args.model_id)
    pipe = DiffusionGemmaPipeline(model=adapter.model, scheduler=EntropyBoundScheduler(), processor=adapter.processor)
    cold = DiffusionGemmaFiller(adapter, DenoiseConfig(warm_start=False, seed=args.seed))
    warm = DiffusionGemmaFiller(adapter, DenoiseConfig(warm_steps=args.warm_steps, seed=args.seed))
    docs = _ordered(read_docs(Path(args.docs)))[: args.n]
    rows = []
    previous_doc = previous = None
    for doc in docs:
        started = time.perf_counter()
        text = pipe(messages=prompts.filler_messages(doc), gen_length=adapter.canvas_length,
                    generator=torch.Generator(device=adapter.device).manual_seed(args.seed)).texts[0]
        reference_ms = (time.perf_counter() - started) * 1000.0
        reference = reading.parse(text)
        mine = cold.fill(FillRequest(doc))
        dirty = dirty_sections(previous_doc, doc)
        refreshed = warm.fill(FillRequest(doc, previous, reading.dirty_slots(dirty), dirty)) if previous else None
        rows.append({"doc_id": doc.doc_id, "pipeline_ms": round(reference_ms, 1), "pipeline_parse": reference.parse_ok,
                     "cold_ms": round(mine.latency_ms, 1), "cold_steps": mine.steps, "cold_parse": mine.parse_ok,
                     "agree_with_pipeline": sum(mine.reading[k] == reference.reading[k] for k in reading.SLOT_NAMES),
                     "warm_ms": round(refreshed.latency_ms, 1) if refreshed else None,
                     "warm_steps": refreshed.steps if refreshed else None,
                     "pipeline_text": text[:400]})
        previous_doc, previous = doc, mine.reading
    _write_json(args.out, rows)


# -- scoring ---------------------------------------------------------------------------------------------------
def _references(args) -> dict[str, dict]:
    from .teacher import read_teacher
    return read_teacher(Path(args.labels))


def cmd_score(args) -> None:
    predictions = _read_jsonl(args.preds)
    references = _references(args)
    rows = metrics.score_rows(predictions, references)
    summary = metrics.summarize(rows)
    summary["revision"] = metrics.revision_metrics(predictions, references)
    summary["filler"] = predictions[0]["filler"] if predictions else None
    _write_json(args.out, summary)


def cmd_signals(args) -> None:
    predictions = _read_jsonl(args.preds)
    hindsight = labels_mod.read_labels(Path(args.hindsight))
    teacher = {}
    if args.teacher:
        from .teacher import read_teacher
        teacher = read_teacher(Path(args.teacher))
    scores: dict[str, list[float]] = {}
    targets: list[bool] = []
    baseline: list[float] = []
    previous = None
    for prediction in sorted(predictions, key=lambda p: (p["run_id"], p["tick"])):
        label = hindsight.get(prediction["doc_id"])
        if label is None:
            continue
        if args.target == "teacher_escalate":
            gold = teacher.get(prediction["doc_id"])
            if gold is None:
                continue
            target = gold["reading"].get("escalate.level", "no") != "no"
        else:
            target = bool(label[args.target])
        same_run = previous is not None and previous["run_id"] == prediction["run_id"]
        dirty = reading.dirty_slots(set(prediction.get("dirty_sections") or []))
        values = signals.doc_signals(prediction["result"], previous["result"] if same_run else None, dirty)
        for name, value in values.items():
            scores.setdefault(name, []).append(value)
        targets.append(target)
        baseline.append(1.0 if label["salient_input"] else 0.0)
        previous = prediction
    report = {"target": args.target, "n": len(targets), "positives": sum(targets),
              "rule_baseline_salient_input": {"auroc": metrics.auroc(baseline, targets)}, "signals": {}}
    for name, values in scores.items():
        report["signals"][name] = {"auroc": metrics.auroc(values, targets),
                                   "precision_at_recall_0.9": metrics.precision_at_recall(values, targets, 0.9)}
    _write_json(args.out, report)


# -- live ------------------------------------------------------------------------------------------------------
def cmd_probe(args) -> None:
    from .bridge import Bridge
    from .probe import append_result, run_probe

    bridge = Bridge(args.bridge_state)
    for index in range(args.repeat):
        if index:
            time.sleep(args.gap)
        result = run_probe(bridge, args.message, args.sender, args.watch)
        append_result(result, Path(args.out))
        print(json.dumps(result["first"], indent=2))


def cmd_freeze_planning(args) -> None:
    from .bridge import Bridge
    from .freeze import run_freeze

    print(json.dumps(run_freeze(Bridge(args.bridge_state), Path(args.out), args.duration, args.poll)))


def cmd_shadow(args) -> None:
    from .bridge import Bridge
    from .shadow import run_shadow

    count = run_shadow(Bridge(args.bridge_state), make_filler(args), Path(args.out), args.duration, args.period,
                       args.run_id)
    print(f"{count} refreshes written to {args.out}")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="s15", description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("inspect", help="summarise recordings: files, contexts, events, System 2 latency")
    p.add_argument("--run", action="append", required=True)
    p.add_argument("--out")
    p.set_defaults(func=cmd_inspect)

    p = sub.add_parser("extract", help="recordings -> state documents (JSONL)")
    p.add_argument("--run", action="append", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--granularity", choices=("decision", "event"), default="event")
    p.add_argument("--self-file", help="replace the default SELF section text")
    p.set_defaults(func=cmd_extract)

    p = sub.add_parser("label-hindsight", help="labels from what System 2 and the runtime did next")
    p.add_argument("--run", action="append", required=True)
    p.add_argument("--docs", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--window", type=int, default=400, help="ticks to look ahead for the next System 2 decision")
    p.set_defaults(func=cmd_label_hindsight)

    p = sub.add_parser("label-teacher", help="teacher model fills READING for every doc (resumable)")
    p.add_argument("--docs", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--base-url", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--api-key-env", default="OPENAI_API_KEY")
    p.add_argument("--run", action="append", help="needed only with --hindsight-window")
    p.add_argument("--hindsight-window", type=int, default=0)
    p.add_argument("--max-tokens", type=int, default=2048)
    p.add_argument("--timeout", type=float, default=120.0)
    p.add_argument("--limit", type=int)
    p.set_defaults(func=cmd_label_teacher)

    p = sub.add_parser("fill", help="run a filler over docs in run/tick order")
    _add_backend_args(p)
    p.add_argument("--docs", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--previous", choices=("none", "self", "teacher"), default="self",
                   help="previous READING: none (stateless), self (online, errors carry), teacher (oracle)")
    p.add_argument("--teacher", help="teacher.jsonl, required for --previous teacher")
    p.add_argument("--limit", type=int)
    p.set_defaults(func=cmd_fill)

    p = sub.add_parser("bench", help="latency of cold and warm refreshes (E1)")
    _add_backend_args(p)
    p.add_argument("--docs", required=True)
    p.add_argument("--n", type=int, default=50)
    p.add_argument("--warmup", type=int, default=3)
    p.add_argument("--repeat", type=int, default=3)
    p.add_argument("--out")
    p.set_defaults(func=cmd_bench)

    p = sub.add_parser("dg-smoke", help="GPU sanity: released pipeline vs this loop (cold and warm) on a few docs")
    p.add_argument("--docs", required=True)
    p.add_argument("--model-id", default="google/diffusiongemma-26B-A4B-it")
    p.add_argument("--n", type=int, default=5)
    p.add_argument("--warm-steps", type=int, default=8)
    p.add_argument("--seed", type=int, default=0)
    p.add_argument("--out")
    p.set_defaults(func=cmd_dg_smoke)

    p = sub.add_parser("score", help="slot accuracy, validity, revision, latency vs teacher labels (E2)")
    p.add_argument("--preds", required=True)
    p.add_argument("--labels", required=True, help="teacher.jsonl")
    p.add_argument("--out")
    p.set_defaults(func=cmd_score)

    p = sub.add_parser("signals", help="AUROC of settledness signals for escalation (E3)")
    p.add_argument("--preds", required=True)
    p.add_argument("--hindsight", required=True)
    p.add_argument("--teacher")
    p.add_argument("--target", choices=("s2_changed_plan", "salient_input", "teacher_escalate"),
                   default="s2_changed_plan")
    p.add_argument("--out")
    p.set_defaults(func=cmd_signals)

    p = sub.add_parser("probe", help="E0: inject a chat into a live client and time the response")
    p.add_argument("--bridge-state")
    p.add_argument("--message", required=True)
    p.add_argument("--sender")
    p.add_argument("--watch", type=float, default=60.0)
    p.add_argument("--repeat", type=int, default=1)
    p.add_argument("--gap", type=float, default=30.0)
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_probe)

    p = sub.add_parser("freeze-planning", help="E0c: pause ticks while a System 2 request is in flight")
    p.add_argument("--bridge-state")
    p.add_argument("--duration", type=float, default=3600.0)
    p.add_argument("--poll", type=float, default=0.05)
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_freeze_planning)

    p = sub.add_parser("shadow", help="E4a: run a filler against a live client, log only")
    _add_backend_args(p)
    p.add_argument("--bridge-state")
    p.add_argument("--duration", type=float, default=600.0)
    p.add_argument("--period", type=float, default=0.25)
    p.add_argument("--run-id", default="live")
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_shadow)

    args = parser.parse_args(argv)
    if getattr(args, "previous", None) == "teacher" and not getattr(args, "teacher", None):
        parser.error("--previous teacher needs --teacher")
    args.func(args)


if __name__ == "__main__":
    sys.exit(main())
