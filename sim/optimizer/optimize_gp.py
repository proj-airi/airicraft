#!/usr/bin/env python3
"""Genetic-programming structural search over the `ast` policy.

Individuals are JSON ASTs (rule lists: condition trees + actions) interpreted
by AstPolicy inside the sim. Selection is NSGA-II-style (non-dominated sort +
crowding) on the same 5-dim objective vector as optimize_cmaes --algo nsga2,
evaluated on a fresh shared scenario set per generation (CRN).

Reuses the eval infrastructure from optimize_cmaes.py.
"""

import argparse
import copy
import json
import math
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from optimize_cmaes import (  # noqa: E402
    Sim, call, metrics_of, METRIC_NAMES, HV_REF, HV_IDEAL,
    _nd_fronts, _crowding, hypervolume, random_scenario, random_terrain)

NUM_FEATURES = ["nearestDist", "rangedDist", "meleeDist", "farthestDist",
                "nearestHp", "lowestHp", "selfHp", "cooldown", "cdFrac",
                "mobHpSum", "usingItem", "offhandPct", "food"]
# radius-arg features (take "r"): counts plus creeperFuse (max fuse 0..1 in r)
COUNT_FEATURES = ["mobCount", "meleeCount", "rangedCount", "targetingCount",
                  "litCreeperCount", "aimingCount", "creeperFuse"]
COMPARE_OPS = ["lt", "le", "gt", "ge"]
TYPE_CHECKS = ["nearestType", "rangedType", "meleeType", "lowestHpType"]
TYPES = ["zombie", "skeleton", "creeper", "spider"]
MODES = ["approach", "flee", "orbit", "kite", "hold"]
TARGETS = ["nearest", "lowestHp", "ranged", "melee", "farthest",
           "centroid", "targeting",
           "type:zombie", "type:skeleton", "type:creeper", "type:spider"]
ATTACKS = ["ready", "always", "never"]

MAX_RULES = 6
MAX_COND_NODES = 8


def rconst(rng, feature):
    if feature in ("nearestDist", "rangedDist", "meleeDist", "farthestDist"):
        return round(float(rng.uniform(1.5, 12)), 2)
    if feature == "mobHpSum":
        return round(float(rng.uniform(10, 140)), 0)
    if feature == "usingItem":
        return 0.5
    if feature in ("nearestHp", "lowestHp", "selfHp"):
        return round(float(rng.uniform(1, 20)), 1)
    if feature in ("cooldown",):
        return int(rng.integers(2, 20))
    if feature in ("cdFrac",):
        return round(float(rng.uniform(0.2, 1.0)), 2)
    if feature in ("creeperFuse", "offhandPct"):
        return round(float(rng.uniform(0.1, 0.9)), 2)
    if feature == "food":
        return int(rng.integers(2, 20))
    return round(float(rng.uniform(1, 7)), 1)  # counts


def rand_cond(rng, depth=0):
    if depth >= 3 or rng.random() < 0.55:
        if rng.random() < 0.2:
            return {"op": "is", "f": str(rng.choice(TYPE_CHECKS)),
                    "v": str(rng.choice(TYPES))}
        f = str(rng.choice(NUM_FEATURES + COUNT_FEATURES))
        c = {"op": str(rng.choice(COMPARE_OPS)), "f": f, "v": rconst(rng, f)}
        if f in COUNT_FEATURES:
            c["r"] = round(float(rng.uniform(2.0, 8.0)), 1)
        return c
    op = str(rng.choice(["and", "or"]))
    return {"op": op, "args": [rand_cond(rng, depth + 1) for _ in range(int(rng.integers(2, 4)))]}


def rand_act(rng):
    a = {"mode": str(rng.choice(MODES)),
         "target": str(rng.choice(TARGETS)),
         "attack": str(rng.choice(ATTACKS)),
         "attackRange": round(float(rng.uniform(2.5, 3.2)), 2)}
    if rng.random() < 0.6:
        a["range"] = round(float(rng.uniform(1.5, 4.0)), 2)
        a["slack"] = round(float(rng.uniform(0.2, 0.9)), 2)
    if rng.random() < 0.4:
        a["sprintBeyond"] = round(float(rng.uniform(3.0, 8.0)), 2)
    if rng.random() < 0.35:
        a["sprint"] = bool(rng.random() < 0.5)
    if rng.random() < 0.5:
        a["readyTicks"] = int(rng.integers(5, 20))
        a["flipTicks"] = int(rng.integers(15, 60))
    if rng.random() < 0.2:
        a["zigzag"] = True
    if rng.random() < 0.15:
        a["jump"] = True
    if rng.random() < 0.2:
        if rng.random() < 0.6:
            a["use"] = "off"   # raise the shield
        else:
            a["stopUse"] = True
    return a


