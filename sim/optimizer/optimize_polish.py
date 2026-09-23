#!/usr/bin/env python3
"""Parameter polish for a fixed AST program (structure frozen).

NSGA-II over the numeric constants of an `ast` program: `when` leaf v/r
thresholds and every numeric act field (range, slack, sprintBeyond,
attackRange, readyTicks, flipTicks). Categorical genes (mode, target,
attack, booleans) and the rule list itself are untouched — this is pure
scalar tuning inside an already-winning structure, multi-objective so the
5-dim tradeoff stays visible instead of being collapsed.

Eval reuses optimize_cmaes infra + per-generation shared scenario sets.
"""

import argparse
import copy
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from optimize_cmaes import (  # noqa: E402
    Sim, call, metrics_of, METRIC_NAMES, HV_REF, HV_IDEAL,
    _nd_fronts, _crowding, hypervolume, random_scenario, random_terrain)

NUM_ACT_KEYS = ("range", "slack", "sprintBeyond", "attackRange",
                "readyTicks", "flipTicks")
BOUNDS = {
    "range": (0.5, 6.0), "slack": (0.05, 1.5), "sprintBeyond": (1.0, 12.0),
    "attackRange": (1.5, 4.0), "readyTicks": (2, 40), "flipTicks": (5, 120),
    "v": (-20.0, 200.0), "r": (1.5, 15.0),
}
INT_KEYS = ("readyTicks", "flipTicks")


def _cond_leaves(c):
    if not isinstance(c, dict):
        return []
    if c.get("op") in ("and", "or"):
        out = []
        for a in c.get("args", []):
            out += _cond_leaves(a)
        return out
    if c.get("op") == "not":
        return _cond_leaves(c.get("arg"))
    return [c]


def collect_genes(prog):
    """Flat list of (container-dict, key) for every tunable numeric leaf."""
    genes = []
    for rule in prog["rules"]:
        w = rule.get("when")
        if isinstance(w, dict):
            for node in _cond_leaves(w):
                for k in ("v", "r"):
                    if k in node and isinstance(node[k], (int, float)):
                        genes.append((node, k))
        for k in NUM_ACT_KEYS:
            if k in rule["act"]:
                genes.append((rule["act"], k))
    return genes


def genome_of(prog):
    return np.array([float(node[k]) for node, k in collect_genes(prog)])


def apply_genome(prog, genome):
    p = copy.deepcopy(prog)
    for (node, k), v in zip(collect_genes(p), genome):
        lo, hi = BOUNDS[k]
        v = min(hi, max(lo, v))
        node[k] = int(round(v)) if k in INT_KEYS else round(float(v), 2)
    return p


