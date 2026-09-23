#!/usr/bin/env python3
"""CMA-ES over BaselineMeleePolicy tunables via the airicraft-sim HTTP API.

Each candidate is evaluated on a fixed set of mob formations (SCENARIOS) in
parallel arenas; fitness is the mean episode score. The optimizer only reads
scores and writes policy params — the vanilla sim layer, input executor, and
scorer are fixed.

Usage:  python3 sim/optimizer/optimize_cmaes.py [--gens 15] [--pop 8] [--arenas 8]
"""

import argparse
import json
import math
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

import numpy as np

API = "http://127.0.0.1:8777"
RUN_DIR = Path(__file__).resolve().parent.parent / "run" / "sim-server"
EP_DIR = RUN_DIR / "sim" / "episodes"

# ---------------------------------------------------------------- scenario --

# Fixed mob formations (all offsets >= 5m from arena center, per spawn rule).
# Vanilla hostile mix: melee, ranged, exploder, jumper.
SCENARIOS = [
    # S1: 4 zombies, diagonal ring r~6
    [("zombie", 4.2, 4.2), ("zombie", 4.2, -4.2), ("zombie", -4.2, 4.2), ("zombie", -4.2, -4.2)],
    # S2: 2 zombies + 2 skeletons, cardinal ring r=6
    [("zombie", 6, 0), ("zombie", -6, 0), ("skeleton", 0, 6), ("skeleton", 0, -6)],
    # S3: 2 zombies + creeper + spider, r~5.8
    [("zombie", 5, 3), ("zombie", -5, 3), ("creeper", 5, -3), ("spider", -5, -3)],
    # S4: 5 zombies, pentagon r=7 (heavier crowd)
    [("zombie", 7 * math.cos(2 * math.pi * k / 5), 7 * math.sin(2 * math.pi * k / 5))
     for k in range(5)],
]

MAX_TICKS = 600
ARENA_SIZE = 20
ARENA_SPACING = 64

# Tunable parameter spec: name -> (lo, hi, default, integer?)
PARAMS = [
    ("engageDistance",      1.5, 4.0, 2.5, False),
    ("engageSlack",         0.0, 1.5, 0.4, False),
    ("sprintBeyond",        2.0, 8.0, 4.0, False),
    ("crowdRadius",         2.0, 6.0, 3.5, False),
    ("crowdThreshold",      2.0, 6.0, 3.0, True),
    ("attackRange",         2.0, 3.0, 3.0, False),
    ("minLastAttackTicks",  3.0, 20.0, 10.0, True),
    ("strafeFlipTicks",     8.0, 80.0, 30.0, True),
]
DIM = len(PARAMS)

def score_of(score: dict) -> float:
    """Scalar episode reward: kill credit, damage efficiency, clear bonus, speed."""
    kills = score.get("kills", 0)
    dealt = score.get("damageDealt", 0.0)
    taken = score.get("damageTaken", 0.0)
    ticks = score.get("ticks", MAX_TICKS)
    cleared = 60.0 if score.get("outcome") == "ALL_MOBS_CLEARED" else 0.0
    died = -50.0 if score.get("outcome") == "PLAYER_DIED" else 0.0
    return 100 * kills + dealt - 3 * taken + cleared + died - 0.05 * ticks


# ------------------------------------------------------------------- http --

def call(method: str, path: str, body=None):
    req = urllib.request.Request(API + path, method=method)
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, data=data, timeout=120) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"{method} {path} -> {e.code}: {e.read()[:400]!r}")


FLOOR_Y = -60  # flat world ground level used for arena centers


def episode_score(ep_log: str) -> dict:
    """Read the terminal 'end' record's score from an episode JSONL on disk."""
    # API returns a path relative to the server run dir.
    p = Path(ep_log)
    if not p.is_absolute():
        p = RUN_DIR / p
    last = None
    with open(p) as f:
        for line in f:
            last = line
    if last is None:
        return {}
    rec = json.loads(last)
    return rec.get("score", {}) if rec.get("type") == "end" else {}


# ----------------------------------------------------------------- cma-es --

