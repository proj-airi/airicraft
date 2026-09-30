"""Scoring: slot accuracy, validity, revision behaviour, latency, and ranking quality of escalation signals.

Stdlib only. Bootstrap confidence intervals resample documents (or runs, via `groups`).
"""
from __future__ import annotations

import random
from collections import Counter, defaultdict
from dataclasses import dataclass

from . import reading


def percentile(values: list[float], q: float) -> float | None:
    """Nearest-rank percentile, q in [0, 100]."""
    if not values:
        return None
    ordered = sorted(values)
    rank = max(1, min(len(ordered), int(-(-q * len(ordered) // 100))))
    return ordered[rank - 1]


def latency_summary(values: list[float]) -> dict:
    return {"n": len(values), "p50": percentile(values, 50), "p90": percentile(values, 90),
            "p95": percentile(values, 95), "p99": percentile(values, 99),
            "mean": (sum(values) / len(values)) if values else None}


def auroc(scores: list[float], labels: list[bool]) -> float | None:
    """Mann-Whitney AUROC with average ranks for ties. None when a class is missing."""
    pairs = sorted(zip(scores, labels), key=lambda item: item[0])
    positives = sum(1 for _, label in pairs if label)
    negatives = len(pairs) - positives
    if positives == 0 or negatives == 0:
        return None
    rank_sum, index = 0.0, 0
    while index < len(pairs):
        end = index
        while end + 1 < len(pairs) and pairs[end + 1][0] == pairs[index][0]:
            end += 1
        average_rank = (index + end) / 2 + 1
        rank_sum += average_rank * sum(1 for k in range(index, end + 1) if pairs[k][1])
        index = end + 1
    return (rank_sum - positives * (positives + 1) / 2) / (positives * negatives)


def precision_at_recall(scores: list[float], labels: list[bool], recall: float) -> float | None:
    positives = sum(labels)
    if positives == 0:
        return None
    hits = 0
    for rank, (_, label) in enumerate(sorted(zip(scores, labels), key=lambda item: -item[0]), start=1):
        hits += label
        if hits / positives >= recall:
            return hits / rank
    return None


def bootstrap_ci(values: list, statistic, samples: int = 1000, alpha: float = 0.05, seed: int = 0,
                 groups: list | None = None) -> tuple[float | None, float | None]:
    """Percentile bootstrap. With `groups` (e.g. run ids) whole groups are resampled together."""
    if not values:
        return None, None
    rng = random.Random(seed)
    if groups is None:
        clusters = [[v] for v in values]
    else:
        by_group: dict = defaultdict(list)
        for value, group in zip(values, groups):
            by_group[group].append(value)
        clusters = list(by_group.values())
    stats = []
    for _ in range(samples):
        sample = [v for _ in clusters for v in rng.choice(clusters)]
        result = statistic(sample)
        if result is not None:
            stats.append(result)
    if not stats:
        return None, None
    stats.sort()
    low = stats[int(alpha / 2 * (len(stats) - 1))]
    high = stats[int((1 - alpha / 2) * (len(stats) - 1))]
    return low, high


def token_f1(predicted: str, reference: str) -> float:
    a, b = predicted.lower().split(), reference.lower().split()
    if not a and not b:
        return 1.0
    if not a or not b:
        return 0.0
    common = sum((Counter(a) & Counter(b)).values())
    if common == 0:
        return 0.0
    precision, recall = common / len(a), common / len(b)
    return 2 * precision * recall / (precision + recall)


def slot_score(slot_name: str, predicted: str, reference: str) -> float:
    slot = reading.SLOT_BY_NAME[slot_name]
    if slot.kind == "text":
        return token_f1(predicted, reference)
    return 1.0 if predicted.strip().lower() == reference.strip().lower() else 0.0


def macro_f1(pairs: list[tuple[str, str]]) -> float | None:
    classes = {reference for _, reference in pairs} | {predicted for predicted, _ in pairs}
    if not pairs:
        return None
    scores = []
    for cls in classes:
        tp = sum(1 for p, r in pairs if p == cls and r == cls)
        fp = sum(1 for p, r in pairs if p == cls and r != cls)
        fn = sum(1 for p, r in pairs if p != cls and r == cls)
        if tp + fp + fn == 0:
            continue
        scores.append(0.0 if tp == 0 else 2 * tp / (2 * tp + fp + fn))
    return sum(scores) / len(scores) if scores else None


@dataclass
class ScoredRow:
    doc_id: str
    run_id: str
    slot_scores: dict[str, float]
    parse_ok: bool
    exact_rate: float
    latency_ms: float
    steps: int | None


def score_rows(predictions: list[dict], references: dict[str, dict]) -> list[ScoredRow]:
    rows = []
    for prediction in predictions:
        reference = references.get(prediction["doc_id"])
        if reference is None:
            continue
        predicted, gold = prediction["result"]["reading"], reference["reading"]
        scores = {name: slot_score(name, predicted.get(name, ""), gold.get(name, "")) for name in reading.SLOT_NAMES}
        exact = prediction["result"].get("exact") or {}
        rows.append(ScoredRow(prediction["doc_id"], prediction.get("run_id", ""), scores,
                              bool(prediction["result"].get("parse_ok")),
                              sum(1 for v in exact.values() if v) / max(1, len(reading.SLOT_NAMES)),
                              float(prediction["result"].get("latency_ms", 0.0)), prediction["result"].get("steps")))
    return rows


def revision_metrics(predictions: list[dict], references: dict[str, dict]) -> dict:
    """For consecutive docs of a run: did the filler change what the reference changed, and keep the rest?

    revision_recall = P(pred_t == ref_t | ref_t != ref_{t-1}); retention = P(pred_t == ref_t | ref unchanged).
    Computed over enum/ref slots only.
    """
    by_run: dict[str, list[dict]] = defaultdict(list)
    for prediction in predictions:
        by_run[prediction.get("run_id", "")].append(prediction)
    changed_hits = changed_total = kept_hits = kept_total = 0
    for rows in by_run.values():
        rows.sort(key=lambda row: row.get("tick", 0))
        for previous, current in zip(rows, rows[1:]):
            ref_prev, ref_cur = references.get(previous["doc_id"]), references.get(current["doc_id"])
            if ref_prev is None or ref_cur is None:
                continue
            for slot in reading.SLOTS:
                if slot.kind == "text":
                    continue
                gold_prev, gold_cur = ref_prev["reading"].get(slot.name), ref_cur["reading"].get(slot.name)
                hit = current["result"]["reading"].get(slot.name) == gold_cur
                if gold_prev != gold_cur:
                    changed_total += 1
                    changed_hits += hit
                else:
                    kept_total += 1
                    kept_hits += hit
    return {"revision_recall": changed_hits / changed_total if changed_total else None, "changed_slots": changed_total,
            "retention": kept_hits / kept_total if kept_total else None, "kept_slots": kept_total}


def summarize(rows: list[ScoredRow]) -> dict:
    per_slot = {name: sum(r.slot_scores[name] for r in rows) / len(rows) for name in reading.SLOT_NAMES} if rows else {}
    overall = [sum(r.slot_scores.values()) / len(r.slot_scores) for r in rows]
    groups = [r.run_id for r in rows]
    by_run = len(set(groups)) >= 2  # docs of one run are correlated: resample runs whenever there are several
    mean = (lambda xs: sum(xs) / len(xs) if xs else None)
    return {
        "n_docs": len(rows),
        "n_runs": len(set(groups)),
        "overall_slot_score": mean(overall),
        "overall_ci95": bootstrap_ci(overall, mean, groups=groups if by_run else None),
        "ci_unit": "run" if by_run else "doc",
        "parse_rate": mean([1.0 if r.parse_ok else 0.0 for r in rows]),
        "exact_format_rate": mean([r.exact_rate for r in rows]),
        "per_slot": per_slot,
        "latency_ms": latency_summary([r.latency_ms for r in rows]),
        "steps": latency_summary([float(r.steps) for r in rows if r.steps is not None]),
    }