def baseline_program():
    """Hand-encoded equivalent of BaselineMeleePolicy."""
    return {"rules": [
        {"when": {"op": "ge", "f": "mobCount", "r": 3.5, "v": 3},
         "act": {"mode": "flee", "target": "centroid", "sprint": True,
                 "attack": "ready", "attackRange": 3.0, "readyTicks": 10}},
        {"act": {"mode": "approach", "target": "nearest", "range": 2.5,
                 "slack": 0.4, "sprintBeyond": 4.0, "attack": "ready",
                 "attackRange": 3.0, "readyTicks": 10, "flipTicks": 30}},
    ]}


def rand_program(rng):
    n = int(rng.integers(1, MAX_RULES - 1))
    rules = [{"when": rand_cond(rng), "act": rand_act(rng)} for _ in range(n)]
    rules.append({"act": rand_act(rng)})
    return {"rules": rules}


def template_ranged_first():
    """Charge ranged attackers first (they die fast, removing arrow pressure),
    then melee baseline. Non-dominated vs baseline on fresh scenarios."""
    return {"rules": [
        {"when": {"op": "gt", "f": "rangedCount", "r": 15, "v": 0},
         "act": {"mode": "approach", "target": "ranged", "range": 2.3,
                 "slack": 0.4, "sprint": True, "attack": "ready",
                 "attackRange": 3.0, "readyTicks": 8}},
        {"when": {"op": "ge", "f": "mobCount", "r": 3.0, "v": 4},
         "act": {"mode": "flee", "target": "centroid", "sprint": True,
                 "attack": "ready", "attackRange": 3.0, "readyTicks": 10}},
        {"act": {"mode": "approach", "target": "nearest", "range": 2.5,
                 "slack": 0.4, "sprintBeyond": 4.0, "attack": "ready",
                 "attackRange": 3.0, "readyTicks": 10, "flipTicks": 30}},
    ]}


def template_lowhp_first():
    """Finish low-HP targets first, then ranged-first, then baseline."""
    return {"rules": [
        {"when": {"op": "lt", "f": "lowestHp", "v": 6},
         "act": {"mode": "approach", "target": "lowestHp", "range": 2.4,
                 "slack": 0.4, "sprint": True, "attack": "ready",
                 "attackRange": 3.0, "readyTicks": 8}},
        {"when": {"op": "gt", "f": "rangedCount", "r": 14, "v": 0},
         "act": {"mode": "approach", "target": "ranged", "range": 2.3,
                 "slack": 0.4, "sprint": True, "attack": "ready",
                 "attackRange": 3.0, "readyTicks": 8}},
        {"when": {"op": "ge", "f": "mobCount", "r": 3.0, "v": 4},
         "act": {"mode": "flee", "target": "centroid", "sprint": True,
                 "attack": "ready", "attackRange": 3.0, "readyTicks": 10}},
        {"act": {"mode": "approach", "target": "nearest", "range": 2.5,
                 "slack": 0.4, "sprintBeyond": 4.0, "attack": "ready",
                 "attackRange": 3.0, "readyTicks": 10, "flipTicks": 30}},
    ]}


def cond_nodes(c):
    nodes = [c]
    if not isinstance(c, dict):
        return nodes
    if c.get("op") in ("and", "or"):
        for a in c.get("args", []):
            nodes += cond_nodes(a)
    elif c.get("op") == "not":
        nodes += cond_nodes(c.get("arg"))
    return nodes


def n_cond_nodes(c):
    return len(cond_nodes(c))


def _find_node(c, idx):
    """Return (parent_container_list_or_dict, key, node) of idx-th node."""
    nodes = []

    def walk(node, parent, key):
        nodes.append((parent, key, node))
        if isinstance(node, dict):
            if node.get("op") in ("and", "or"):
                for i, a in enumerate(node.get("args", [])):
                    walk(a, node["args"], i)
            elif node.get("op") == "not":
                walk(node["arg"], node, "arg")

    walk(c, None, None)
    return nodes[idx] if idx < len(nodes) else (None, None, None)