class CMAES:
    """Minimal (mu/lambda_w) CMA-ES with CSA and rank-mu update."""

    def __init__(self, mean, sigma, pop, seed=0, scales=None):
        self.mean = np.asarray(mean, float)
        self.sigma = float(sigma)
        self.pop = pop
        self.rng = np.random.default_rng(seed)
        # Per-dimension initial spread folded into the covariance (sigma stays scalar)
        self.C = np.diag(np.asarray(scales, float) ** 2) if scales is not None else np.eye(DIM)
        self.mu = pop // 2
        w = np.log(self.mu + 0.5) - np.log(np.arange(1, self.mu + 1))
        self.w = w / w.sum()
        self.weff = 1.0 / (self.w * self.w).sum()
        self.cc = (4 + self.weff / DIM) / (DIM + 4 + 2 * self.weff / DIM)
        self.cs = (self.weff + 2) / (DIM + self.weff + 5)
        self.c1 = 2 / ((DIM + 1.3) ** 2 + self.weff)
        self.cmu = min(1 - self.c1, 2 * (self.weff - 2 + 1 / self.weff) /
                       ((DIM + 2) ** 2 + self.weff))
        self.damps = 1 + 2 * max(0, math.sqrt((self.weff - 1) / (DIM + 1)) - 1) + self.cs
        self.chiN = math.sqrt(DIM) * (1 - 1 / (4 * DIM) + 1 / (21 * DIM * DIM))
        self.ps = np.zeros(DIM)
        self.pc = np.zeros(DIM)
        self.gen = 0
        self._D, self._B = np.ones(DIM), np.eye(DIM)
        self._eig_gen = -1

    def _eigendecomp(self):
        if self._eig_gen != self.gen:
            self._D, self._B = np.linalg.eigh((self.C + self.C.T) / 2)
            self._D = np.maximum(self._D, 1e-20)
            self._eig_gen = self.gen

    def ask(self):
        self._eigendecomp()
        z = self.rng.standard_normal((self.pop, DIM))
        y = z @ (np.sqrt(self._D)[:, None] * self._B).T  # B D z
        xs = self.mean + self.sigma * y
        return xs

    def tell(self, xs, fitness):
        order = np.argsort(-fitness)
        xs = xs[order]
        self._eigendecomp()
        # (B D)^-1 without inverse: z = y^T B D^-1 ... use direct solve.
        ys = (xs[: self.mu] - self.mean) / self.sigma
        yw = (self.w @ ys)
        inv_sqrt_C = self._B @ np.diag(1 / np.sqrt(self._D)) @ self._B.T
        self.ps = (1 - self.cs) * self.ps + math.sqrt(self.cs * (2 - self.cs) * self.weff) * (inv_sqrt_C @ yw)
        hsig = (np.linalg.norm(self.ps) /
                math.sqrt(1 - (1 - self.cs) ** (2 * (self.gen + 1))) / self.chiN <
                1.4 + 2 / (DIM + 1))
        self.pc = ((1 - self.cc) * self.pc +
                   hsig * math.sqrt(self.cc * (2 - self.cc) * self.weff) * yw)
        Cm = (ys.T * self.w) @ ys  # sum w_i y_i y_i^T
        self.C = ((1 - self.c1 - self.cmu) * self.C +
                  self.c1 * (np.outer(self.pc, self.pc) + (1 - hsig) * self.cc * (2 - self.cc) * self.C) +
                  self.cmu * Cm)
        self.sigma *= math.exp((self.cs / self.damps) * (np.linalg.norm(self.ps) / self.chiN - 1))
        self.mean = self.mean + self.sigma * yw
        self.gen += 1
        self._eig_gen = -1
        return xs[0]


def encode(x):
    """Clip to bounds, cast integer params."""
    out = {}
    for (name, lo, hi, _default, is_int), v in zip(PARAMS, x):
        v = float(np.clip(v, lo, hi))
        out[name] = int(round(v)) if is_int else round(v, 3)
    return out


def defaults():
    return {name: d for name, _l, _h, d, _i in PARAMS}


# ----------------------------------------------------------------- runner --

