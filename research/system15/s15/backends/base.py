"""Filler interface: turn a state document (plus the previous READING) into a new READING."""
from __future__ import annotations

from dataclasses import dataclass, field

from ..statedoc import StateDoc


@dataclass
class FillRequest:
    doc: StateDoc
    previous: dict[str, str] | None = None  # previous READING of the same run (warm start / update mode)
    dirty_slots: set[str] = field(default_factory=set)  # slots whose declared inputs changed
    dirty_sections: set[str] = field(default_factory=set)
    step_budget: int | None = None  # diffusion: max denoising steps for this refresh


@dataclass
class FillResult:
    reading: dict[str, str]
    parse_ok: bool
    exact: dict[str, bool]
    latency_ms: float
    slot_confidence: dict[str, float] = field(default_factory=dict)  # 0..1, higher = more certain
    steps: int | None = None
    timings: dict[str, float] = field(default_factory=dict)
    # Diffusion only: per step {"step", "mean_entropy", "accepted", "slots": {slot: {"value", "entropy"}}}
    trajectory: list[dict] = field(default_factory=list)
    raw_text: str = ""
    usage: dict = field(default_factory=dict)

    def to_json(self) -> dict:
        return {
            "reading": self.reading, "parse_ok": self.parse_ok, "exact": self.exact,
            "latency_ms": round(self.latency_ms, 2), "slot_confidence": self.slot_confidence,
            "steps": self.steps, "timings": {k: round(v, 2) for k, v in self.timings.items()},
            "trajectory": self.trajectory, "raw_text": self.raw_text, "usage": self.usage,
        }


class Filler:
    name = "base"

    def fill(self, request: FillRequest) -> FillResult:
        raise NotImplementedError

    def reset(self) -> None:
        """Forget per-run state (caches). Called when a new run starts."""
