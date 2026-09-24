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

MOB_POOL = [("zombie", 0.42), ("skeleton", 0.20), ("creeper", 0.16), ("spider", 0.22)]


def random_scenario(rng, min_r=5.5, max_r=9.0, min_n=3, max_n=7):
    """Sample one spawn layout: mob type mix x formation x radius.

    Formations: ring (surround), arc (one-sided), cluster (single group),
    pincer (two opposite groups). Every offset is clamped to radius >= 5.5
    so the spawn-distance rule never trips.
    """
    n = rng.integers(min_n, max_n + 1)
    types = [t for t, _w in MOB_POOL]
    weights = np.array([w for _t, w in MOB_POOL])
    weights = weights / weights.sum()
    pick = rng.choice(types, size=n, p=weights)
    pattern = rng.choice(["ring", "arc", "cluster", "pincer"])
    pts = []
    if pattern == "ring":
        for k in range(n):
            a = 2 * math.pi * k / n + rng.uniform(-0.3, 0.3)
            r = rng.uniform(min_r, max_r)
            pts.append((r * math.cos(a), r * math.sin(a)))
    elif pattern == "arc":
        center_a = rng.uniform(0, 2 * math.pi)
        span = rng.uniform(math.pi / 2, math.pi)
        for k in range(n):
            a = center_a + span * (k / max(n - 1, 1) - 0.5) + rng.uniform(-0.15, 0.15)
            r = rng.uniform(min_r, max_r)
            pts.append((r * math.cos(a), r * math.sin(a)))
    elif pattern == "cluster":
        center_a = rng.uniform(0, 2 * math.pi)
        cr = rng.uniform(min_r + 1, max_r)
        cx, cy = cr * math.cos(center_a), cr * math.sin(center_a)
        for k in range(n):
            dx = cx + rng.normal(0, 1.2)
            dy = cy + rng.normal(0, 1.2)
            r = math.hypot(dx, dy)
            if r < min_r:
                dx, dy = dx / r * min_r, dy / r * min_r
            pts.append((dx, dy))
    else:  # pincer
        a0 = rng.uniform(0, 2 * math.pi)
        for k in range(n):
            a = a0 + (0 if k % 2 == 0 else math.pi) + rng.uniform(-0.4, 0.4)
            r = rng.uniform(min_r, max_r)
            pts.append((r * math.cos(a), r * math.sin(a)))
    # micro-jitter baked in so terrain/spawn code sees the final positions;
    # re-clamp radius afterwards — jitter can push a boundary point under 5m
    out = []
    for dx, dy in pts:
        dx += rng.uniform(-0.8, 0.8)
        dy += rng.uniform(-0.8, 0.8)
        r = math.hypot(dx, dy)
        if r < min_r:
            dx, dy = dx / r * min_r, dy / r * min_r
        out.append((dx, dy))
    # separate spawn points: two mobs spawning into overlapping collision
    # boxes is rejected server-side ("no empty space"); push apart to >=1.6m
    for i in range(len(out)):
        for _try in range(12):
            ok = all(i == j or math.hypot(out[i][0] - out[j][0], out[i][1] - out[j][1]) >= 1.6
                     for j in range(len(out)))
            if ok:
                break
            dx, dy = out[i]
            dx += rng.uniform(-1.2, 1.2)
            dy += rng.uniform(-1.2, 1.2)
            r = math.hypot(dx, dy)
            if r < min_r:
                dx, dy = dx / r * min_r, dy / r * min_r
            out[i] = (dx, dy)
    return [(t, float(dx), float(dy)) for t, (dx, dy) in zip(pick, out)]


def scenario_desc(scenario):
    counts = {}
    for t, _x, _y in scenario:
        counts[t] = counts.get(t, 0) + 1
    return "+".join(f"{v}{k[0]}" for k, v in sorted(counts.items()))


