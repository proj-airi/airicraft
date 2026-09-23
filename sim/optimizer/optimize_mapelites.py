#!/usr/bin/env python3
"""MAP-Elites over `ast` policy programs: diversity-preserving structural search.

Instead of squeezing all programs onto a single Pareto front, the archive is a
64-cell behavior grid — (mean kills) x (mean damage taken) — and each cell
keeps its own non-dominated elite programs. Niche specialists (e.g. an
ultra-safe low-kill policy) survive instead of being dominated out by the
generalist front. Progress = grid coverage + hypervolume of the union of all
cell elites on the shared-scenario-set objective vector.

Evaluation reuses optimize_cmaes infra; variation reuses optimize_gp's GP ops.
"""

import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from optimize_cmaes import (  # noqa: E402
    Sim, call, metrics_of, METRIC_NAMES, HV_REF, HV_IDEAL,
    _nd_fronts, _crowding, hypervolume, random_scenario, random_terrain)
import optimize_gp as gp  # noqa: E402

KILL_BINS = np.arange(9)            # kills mean 0..7 (clipped)
TAKEN_EDGES = [2, 4, 6, 9, 13, 18, 25]  # damage-taken bin edges -> 8 bins
GRID = (8, 8)


def cell_of(objs):
    kills_b = int(np.clip(int(round(objs[0])), 0, 7))
    taken = -objs[1]
    taken_b = int(np.digitize(taken, TAKEN_EDGES))
    return kills_b, taken_b