def mutate(prog, rng):
    prog = copy.deepcopy(prog)
    rules = prog["rules"]
    op = rng.random()
    if op < 0.30:  # perturb a random cond leaf or act constant
        ri = int(rng.integers(0, len(rules)))
        rule = rules[ri]
        pool = []
        if "when" in rule:
            for i in range(n_cond_nodes(rule["when"])):
                parent, key, node = _find_node(rule["when"], i)
                if node.get("op") in COMPARE_OPS:
                    pool.append(("cond", parent, key, node))
                elif node.get("op") in ("and", "or"):
                    pool.append(("boolop", parent, key, node))
        for k in ("range", "slack", "sprintBeyond", "attackRange",
                  "readyTicks", "flipTicks"):
            if k in rule["act"]:
                pool.append(("actnum", rule["act"], k, None))
        for k in ("mode", "target", "attack"):
            pool.append(("actcat", rule["act"], k, None))
        if pool:
            kind, parent, key, node = pool[int(rng.integers(0, len(pool)))]
            if kind == "cond":
                what = rng.random()
                if what < 0.45:
                    node["v"] = round(node["v"] * float(rng.uniform(0.7, 1.4)) + float(rng.normal(0, 0.4)), 2)
                elif what < 0.7:
                    node["op"] = str(rng.choice(COMPARE_OPS))
                elif what < 0.9 and node["f"] not in COUNT_FEATURES:
                    node["f"] = str(rng.choice(NUM_FEATURES))
                    node["v"] = rconst(rng, node["f"])
                elif "r" in node:
                    node["r"] = round(min(12, max(1.5, node["r"] + float(rng.normal(0, 1)))), 1)
            elif kind == "boolop":
                node["op"] = "or" if node["op"] == "and" else "and"
            elif kind == "actnum":
                v = parent[key]
                nv = v * float(rng.uniform(0.7, 1.4)) + float(rng.normal(0, 0.3))
                parent[key] = int(round(nv)) if isinstance(v, int) else round(nv, 2)
            else:
                table = {"mode": MODES, "target": TARGETS, "attack": ATTACKS}
                parent[key] = str(rng.choice(table[key]))
    elif op < 0.45:  # replace a cond subtree
        rix = [i for i, r in enumerate(rules) if "when" in r]
        if rix:
            ri = rix[int(rng.integers(0, len(rix)))]
            rule = rules[ri]
            i = int(rng.integers(0, n_cond_nodes(rule["when"])))
            parent, key, _node = _find_node(rule["when"], i)
            new = rand_cond(rng, depth=2)
            if parent is None:
                rule["when"] = new
            elif isinstance(parent, list):
                parent[key] = new
            else:
                parent[key] = new
    elif op < 0.60:  # add a rule (before the default)
        if len(rules) < MAX_RULES:
            rules.insert(int(rng.integers(0, max(1, len(rules) - 1))),
                         {"when": rand_cond(rng), "act": rand_act(rng)})
    elif op < 0.70:  # remove a non-default rule
        if len(rules) > 2:
            del rules[int(rng.integers(0, len(rules) - 1))]
    elif op < 0.80:  # reorder rules (keep default last)
        if len(rules) > 3:
            i = int(rng.integers(0, len(rules) - 1))
            j = int(rng.integers(0, len(rules) - 1))
            rules[i], rules[j] = rules[j], rules[i]
    else:  # wrap/unwrap a NOT
        rix = [i for i, r in enumerate(rules) if "when" in r]
        if rix:
            ri = rix[int(rng.integers(0, len(rix)))]
            rule = rules[ri]
            i = int(rng.integers(0, n_cond_nodes(rule["when"])))
            parent, key, node = _find_node(rule["when"], i)
            if node.get("op") == "not":
                repl = node["arg"]
            else:
                repl = {"op": "not", "arg": node}
            if parent is None:
                rule["when"] = repl
            else:
                parent[key] = repl
    return prog


def crossover(pa, pb, rng):
    """Swap whole rules between two parents at a random index each."""
    a, b = copy.deepcopy(pa), copy.deepcopy(pb)
    ra, rb = a["rules"], b["rules"]
    if len(ra) > 1 and len(rb) > 1:
        i = int(rng.integers(0, len(ra)))
        j = int(rng.integers(0, len(rb)))
        ra[i], rb[j] = rb[j], ra[i]
    return a, b


def cond_size(prog):
    return sum(n_cond_nodes(r["when"]) for r in prog["rules"] if "when" in r)