def random_terrain(rng, avoid_pts=(), floor_dy=-1):
    """Sample terrain features as (block_id, [(dx,dy,dz),...]) groups, offsets
    relative to arena center. dy is relative to player-spawn y (floor = -1).

    Kinds: pillars, wall segments, mounds, 1-deep water pools, cobweb patches.
    Features are kept >=2m from the player spawn and >=1.5m from every point in
    avoid_pts (mob spawn offsets) so they never block spawning.
    """
    groups = []
    n_feat = rng.integers(0, 3)  # 0-2 feature groups

    def far(px, pz):
        # avoid_pts are the final (jittered) spawn offsets — 1.8 covers the
        # half-block + a safety margin around the spawn's collision box
        if math.hypot(px, pz) < 2.0:
            return False
        return all(math.hypot(px - ax, pz - az) >= 1.8 for ax, az in avoid_pts)

    for _ in range(n_feat):
        kind = rng.choice(["pillar", "wall", "mound", "water_pool", "cobweb"])
        a = rng.uniform(0, 2 * math.pi)
        r = rng.uniform(3.0, 8.0)
        cx, cz = r * math.cos(a), r * math.sin(a)
        pts = []
        if kind == "pillar":
            h = int(rng.integers(2, 4))
            if far(cx, cz):
                pts = [(round(cx), dy, round(cz)) for dy in range(0, h)]
        elif kind == "wall":
            length = int(rng.integers(3, 6))
            horiz = rng.random() < 0.5
            for k in range(length):
                px, pz = (cx + k, cz) if horiz else (cx, cz + k)
                if far(px, pz):
                    pts += [(round(px), dy, round(pz)) for dy in range(0, 2)]
            groups.append(("minecraft:stone", pts)) if pts else None
            continue
        elif kind == "mound":
            for ox in (-0.5, 0.5):
                for oz in (-0.5, 0.5):
                    px, pz = cx + ox, cz + oz
                    if far(px, pz):
                        pts.append((round(px), 0, round(pz)))
        elif kind == "water_pool":
            w = int(rng.integers(2, 4))
            for ox in range(w):
                for oz in range(w):
                    px, pz = cx + ox - w / 2, cz + oz - w / 2
                    if far(px, pz):
                        pts.append((round(px), floor_dy, round(pz)))
        else:  # cobweb
            w = int(rng.integers(2, 4))
            for ox in range(w):
                for oz in range(w):
                    px, pz = cx + ox - w / 2, cz + oz - w / 2
                    if far(px, pz):
                        pts.append((round(px), 0, round(pz)))
        if not pts:
            continue
        block = ("minecraft:water" if kind == "water_pool"
                 else "minecraft:cobweb" if kind == "cobweb"
                 else "minecraft:stone")
        groups.append((block, pts))
    return groups

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
    """Scalar episode reward (legacy CMA-ES mode; the NSGA-II path uses
    `metrics_of` instead — no scalarization)."""
    kills = score.get("kills", 0)
    dealt = score.get("damageDealt", 0.0)
    taken = score.get("damageTaken", 0.0)
    ticks = score.get("ticks", MAX_TICKS)
    cleared = 60.0 if score.get("outcome") == "ALL_MOBS_CLEARED" else 0.0
    died = -500.0 if score.get("outcome") == "PLAYER_DIED" else 0.0
    return 100 * kills + dealt - 3 * taken + cleared + died - 0.2 * ticks


# Raw objective vector, all entries maximized. Per-candidate fitness in
# NSGA-II mode is the per-dimension mean of this vector over the eval set.
METRIC_NAMES = ["kills", "neg_taken", "clear", "survived", "neg_ticks"]


def metrics_of(score: dict):
    return np.array([
        score.get("kills", 0),                     # credited kills
        -score.get("damageTaken", 0.0),            # less damage taken is better
        1.0 if score.get("outcome") == "ALL_MOBS_CLEARED" else 0.0,
        1.0 if score.get("outcome") != "PLAYER_DIED" else 0.0,
        -score.get("ticks", MAX_TICKS),            # faster clears are better
    ])


# ------------------------------------------------------------- nsga-ii ---

def _dominates(a, b):
    return np.all(a >= b) and np.any(a > b)