def mutate_genome(g, rng):
    g = g.copy()
    for i in range(len(g)):
        u = rng.random()
        if u < 0.15:
            g[i] *= float(rng.uniform(0.8, 1.25))
        elif u < 0.2:
            g[i] += float(rng.normal(0, 0.15))
    return g


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ast", required=True, help="program JSON file")
    ap.add_argument("--gens", type=int, default=30)
    ap.add_argument("--pop", type=int, default=16)
    ap.add_argument("--arenas", type=int, default=12)
    ap.add_argument("--nscen", type=int, default=12)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    prog = json.loads(Path(args.ast).read_text())
    g0 = genome_of(prog)
    print(f"[setup] {len(prog['rules'])} rules, {len(g0)} tunable genes",
          flush=True)

    rng = np.random.default_rng(args.seed)
    arenas = [f"pl-{i}" for i in range(max(args.arenas, args.pop))]
    sim = Sim(arenas)
    for name in arenas:
        sim.setup_arena(name)
    call("POST", "/v1/tick", {"mode": "sprint", "ticks": 40})
    call("POST", "/v1/tick", {"mode": "run"})

    def sample_eval_set(r, n):
        out = []
        for _ in range(n):
            scen = random_scenario(r)
            terr = random_terrain(r, avoid_pts=[(dx, dz)
                                              for _t, dx, dz in scen])
            out.append((scen, terr))
        return out

    def eval_progs(progs, eval_set):
        objs = np.full((len(progs), len(METRIC_NAMES)), -1e9)
        for c0 in range(0, len(progs), len(arenas)):
            chunk = progs[c0:c0 + len(arenas)]
            acc = np.zeros((len(chunk), len(METRIC_NAMES)))
            n_done = 0
            for scen, terr in eval_set:
                params = [{"ast": p} for p in chunk]
                try:
                    scores = sim.run_batch(params, scen,
                                           terrains=[terr] * len(arenas),
                                           policy="ast")
                except RuntimeError as e:
                    print(f"  [warn] batch skipped: {e}", flush=True)
                    continue
                n_done += 1
                for i, s in enumerate(scores):
                    acc[i] += metrics_of(s)
            objs[c0:c0 + len(chunk)] = acc / max(n_done, 1)
        return objs

    def eval_pop(genomes, eval_set):
        return eval_progs([apply_genome(prog, g) for g in genomes], eval_set)

    pop = [g0] + [mutate_genome(g0, rng) for _ in range(args.pop - 1)]
    objs = eval_pop(pop, sample_eval_set(
        np.random.default_rng(args.seed + 1), args.nscen))

    out = Path(args.out or (Path(args.ast).stem + "_polish"))
    out.mkdir(parents=True, exist_ok=True)
    hist = open(out / "history.jsonl", "a")

    for gen in range(args.gens):
        t0 = time.time()
        kids = []
        while len(kids) < args.pop:
            i = int(rng.integers(0, len(pop)))
            kids.append(mutate_genome(pop[i], rng))
        eval_set = sample_eval_set(
            np.random.default_rng(args.seed * 7919 + gen + 9000), args.nscen)
        kid_objs = eval_pop(kids, eval_set)
        all_g = pop + kids
        all_o = np.vstack([objs, kid_objs])
        # NSGA-II: union fronts, then crowding within the last front kept
        keep = []
        for f in _nd_fronts(all_o):
            if len(keep) + len(f) <= args.pop:
                keep += f
            else:
                crowd = _crowding(np.array(f), all_o)
                keep += [f[i] for i in
                         np.argsort(-crowd)[:args.pop - len(keep)]]
                break
        pop = [all_g[i] for i in keep]
        objs = all_o[keep]
        front = _nd_fronts(objs)[0]
        hv = hypervolume(objs[front], HV_REF, HV_IDEAL)
        rec = {"gen": gen, "front": len(front), "hv": round(hv, 2),
               "sec": round(time.time() - t0, 1)}
        hist.write(json.dumps(rec) + "\n")
        hist.flush()
        print(f"[gen {gen}] front={len(front)} hv={hv:8.1f} "
              f"({rec['sec']}s)", flush=True)

    hist.close()

    # ---- final: population front on a fresh large set ----
    print("[final] re-evaluating polished front on fresh scenarios ...",
          flush=True)
    import optimize_gp as gp
    eval_rng = np.random.default_rng(555)
    big_set = sample_eval_set(eval_rng, max(args.nscen * 2, 24))
    final_objs = eval_pop(pop, big_set)
    orig_obj = eval_progs([prog], big_set)[0]
    base_obj = eval_progs([gp.baseline_program()], big_set)[0]
    front = _nd_fronts(final_objs)[0]
    report = {
        "metric_names": METRIC_NAMES,
        "original_metrics": np.round(orig_obj, 3).tolist(),
        "baseline_ast_metrics": np.round(base_obj, 3).tolist(),
        "eval_set_size": len(big_set),
        "pareto_front": [
            {"genome": np.round(pop[i], 2).tolist(),
             "program": apply_genome(prog, pop[i]),
             "metrics": np.round(final_objs[i], 3).tolist()}
            for i in front],
    }
    (out / "final_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
