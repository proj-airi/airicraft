"""Client for a filler served by `s15 serve` (e.g. DiffusionGemma on a GPU host behind an SSH tunnel)."""
from __future__ import annotations

import json
import time
import urllib.request

from .base import FillRequest, FillResult, Filler


class RemoteFiller(Filler):
    def __init__(self, url: str, timeout_s: float = 30.0):
        self.url = url.rstrip("/")
        self.timeout_s = timeout_s
        local = any(host in self.url for host in ("://localhost", "://127.0.0.1"))
        self._opener = urllib.request.build_opener(*([urllib.request.ProxyHandler({})] if local else []))
        with self._opener.open(f"{self.url}/health", timeout=timeout_s) as response:
            health = json.loads(response.read().decode("utf-8"))
        self.name = f"remote:{health.get('filler', '?')}"
        self._reset_next = True

    def reset(self) -> None:
        self._reset_next = True

    def fill(self, request: FillRequest) -> FillResult:
        body = {"doc": request.doc.to_json(), "previous": request.previous,
                "dirty_sections": sorted(request.dirty_sections), "step_budget": request.step_budget,
                "reset": self._reset_next}
        self._reset_next = False
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        started = time.perf_counter()
        http = urllib.request.Request(f"{self.url}/fill", data=data, method="POST",
                                      headers={"Content-Type": "application/json"})
        with self._opener.open(http, timeout=self.timeout_s) as response:
            out = json.loads(response.read().decode("utf-8"))
        round_trip_ms = (time.perf_counter() - started) * 1000.0
        timings = dict(out.get("timings") or {})
        timings["server_ms"] = out.get("server_ms", 0.0)
        timings["network_ms"] = round_trip_ms - float(out.get("server_ms", 0.0))
        return FillResult(out["reading"], out["parse_ok"], out.get("exact") or {}, round_trip_ms,
                          slot_confidence=out.get("slot_confidence") or {}, steps=out.get("steps"), timings=timings,
                          trajectory=out.get("trajectory") or [], raw_text=out.get("raw_text", ""),
                          usage=out.get("usage") or {})