def _nd_fronts(objs):
    """Non-dominated sorting: list of index lists, best front first."""
    n = len(objs)
    dominated_by = [set() for _ in range(n)]
    dom_count = np.zeros(n, int)
    for i in range(n):
        for j in range(n):
            if i == j:
                continue
            if _dominates(objs[i], objs[j]):
                dominated_by[i].add(j)
            elif _dominates(objs[j], objs[i]):
                dom_count[i] += 1
    fronts = []
    cur = [i for i in range(n) if dom_count[i] == 0]
    while cur:
        fronts.append(cur)
        nxt = []
        for i in cur:
            for j in dominated_by[i]:
                dom_count[j] -= 1
                if dom_count[j] == 0:
                    nxt.append(j)
        cur = nxt
    return fronts


def _crowding(idxs, objs):
    """Crowding distance within one front."""
    d = np.zeros(len(idxs))
    for m in range(objs.shape[1]):
        order = np.argsort(objs[idxs, m])
        d[order[0]] = d[order[-1]] = np.inf
        span = objs[idxs[order[-1]], m] - objs[idxs[order[0]], m]
        if span <= 0:
            continue
        for k in range(1, len(idxs) - 1):
            d[order[k]] += (objs[idxs[order[k + 1]], m] - objs[idxs[order[k - 1]], m]) / span
    return d


def hypervolume(front_objs, ref, ideal, n_samples=20000, seed=7):
    """Monte-Carlo dominated hypervolume of a Pareto front."""
    rng = np.random.default_rng(seed)
    pts = rng.uniform(ref, ideal, (n_samples, len(ref)))
    covered = np.zeros(n_samples, bool)
    for o in front_objs:
        covered |= np.all(pts <= o, axis=1)
    return float(covered.mean() * np.prod(ideal - ref))