class Sim:
    """Drives one parallel-arena batch through the control API.

    Arena centers are spaced ARENA_SPACING apart along +x; scenario offsets are
    relative to the arena center (where the player spawns).
    """

    def __init__(self, arenas):
        self.arenas = arenas
        self.center = {name: (1000 + i * ARENA_SPACING, FLOOR_Y, 0)
                       for i, name in enumerate(arenas)}

    def setup_arena(self, name):
        cx, cy, cz = self.center[name]
        call("POST", "/v1/arena", {"name": name, "world": "minecraft:overworld",
                                   "center": [cx, cy, cz], "size": ARENA_SIZE})
        call("POST", "/v1/player", {"arena": name, "name": "bot"})
        call("POST", "/v1/equip", {"arena": name, "items": [
            {"id": "minecraft:iron_sword", "slot": "main"}]})

    def reset_and_spawn(self, name, scenario):
        call("POST", "/v1/reset", {"arena": name})
        # reset() clears the inventory -> re-equip the weapon afterwards
        call("POST", "/v1/equip", {"arena": name, "items": [
            {"id": "minecraft:iron_sword", "slot": "main"}]})
        cx, cy, cz = self.center[name]
        for typ, dx, dz in scenario:
            call("POST", "/v1/spawn", {"arena": name, "type": typ,
                                       "pos": [cx + dx, cy, cz + dz],
                                       "minDist": 5.0})

    def run_batch(self, params_list, scenario):
        """One episode per arena against `scenario`; returns score dicts in order."""
        logs = []
        for name, params in zip(self.arenas, params_list):
            self.reset_and_spawn(name, scenario)
        # Let spawned mobs finish chunk-entity loading before the episode starts
        # (entities spawned into a still-loading chunk only materialize on the
        # next entity-load pass; a few ticks guarantees they are in the live set).
        try:
            call("POST", "/v1/tick", {"mode": "sprint", "ticks": 10})
        except RuntimeError:
            pass
        for name, params in zip(self.arenas, params_list):
            body = {"arena": name, "policy": "baseline-melee",
                    "maxTicks": MAX_TICKS, "obsRadius": 20.0}
            if params:
                body["params"] = params
            ep = call("POST", "/v1/episode", body)
            logs.append(ep["log"])
        # sprint is synchronous: server ticks in bursts until the count is spent
        try:
            call("POST", "/v1/tick", {"mode": "sprint", "ticks": MAX_TICKS + 50})
        except RuntimeError as e:
            print(f"  [warn] sprint call: {e}", flush=True)
        deadline = time.time() + 240
        while time.time() < deadline:
            st = call("GET", "/v1/status")
            if st.get("gate") == "RUN" and len(st.get("episodes", [])) == 0:
                break
            time.sleep(0.3)
        return [episode_score(lg) for lg in logs]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gens", type=int, default=15)
    ap.add_argument("--pop", type=int, default=8)
    ap.add_argument("--arenas", type=int, default=8)
    ap.add_argument("--reps", type=int, default=1,
                    help="episode repetitions per candidate per scenario (eval noise smoothing)")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--out", default=str(Path(__file__).parent / "results"))
    args = ap.parse_args()

    n_arenas = max(args.arenas, args.pop)
    arenas = [f"opt-{i}" for i in range(n_arenas)]
    sim = Sim(arenas)

    print("[setup] creating arenas ...", flush=True)
    for name in arenas:
        sim.setup_arena(name)
    # Warmup: force-load tickets take a few ticks to propagate to entity-loaded
    # chunks; spawning before that leaves mobs stuck in pending chunk storage.
    call("POST", "/v1/tick", {"mode": "sprint", "ticks": 40})

    # ---- baseline reference (default params on all scenarios) ----
    print("[baseline] evaluating default params ...", flush=True)
    call("POST", "/v1/tick", {"mode": "run"})
    base_scores = []
    for s_i, scen in enumerate(SCENARIOS):
        batch = [defaults()] * n_arenas
        scores = sim.run_batch(batch, scen)
        fs = [score_of(s) for s in scores]
        base_scores.append(float(np.mean(fs)))
        print(f"  scenario {s_i}: mean={np.mean(fs):.1f}  "
              f"outcomes={json.dumps([s.get('outcome') for s in scores])}", flush=True)
    base_j = float(np.mean(base_scores))
    print(f"[baseline] J={base_j:.1f}  per-scenario={np.round(base_scores,1)}", flush=True)

    # ---- CMA-ES ----
    mean = np.array([d for _n, _l, _h, d, _i in PARAMS])
    scales = np.array([(h - l) for _n, l, h, _d, _i in PARAMS]) * 0.35
    es = CMAES(mean, 1.0, args.pop, seed=args.seed, scales=scales)
    lo = np.array([l for _n, l, h, _d, _i in PARAMS])
    hi = np.array([h for _n, _l, h, _d, _i in PARAMS])

    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    hist_path = out / "history.jsonl"
    best = (-1e9, None)

    with open(hist_path, "a") as hf:
        for gen in range(args.gens):
            t0 = time.time()
            xs = es.ask()
            xs = np.clip(xs, lo, hi)
            # Evaluate: each candidate sees every scenario; pop <= arenas so each
            # scenario batch covers all candidates in parallel.
            cand_f = np.zeros(args.pop)
            for rep in range(args.reps):
                for scen in SCENARIOS:
                    params_batch = [encode(x) for x in xs]
                    scores = sim.run_batch(params_batch, scen)
                    for i, s in enumerate(scores):
                        cand_f[i] += score_of(s)
            cand_f /= len(SCENARIOS) * args.reps
            best_x = es.tell(xs, cand_f)
            if cand_f.max() > best[0]:
                best = (float(cand_f.max()), encode(best_x))
            rec = {"gen": gen, "fitness": np.round(cand_f, 2).tolist(),
                   "mean_f": round(float(cand_f.mean()), 2),
                   "best_f": round(float(cand_f.max()), 2),
                   "best_x": encode(best_x), "sigma": round(float(es.sigma), 3),
                   "sec": round(time.time() - t0, 1)}
            hf.write(json.dumps(rec) + "\n"); hf.flush()
            print(f"[gen {gen}] mean={cand_f.mean():6.1f}  best={cand_f.max():6.1f}  "
                  f"sigma={es.sigma:.3f}  ({rec['sec']}s)", flush=True)

    # ---- final head-to-head: baseline vs best on fresh eval ----
    print("[final] head-to-head baseline vs best ...", flush=True)
    final = {"baseline": [], "best": []}
    for rep in range(3):
        for scen in SCENARIOS:
            batch = [defaults()] + [best[1]] + [defaults()] * (n_arenas - 2)
            scores = sim.run_batch(batch, scen)
            final["baseline"].append(score_of(scores[0]))
            final["best"].append(score_of(scores[1]))
    report = {
        "baseline_J": float(np.mean(final["baseline"])),
        "best_J": float(np.mean(final["best"])),
        "baseline_per_scen": final["baseline"],
        "best_per_scen": final["best"],
        "best_params": best[1],
    }
    (out / "final_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
