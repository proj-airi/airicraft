"""Autoregressive baseline (B2/B3): any OpenAI-compatible endpoint fills the READING form.

Use a local vLLM/SGLang server with automatic prefix caching for latency comparisons (the stable prompt
prefix is SELF+schema, then OBJECTIVE/PLAN, then NOW/RECENT). `update` mode is the autoregressive analogue of
diffusion warm start: the previous READING is in the prompt and the model rewrites it.
"""
from __future__ import annotations

import math

from .. import prompts, reading
from ..oai import OpenAICompatClient
from .base import FillRequest, FillResult, Filler


def _leaf(slot_name: str) -> str:
    return slot_name.split(".")[-1]


def slot_confidence_from_logprobs(text: str, tokens: list[dict], flat: dict[str, str]) -> dict[str, float]:
    """exp(mean token logprob) over each slot value's characters, located by scanning keys in order."""
    offsets, cursor = [], 0
    for token in tokens:
        piece = token.get("token", "")
        offsets.append((cursor, cursor + len(piece), token.get("logprob", 0.0)))
        cursor += len(piece)
    joined = "".join(t.get("token", "") for t in tokens)
    if joined != text:
        return {}
    confidence: dict[str, float] = {}
    position = 0
    for slot in reading.SLOTS:
        key = f'"{_leaf(slot.name)}"'
        found = text.find(key, position)
        if found < 0:
            continue
        start = text.find('"', found + len(key) + 1)
        end = text.find('"', start + 1) if start >= 0 else -1
        if start < 0 or end < 0:
            continue
        span = [lp for (a, b, lp) in offsets if a < end and b > start + 1]  # tokens inside the quotes
        if not span:  # empty value: judge the quotes that close it
            span = [lp for (a, b, lp) in offsets if a < end + 1 and b > start]
        if span:
            confidence[slot.name] = math.exp(sum(span) / len(span))
        position = end + 1
    return confidence


class OpenAIFiller(Filler):
    name = "openai"

    def __init__(self, client: OpenAICompatClient, mode: str = "full", json_mode: bool = True,
                 logprobs: bool = False, max_tokens: int = 400):
        if mode not in ("full", "update"):
            raise ValueError("mode must be full or update")
        self.client = client
        self.mode = mode
        self.json_mode = json_mode
        self.logprobs = logprobs
        self.max_tokens = max_tokens
        self.name = f"openai:{client.model}:{mode}"

    def fill(self, request: FillRequest) -> FillResult:
        previous = request.previous if self.mode == "update" else None
        messages = prompts.filler_messages(request.doc, previous, request.dirty_slots if previous else None)
        result = self.client.chat(messages, temperature=0.0, max_tokens=self.max_tokens, json_mode=self.json_mode,
                                  logprobs=self.logprobs)
        parsed = reading.parse(result.text)
        confidence = slot_confidence_from_logprobs(result.text, result.logprobs or [], parsed.reading) \
            if self.logprobs and result.logprobs else {}
        usage = dict(result.usage)
        return FillResult(parsed.reading, parsed.parse_ok, parsed.exact, result.latency_ms,
                          slot_confidence=confidence, raw_text=result.text, usage=usage,
                          timings={"request_ms": result.latency_ms})