class NSGA2:
    """(mu+lambda) NSGA-II: tournament select -> SBX crossover + gaussian
    mutation -> fast non-dominated sort + crowding selection."""

    def __init__(self, mean, lo, hi, pop, seed=0, scales=None):
        self.lo, self.hi, self.pop = lo, hi, pop
        self.rng = np.random.default_rng(seed)
        sc = (hi - lo) * 0.1 if scales is None else scales
        self.mut_sd = sc
        self.parents = np.clip(
            mean + self.rng.standard_normal((pop, len(mean))) * sc[None, :] * 2, lo, hi)
        self.gen = 0
        self.parent_objs = None
        self._rank = np.zeros(pop, int)
        self._crowd = np.zeros(pop)

    def ask(self):
        kids = np.empty_like(self.parents)
        for k in range(self.pop):
            a, b = self._tournament(), self._tournament()
            u = self.rng.random(self.parents.shape[1])
            child = np.where(u < 0.5, 0.5 * ((1 + 2 * u) * a + (1 - 2 * u) * b),
                                       0.5 * ((2 - 2 * u) * a + (2 * u) * b))
            child += self.rng.standard_normal(child.shape) * self.mut_sd
            kids[k] = np.clip(child, self.lo, self.hi)
        return kids

    def _tournament(self):
        i, j = self.rng.choice(self.pop, 2, replace=False)
        if self.parent_objs is None:
            return self.parents[self.rng.integers(self.pop)]
        return self.parents[i] if self._better(i, j) else self.parents[j]

    def _better(self, i, j):
        return (self._rank[i], -self._crowd[i]) < (self._rank[j], -self._crowd[j])

    def tell(self, kid_x, kid_objs):
        """Combined parent+offspring selection; returns best front members."""
        all_x = np.vstack([self.parents, kid_x])
        all_o = np.vstack([self.parent_objs, kid_objs]) \
            if self.parent_objs is not None else kid_objs.copy()
        fronts = _nd_fronts(all_o)
        rank = np.empty(len(all_x), int)
        crowd = np.zeros(len(all_x))
        keep = []
        for fi, front in enumerate(fronts):
            for i in front:
                rank[i] = fi
            cd = _crowding(np.array(front), all_o)
            for i, c in zip(front, cd):
                crowd[i] = c
        for front in fronts:
            if len(keep) + len(front) <= self.pop:
                keep += front
            else:
                order = np.argsort(-crowd[front])
                keep += [front[i] for i in order[: self.pop - len(keep)]]
                break
        self.parents = all_x[keep]
        self.parent_objs = all_o[keep]
        self._rank = rank[keep]
        self._crowd = crowd[keep]
        self.gen += 1
        # current pareto front within the new population
        pf = _nd_fronts(self.parent_objs)[0]
        return self.parents[pf], self.parent_objs[pf]


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
    # scan backwards: a tail line can be truncated mid-write if the log is
    # read while the episode is still flushing records
    last_rec = None
    with open(p) as f:
        for line in f:
            try:
                r = json.loads(line)
            except json.JSONDecodeError:
                continue
            last_rec = r
    if last_rec is None:
        return {}
    return last_rec.get("score", {}) if last_rec.get("type") == "end" else {}


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

    def reset_and_spawn(self, name, scenario, jitter=None, terrain=None):
        call("POST", "/v1/reset", {"arena": name})
        # reset() clears the inventory -> re-equip weapon + shield afterwards
        call("POST", "/v1/equip", {"arena": name, "items": [
            {"id": "minecraft:iron_sword", "slot": "main"},
            {"id": "minecraft:shield", "slot": "off"}]})
        cx, cy, cz = self.center[name]
        if terrain:
            for block, pts in terrain:
                pos = [[cx + dx, cy + dy, cz + dz] for dx, dy, dz in pts]
                call("POST", "/v1/terrain", {"arena": name, "block": block, "pos": pos})
        for i, (typ, dx, dz) in enumerate(scenario):
            jx = jz = 0.0
            if jitter is not None:
                jx, jz = jitter[i]
            x, z = cx + dx + jx, cz + dz + jz
            call("POST", "/v1/spawn", {"arena": name, "type": typ,
                                       "pos": [x, cy, z],
                                       "minDist": 5.0})

    def run_batch(self, params_list, scenario, jitter_rng=None, scenarios=None,
                  terrains=None, policy="baseline-melee"):
        """One episode per arena against `scenario`; returns score dicts in order.

        scenarios: optional per-arena scenario list (overrides `scenario`) for
        diversity studies — each arena gets its own sampled layout.

        jitter_rng: when given, every mob gets an independent +-1.25 block
        positional jitter (resampled per arena per call) so repeated evals
        of the same params don't see identical spawn layouts — deconfounds
        lucky formations from policy quality. Radius is kept >= 5.6m so the
        spawn-distance rule never trips.
        """
        logs = []
        for a_i, (name, params) in enumerate(zip(self.arenas, params_list)):
            # single shared terrain spec (CRN): every candidate in the batch
            # plays the identical layout
            terrain = terrains[a_i] if terrains is not None else None
            scen = scenarios[a_i] if scenarios is not None else scenario
            jitter = None
            if jitter_rng is not None:
                jitter = []
                for _typ, dx, dz in scen:
                    j = jitter_rng.uniform(-1.25, 1.25, 2)
                    r = math.hypot(dx + j[0], dz + j[1])
                    if r < 5.6:
                        scale = 5.6 / max(r, 1e-6)
                        j = np.array([(dx + j[0]) * scale - dx,
                                      (dz + j[1]) * scale - dz])
                    jitter.append(j)
            self.reset_and_spawn(name, scen, jitter, terrain)
        # Let spawned mobs finish chunk-entity loading before the episode starts
        # (entities spawned into a still-loading chunk only materialize on the
        # next entity-load pass; a few ticks guarantees they are in the live set).
        try:
            call("POST", "/v1/tick", {"mode": "sprint", "ticks": 10})
        except RuntimeError:
            pass
        for name, params in zip(self.arenas, params_list):
            body = {"arena": name, "policy": policy,
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
        deadline = time.time() + 900
        while time.time() < deadline:
            st = call("GET", "/v1/status")
            if st.get("gate") == "RUN" and len(st.get("episodes", [])) == 0:
                break
            if st.get("gate") == "RUN":
                # sprint budget drained while episodes still run -> top up
                try:
                    call("POST", "/v1/tick", {"mode": "sprint",
                                               "ticks": MAX_TICKS + 50})
                except RuntimeError:
                    pass
            time.sleep(0.3)
        scores = []
        for lg in logs:
            scores.append(episode_score(lg))
            # per-tick obs logs are ~MBs each — hundreds of episodes per
            # generation would fill the disk if every one were kept
            try:
                p = Path(lg)
                (p if p.is_absolute() else RUN_DIR / p).unlink(missing_ok=True)
            except OSError:
                pass
        return scores


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--gens", type=int, default=15)
    ap.add_argument("--pop", type=int, default=8)
    ap.add_argument("--arenas", type=int, default=8)
    ap.add_argument("--reps", type=int, default=1,
                    help="episode repetitions per candidate per scenario (eval noise smoothing)")
    ap.add_argument("--nscen", type=int, default=12,
                    help="fresh scenarios sampled per generation; all candidates share the set (CRN)")
    ap.add_argument("--neval", type=int, default=24,
                    help="final head-to-head: batches of 4 paired scenarios")
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--algo", choices=["nsga2", "cmaes"], default="nsga2")
    ap.add_argument("--out", default=str(Path(__file__).parent / "results"))
    args = ap.parse_args()
    if args.algo == "nsga2":
        main_nsga2(args)
        return

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

    def sample_eval_set(rng, n):
        out = []
        for _ in range(n):
            scen = random_scenario(rng)
            terr = random_terrain(rng, avoid_pts=[(dx, dz) for _t, dx, dz in scen])
            out.append((scen, terr))
        return out

    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    hist_path = out / "history.jsonl"
    best = (-1e9, None)

    with open(hist_path, "a") as hf:
        for gen in range(args.gens):
            t0 = time.time()
            xs = es.ask()
            xs = np.clip(xs, lo, hi)
            # Evaluate: all candidates share a freshly sampled set of nscen
            # (scenario, terrain) pairs (common random numbers) — batch per
            # scenario across all arenas. Set resampled per generation.
            cand_f = np.zeros(args.pop)
            set_rng = np.random.default_rng(args.seed * 7919 + gen)
            eval_set = sample_eval_set(set_rng, args.nscen)
            for rep in range(args.reps):
                for scen, terr in eval_set:
                    params_batch = [encode(x) for x in xs]
                    scores = sim.run_batch(params_batch, scen,
                                           terrains=[terr] * n_arenas)
                    for i, s in enumerate(scores):
                        cand_f[i] += score_of(s)
            cand_f /= len(eval_set) * args.reps
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

    # ---- final head-to-head: baseline vs best on fresh paired eval ----
    # each batch: 4 fresh (scenario, terrain) pairs x (baseline, best) per pair
    print("[final] head-to-head baseline vs best ...", flush=True)
    final = {"baseline": [], "best": [], "diffs": []}
    eval_rng = np.random.default_rng(777)
    n_pairs = n_arenas // 2
    for rep in range(args.neval):
        pair_scens, pair_terr = [], []
        for _p in range(n_pairs):
            scen = random_scenario(eval_rng)
            pair_scens += [scen, scen]
            terr = random_terrain(eval_rng, avoid_pts=[(dx, dz) for _t, dx, dz in scen])
            pair_terr += [terr, terr]
        batch = ([defaults(), best[1]] * n_pairs)[:n_arenas]
        scores = sim.run_batch(batch, None, scenarios=pair_scens, terrains=pair_terr)
        for p in range(n_pairs):
            fb, ft = score_of(scores[2 * p]), score_of(scores[2 * p + 1])
            final["baseline"].append(fb)
            final["best"].append(ft)
            final["diffs"].append(ft - fb)
    report = {
        "baseline_J": float(np.mean(final["baseline"])),
        "best_J": float(np.mean(final["best"])),
        "diff_mean": float(np.mean(final["diffs"])),
        "diff_se": float(np.std(final["diffs"]) / math.sqrt(len(final["diffs"]))),
        "n_paired": len(final["diffs"]),
        "baseline_per_scen": final["baseline"],
        "best_per_scen": final["best"],
        "best_params": best[1],
    }
    (out / "final_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


# ----------------------------------------------------------- nsga-ii main --

# Fixed box for comparable hypervolume across runs (all dims maximized):
# ref = worst plausible, ideal = utopian bound.
HV_REF = np.array([-1.0, -45.0, -0.05, -0.05, -650.0])
HV_IDEAL = np.array([8.0, 0.5, 1.05, 1.05, -40.0])


def main_nsga2(args):
    n_arenas = max(args.arenas, args.pop)
    arenas = [f"opt-{i}" for i in range(n_arenas)]
    sim = Sim(arenas)

    print("[setup] creating arenas ...", flush=True)
    for name in arenas:
        sim.setup_arena(name)
    call("POST", "/v1/tick", {"mode": "sprint", "ticks": 40})
    call("POST", "/v1/tick", {"mode": "run"})

    def eval_batch(xs, eval_set):
        """Objective matrix (pop x 5): per-dim mean metrics over eval set."""
        objs = np.zeros((len(xs), len(METRIC_NAMES)))
        n_done = 0
        for scen, terr in eval_set:
            params_batch = [encode(x) for x in xs]
            try:
                scores = sim.run_batch(params_batch, scen, terrains=[terr] * n_arenas)
            except RuntimeError as e:
                print(f"  [warn] batch skipped: {e}", flush=True)
                continue
            n_done += 1
            for i, s in enumerate(scores):
                objs[i] += metrics_of(s)
        return objs / max(n_done, 1)

    def sample_eval_set(rng, n):
        out = []
        for _ in range(n):
            scen = random_scenario(rng)
            terr = random_terrain(rng, avoid_pts=[(dx, dz) for _t, dx, dz in scen])
            out.append((scen, terr))
        return out

    mean = np.array([d for _n, _l, _h, d, _i in PARAMS])
    lo = np.array([l for _n, l, h, _d, _i in PARAMS])
    hi = np.array([h for _n, _l, h, _d, _i in PARAMS])
    ga = NSGA2(mean, lo, hi, args.pop, seed=args.seed)

    out = Path(args.out); out.mkdir(parents=True, exist_ok=True)
    hist_path = out / "history.jsonl"

    # seed population eval on a shared scenario set
    init_set = sample_eval_set(np.random.default_rng(args.seed * 31 + 1), args.nscen)
    objs0 = eval_batch(ga.parents, init_set)
    ga.parent_objs = objs0
    fronts = _nd_fronts(objs0)
    print(f"[seed] front0 size={len(fronts[0])} hv={hypervolume(objs0[fronts[0]], HV_REF, HV_IDEAL):.1f}", flush=True)

    with open(hist_path, "a") as hf:
        for gen in range(args.gens):
            t0 = time.time()
            xs = ga.ask()
            set_rng = np.random.default_rng(args.seed * 7919 + gen + 1000)
            eval_set = sample_eval_set(set_rng, args.nscen)
            kid_objs = eval_batch(xs, eval_set)
            front_x, front_o = ga.tell(xs, kid_objs)
            hv = hypervolume(front_o, HV_REF, HV_IDEAL)
            rec = {"gen": gen, "front_size": len(front_o),
                   "front": np.round(front_o, 3).tolist(),
                   "front_params": [encode(x) for x in front_x],
                   "hv": round(hv, 2), "sec": round(time.time() - t0, 1)}
            hf.write(json.dumps(rec) + "\n"); hf.flush()
            print(f"[gen {gen}] front={len(front_o)}  hv={hv:7.1f}  ({rec['sec']}s)", flush=True)

    # ---- final: re-evaluate the pareto front on a large fresh eval set ----
    print("[final] re-evaluating pareto front on fresh scenarios ...", flush=True)
    eval_rng = np.random.default_rng(555)
    big_set = sample_eval_set(eval_rng, max(args.nscen * 2, 24))
    base_objs = eval_batch(np.array([[d for _n, _l, _h, d, _i in PARAMS]]), big_set)[0]
    pf = _nd_fronts(ga.parent_objs)[0]
    final_objs = eval_batch(ga.parents[pf], big_set)
    report = {
        "metric_names": METRIC_NAMES,
        "baseline_metrics": np.round(base_objs, 3).tolist(),
        "pareto_front": [
            {"params": encode(x), "metrics": np.round(o, 3).tolist()}
            for x, o in zip(ga.parents[pf], final_objs)
        ],
        "eval_set_size": len(big_set),
        "hypervolume_final": round(hypervolume(final_objs, HV_REF, HV_IDEAL), 2),
    }
    (out / "final_report.json").write_text(json.dumps(report, indent=2))
    print(json.dumps(report, indent=2), flush=True)


if __name__ == "__main__":
    main()