def prune(prog):
    """Bloat control: cap rules, drop over-deep conditions to 'true'."""
    prog["rules"] = prog["rules"][:MAX_RULES]
    for r in prog["rules"]:
        if "when" in r and n_cond_nodes(r["when"]) > MAX_COND_NODES:
            r["when"] = {"op": "true"}
    return prog


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gens", type=int, default=15)
    ap.add_argument("--pop", type=int, default=8)
    ap.add_argument("--arenas", type=int, default=8)
    ap.add_argument("--nscen", type=int, default=12)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default=str(Path(__file__).parent / "results_gp"))
    args = ap.parse_args()

    rng = np.random.default_rng(args.seed)
    arenas = [f"opt-{i}" for i in range(max(args.arenas, args.pop))]
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
        objs = np.zeros((len(programs), len(METRIC_NAMES)))
        n_done = 0
        for scen, terr in eval_set:
            params_batch = [{"ast": p} for p in programs]
            try:
                scores = sim.run_batch(params_batch, scen,
                                       terrains=[terr] * len(arenas),
                                       policy="ast")
            except RuntimeError as e:
                print(f"  [warn] batch skipped: {e}", flush=True)
                continue
            n_done += 1
            for i, s in enumerate(scores):
                objs[i] += metrics_of(s)
        return objs / max(n_done, 1)

    # seed: baseline + structure templates + their mutants + randoms
    base = baseline_program()
    seeds = [base, template_ranged_first(), template_lowhp_first()]
    pop = list(seeds)
    i = 0
    while len(pop) < args.pop * 2 // 3:
        pop.append(mutate(seeds[i % len(seeds)], rng))
        i += 1
    while len(pop) < args.pop:
        pop.append(rand_program(rng))
    pop = [prune(p) for p in pop]

    objs = eval_pop(pop, sample_eval_set(np.random.default_rng(args.seed + 1), args.nscen))

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    hist_path = out / "history.jsonl"
    archive = list(zip(pop, objs))  # (program, objs) of everything evaluated

    def sel(pop_x, pop_o):
        fr = _nd_fronts(pop_o)
        order = []
        for f in fr:
            order += f
        return order

    with open(hist_path, "a") as hf:
        for gen in range(args.gens):
            t0 = time.time()
            kids = []
            while len(kids) < args.pop:
                if rng.random() < 0.35 and len(pop) >= 2:
                    i, j = rng.integers(0, len(pop), 2)
                    ka, kb = crossover(pop[i], pop[j], rng)
                    kids += [prune(ka), prune(kb)]
                else:
                    i = int(rng.integers(0, len(pop)))
                    kids.append(prune(mutate(pop[i], rng)))
            kids = kids[:args.pop]
            eval_set = sample_eval_set(np.random.default_rng(args.seed * 7919 + gen + 2000),
                                       args.nscen)
            kid_objs = eval_pop(kids, eval_set)
            # NOTE: objectives measured on per-gen fresh sets (CRN); archive
            # stores each candidate's measured vector for reporting.
            all_x = pop + kids
            all_o = np.vstack([objs, kid_objs])
            archive += list(zip(kids, kid_objs))
            order = sel(all_x, all_o)
            keep = order[:args.pop]
            pop = [all_x[i] for i in keep]
            objs = all_o[keep]
            fronts = _nd_fronts(objs)
            front = fronts[0]
            hv = hypervolume(objs[front], HV_REF, HV_IDEAL)
            rec = {"gen": gen, "front_size": len(front),
                   "hv": round(hv, 2),
                   "front_objs": np.round(objs[front], 3).tolist(),
                   "sec": round(time.time() - t0, 1)}
            hf.write(json.dumps(rec) + "\n")
            hf.flush()
            print(f"[gen {gen}] front={len(front)}  hv={hv:7.1f}  ({rec['sec']}s)", flush=True)

    # ---- final: global non-dominated archive re-evaluated on fresh set ----
    print("[final] re-evaluating archive front on fresh scenarios ...", flush=True)
    arch_x = [p for p, _ in archive] + [base]
    arch_o = np.vstack([o for _p, o in archive] + [np.zeros(len(METRIC_NAMES))])
    global_front = _nd_fronts(arch_o)[0][:args.pop]
    finalists = [arch_x[i] for i in global_front]
    eval_rng = np.random.default_rng(555)
    big_set = sample_eval_set(eval_rng, max(args.nscen * 2, 24))
    final_objs = eval_pop(finalists, big_set)
    base_objs = eval_pop([base], big_set)[0]
    report = {
        "metric_names": METRIC_NAMES,
        "baseline_ast_metrics": np.round(base_objs, 3).tolist(),
        "pareto_front": [
            {"program": p, "metrics": np.round(o, 3).tolist()}
            for p, o in zip(finalists, final_objs)
        ],
        "eval_set_size": len(big_set),
        "hypervolume_final": round(hypervolume(final_objs, HV_REF, HV_IDEAL), 2),
    }
    (out / "final_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
