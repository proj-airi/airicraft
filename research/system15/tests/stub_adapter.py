"""A character-level stand-in for DiffusionGemma, for exercising the denoising loop without weights.

The stub "knows" a target READING. Its logits point at the target character at every canvas position, with a
sharpness that grows with the fraction of the canvas already correct and with self-conditioning, so warm starts
settle in a couple of steps and cold starts take longer. It says nothing about the real model's quality.
"""
from __future__ import annotations

import torch

from s15 import reading


class StubAdapter:
    def __init__(self, target: dict[str, str], canvas_length: int = 512, base: float = 4.0, gain: float = 18.0):
        self.canvas_length = canvas_length
        self.vocab_size = 128
        self.eos_token_id = 0
        self.device = torch.device("cpu")
        self.base, self.gain = base, gain
        self.set_target(target)
        self.prefills: list[int] = []
        self.forward_calls = 0

    def set_target(self, target: dict[str, str]) -> None:
        text, _ = reading.serialize(target)
        self.set_target_ids([ord(c) % 128 for c in text])

    def set_target_ids(self, ids: list[int]) -> None:
        ids = list(ids)[: self.canvas_length]
        self.target = torch.tensor(ids + [0] * (self.canvas_length - len(ids)), dtype=torch.long)

    def reset(self) -> None:
        pass

    def prompt_ids(self, messages: list[dict]) -> list[int]:
        return [ord(c) % 128 for c in messages[-1]["content"][:64]]

    def prefill(self, ids: list[int]) -> dict:
        self.prefills.append(len(ids))
        return {"encoded_tokens": len(ids), "reused_tokens": 0}

    def logits(self, canvas, self_conditioning):
        self.forward_calls += 1
        correct = (canvas[0] == self.target).float().mean()
        strength = self.base + self.gain * correct + (2.0 if self_conditioning is not None else 0.0)
        logits = torch.zeros(self.canvas_length, self.vocab_size)
        logits[torch.arange(self.canvas_length), self.target] = strength
        return logits.unsqueeze(0)

    def encode_text(self, text: str):
        return [ord(c) % 128 for c in text], [(i, i + 1) for i in range(len(text))]

    def pieces(self, ids: list[int]):
        text, offsets, cursor = [], [], 0
        for token in ids:
            piece = "" if token == 0 else chr(token)
            text.append(piece)
            offsets.append((cursor, cursor + len(piece)))
            cursor += len(piece)
        return "".join(text), offsets
