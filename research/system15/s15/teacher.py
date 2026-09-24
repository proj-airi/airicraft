"""Teacher labels: a strong model fills the READING form for every state document (resumable)."""
from __future__ import annotations

import json
from pathlib import Path

from . import prompts, reading
from .oai import OpenAICompatClient
from .recordings import Run
from .statedoc import StateDoc, render_event


def hindsight_text(run: Run, doc: StateDoc, window_ticks: int) -> str:
    later = [e for e in run.events if doc.tick < e.tick <= doc.tick + window_ticks][:12]
    calls = [c for c in run.calls if doc.tick <= c.dispatch_tick <= doc.tick + window_ticks][:3]
    lines = [render_event(e) for e in later]
    lines += [f"System 2 at tick {c.dispatch_tick}: " + ", ".join(t["name"] for t in c.tool_calls) for c in calls]
    return "\n".join(lines) or "(nothing recorded)"


def label_docs(docs: list[StateDoc], client: OpenAICompatClient, out_path: Path, run: Run | None = None,
               hindsight_window: int = 0, max_tokens: int = 2048, limit: int | None = None) -> int:
    """Append one teacher row per doc to out_path, skipping doc_ids already present. Returns rows written."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    done = set()
    if out_path.exists():
        with open(out_path, "r", encoding="utf-8") as handle:
            done = {json.loads(line)["doc_id"] for line in handle if line.strip()}
    written = 0
    with open(out_path, "a", encoding="utf-8") as handle:
        for doc in docs:
            if doc.doc_id in done:
                continue
            if limit is not None and written >= limit:
                break
            hint = hindsight_text(run, doc, hindsight_window) if run is not None and hindsight_window > 0 else None
            result = client.chat(prompts.teacher_messages(doc, hint), temperature=0.0, max_tokens=max_tokens)
            parsed = reading.parse(result.text)
            handle.write(json.dumps({
                "doc_id": doc.doc_id, "reading": parsed.reading, "parse_ok": parsed.parse_ok,
                "exact": parsed.exact, "raw": result.text, "latency_ms": round(result.latency_ms, 1),
                "model": client.model, "hindsight_window": hindsight_window,
            }, ensure_ascii=False) + "\n")
            handle.flush()
            written += 1
    return written


def read_teacher(path: Path) -> dict[str, dict]:
    with open(path, "r", encoding="utf-8") as handle:
        return {row["doc_id"]: row for row in (json.loads(line) for line in handle if line.strip())}