def prune_cell(members, keep=3):
    """members: list of (program, objvec); keep non-dominated, then most spread."""
    if len(members) <= keep:
        return members
    objs = np.array([o for _p, o in members])
    fronts = _nd_fronts(objs)
    picked = []
    for f in fronts:
        for i in f:
            picked.append(members[i])
            if len(picked) >= keep:
                return picked
    return picked


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gens", type=int, default=25)
    ap.add_argument("--pop", type=int, default=12, help="kids emitted per generation")
    ap.add_argument("--arenas", type=int, default=12)
    ap.add_argument("--nscen", type=int, default=12)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default=str(Path(__file__).parent / "results_me"))
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    arenas = [f"me-{i}" for i in range(max(args.arenas, args.pop))]
    sim = Sim(arenas)
    print("[setup] arenas ...", flush=True)
    for name in arenas:
        sim.setup_arena(name)
    call("POST", "/v1/tick", {"mode": "sprint", "ticks": 40})
    call("POST", "/v1/tick", {"mode": "run"})

    def sample_eval_set(r, n):
        out = []
        for _ in range(n):
            scen = random_scenario(r)
            terr = random_terrain(r, avoid_pts=[(dx, dz) for _t, dx, dz in scen])
            out.append((scen, terr))
        return out

    def eval_pop(programs, eval_set):
        """Mean 5-dim objective per program over eval_set. Programs are
        evaluated in arena-sized chunks (run_batch evaluates at most
        len(arenas) per call); every chunk sees the same eval_set."""
        objs = np.full((len(programs), len(METRIC_NAMES)), -1e9)
        for c0 in range(0, len(programs), len(arenas)):
            chunk = programs[c0:c0 + len(arenas)]
            acc = np.zeros((len(chunk), len(METRIC_NAMES)))
            n_done = 0
            for scen, terr in eval_set:
                params_batch = [{"ast": p} for p in chunk]
                try:
                    scores = sim.run_batch(params_batch, scen,
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

    grid = {}  # (ki,ti) -> list[(program, obj)]

    def insert(prog, obj):
        c = cell_of(obj)
        members = grid.get(c, [])
        members.append((prog, obj))
        grid[c] = prune_cell(members)
        return c

    def occupied():
        return list(grid.keys())

    # ---- seed archive ----
    seeds = [gp.baseline_program(), gp.template_ranged_first(),
             gp.template_lowhp_first()] + [gp.rand_program(rng)
                                           for _ in range(args.pop - 3)]
    seeds = [gp.prune(p) for p in seeds]
    objs = eval_pop(seeds, sample_eval_set(np.random.default_rng(args.seed + 1),
                                           args.nscen))
    for p, o in zip(seeds, objs):
        insert(p, o)
    print(f"[seed] coverage={len(grid)}/{GRID[0]*GRID[1]}", flush=True)

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    hist_path = out / "history.jsonl"

    with open(hist_path, "a") as hf:
        for gen in range(args.gens):
            t0 = time.time()
            kids = []
            while len(kids) < args.pop:
                u = rng.random()
                if u < 0.15 or not occupied():
                    kids.append(gp.prune(gp.rand_program(rng)))
                    continue
                occ = occupied()
                if u < 0.35 and len(occ) >= 2:
                    # crossover elites from two different cells
                    ca, cb = occ[int(rng.integers(0, len(occ)))], occ[int(rng.integers(0, len(occ)))]
                    pa = grid[ca][int(rng.integers(0, len(grid[ca])))][0]
                    pb = grid[cb][int(rng.integers(0, len(grid[cb])))][0]
                    ka, kb = gp.crossover(pa, pb, rng)
                    kids += [gp.prune(ka), gp.prune(kb)]
                else:
                    # mutate a random cell elite; cells holding fewer elites
                    # (sparser niches) get more emission — novelty pressure
                    weights = np.array([1.0 / len(grid[c]) for c in occ])
                    weights /= weights.sum()
                    ci = int(rng.choice(len(occ), p=weights))
                    cell = occ[ci]
                    parent = grid[cell][int(rng.integers(0, len(grid[cell])))][0]
                    kids.append(gp.prune(gp.mutate(parent, rng)))
            kids = kids[:args.pop]
            eval_set = sample_eval_set(
                np.random.default_rng(args.seed * 7919 + gen + 3000), args.nscen)
            kid_objs = eval_pop(kids, eval_set)
            for p, o in zip(kids, kid_objs):
                insert(p, o)

            union = [(p, o) for ms in grid.values() for p, o in ms]
            union_o = np.array([o for _p, o in union])
            front = _nd_fronts(union_o)[0]
            hv = hypervolume(union_o[front], HV_REF, HV_IDEAL)
            cov = len(grid) / (GRID[0] * GRID[1])
            rec = {"gen": gen, "coverage": round(cov, 3),
                   "cells": len(grid), "front_size": len(front),
                   "hv": round(hv, 2), "sec": round(time.time() - t0, 1)}
            hf.write(json.dumps(rec) + "\n")
            hf.flush()
            print(f"[gen {gen}] cells={len(grid)} cov={cov:.0%} "
                  f"front={len(front)} hv={hv:7.1f} ({rec['sec']}s)", flush=True)

    # ---- final: union of cell elites re-evaluated on a fresh large set ----
    print("[final] re-evaluating map elites on fresh scenarios ...", flush=True)
    (out / "grid_final.json").write_text(json.dumps({
        f"{k[0]},{k[1]}": [p for p, _o in ms] for k, ms in grid.items()}, indent=2))
    union = [(p, o) for ms in grid.values() for p, o in ms]
    finalists = [p for p, _o in union]
    eval_rng = np.random.default_rng(555)
    big_set = sample_eval_set(eval_rng, max(args.nscen * 2, 24))
    final_objs = eval_pop(finalists, big_set)
    base_objs = eval_pop([gp.baseline_program()], big_set)[0]
    global_front = _nd_fronts(final_objs)[0]

    # re-bin on final evals for an honest coverage figure
    final_cells = {}
    for (p, _o), o in zip(union, final_objs):
        c = cell_of(o)
        final_cells.setdefault(c, []).append({"program": p,
                                              "metrics": np.round(o, 3).tolist()})
    report = {
        "metric_names": METRIC_NAMES,
        "baseline_ast_metrics": np.round(base_objs, 3).tolist(),
        "coverage_final": len(final_cells) / (GRID[0] * GRID[1]),
        "cells_occupied": len(final_cells),
        "pareto_front": [
            {"program": finalists[i], "metrics": np.round(final_objs[i], 3).tolist()}
            for i in global_front
        ],
        "grid": {f"{k[0]},{k[1]}": v for k, v in sorted(final_cells.items())},
        "eval_set_size": len(big_set),
        "hypervolume_final": round(hypervolume(final_objs[global_front], HV_REF, HV_IDEAL), 2),
    }
    (out / "final_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps({k: v for k, v in report.items() if k != "grid"},
                     indent=2), flush=True)


if __name__ == "__main__":
    main()
