"""DiffusionGemma filler with warm start (T1 cold / T2 warm).

The READING form lives in the 256-token decoder canvas; the state document is the prompt, encoded once by
the causal encoder into a KV cache that is reused across refreshes when only the volatile tail changed.

Mechanics follow the released sampler as implemented in diffusers' DiffusionGemmaPipeline and
EntropyBoundScheduler (uniform corruption, entropy-bound acceptance, temperature 0.8 -> 0.4, self-conditioning on
temperature-scaled logits, stop when the argmax canvas is stable and mean entropy < 0.005). What is new here:

* warm start: the canvas starts from the previous READING (HF `decoder_input_ids` supports a starting canvas),
  with the tokens of dirty slots renoised to uniform random tokens and the temperature schedule entered late;
* optional clamping of the JSON template tokens (keys and punctuation), so only values can change;
* a per-step trajectory of slot values and slot entropies, the raw material for settledness signals (E3).

Requires torch + transformers with DiffusionGemma support (2026-06 or later) and a CUDA GPU for meaningful timing.
The loop is exercised in tests with a stub adapter; it has not been run against the released weights in this repo.
"""
from __future__ import annotations

import math
import time
from dataclasses import dataclass

from .. import prompts, reading
from .base import FillRequest, FillResult, Filler


@dataclass
class DenoiseConfig:
    max_steps: int = 48  # cold-start budget; matches the released checkpoint
    warm_steps: int = 8  # budget for a warm refresh (E2 sweeps this)
    entropy_bound: float = 0.1
    t_max: float = 0.8
    t_min: float = 0.4
    confidence_threshold: float = 0.005
    stability_threshold: int = 1
    warm_start: bool = True
    renoise_dirty: bool = True
    clamp_template: bool = False
    carry_self_conditioning: bool = False  # reuse last refresh's final logits as step-0 self-conditioning
    greedy: bool = False
    record_trajectory: bool = True
    seed: int = 0


def temperature_at(step: int, total: int, t_min: float, t_max: float) -> float:
    """EntropyBoundScheduler: t_max on the first step of a `total`-step schedule, t_min on the last."""
    fraction = (total - step) / total
    return t_min + (t_max - t_min) * fraction


def entropy_bound_accept(torch, entropy, bound: float):
    """Accept the lowest-entropy positions while the entropy of the already-accepted ones stays <= bound."""
    sorted_entropy, order = torch.sort(entropy, dim=-1, descending=False)
    cumulative = torch.cumsum(sorted_entropy, dim=-1)
    sorted_accept = (cumulative - sorted_entropy) <= bound
    return torch.zeros_like(sorted_accept).scatter(-1, order, sorted_accept)


def positions_for_spans(offsets: list[tuple[int, int]], spans: dict[str, tuple[int, int]]) -> dict[str, list[int]]:
    """Token positions overlapping each character span; empty spans map to the token at their position."""
    result: dict[str, list[int]] = {}
    for name, (start, end) in spans.items():
        if end > start:
            hits = [i for i, (a, b) in enumerate(offsets) if a < end and b > start]
        else:
            hits = [i for i, (a, b) in enumerate(offsets) if a <= start < b][:1]
        result[name] = hits
    return result


@dataclass
class Layout:
    """Token layout of a READING on the canvas."""
    ids: list[int]  # exactly canvas_length tokens
    template: list[bool]  # True for keys/punctuation/tail (clamped in clamp mode)
    slots: dict[str, list[int]]  # value token positions per slot
    fixed_width: bool


TEXT_WIDTHS = {"situation": 32, "anomaly.detail": 20, "fix.arg": 10, "say": 24}
REF_WIDTH = 5


def default_widths(adapter) -> dict[str, int]:
    """Token budget per value in fixed-width layouts (template + values must fit the 256-token canvas)."""
    widths = {}
    for slot in reading.SLOTS:
        if slot.kind == "enum":
            widths[slot.name] = max(len(adapter.encode_text(value)[0]) for value in slot.values) + 1
        elif slot.kind == "ref":
            widths[slot.name] = REF_WIDTH
        else:
            widths[slot.name] = TEXT_WIDTHS.get(slot.name, 2 * slot.max_words)
    return widths


def free_layout(adapter, flat: dict[str, str]) -> Layout:
    """The canonical JSON tokenized as-is; value lengths vary, so positions shift when values change."""
    length = adapter.canvas_length
    text, spans = reading.serialize(flat)
    ids, offsets = adapter.encode_text(text)
    ids, offsets = ids[:length], offsets[:length]
    slots = positions_for_spans(offsets, spans)
    values = {p for positions in slots.values() for p in positions}
    pad = adapter.eos_token_id if adapter.eos_token_id is not None else 0
    template = [i not in values for i in range(len(ids))] + [True] * (length - len(ids))
    return Layout(ids + [pad] * (length - len(ids)), template, slots, False)


def fixed_layout(adapter, flat: dict[str, str], widths: dict[str, int]) -> Layout:
    """Keys/punctuation tokenized once; every value gets a fixed token budget padded with spaces.

    Template tokens never move, so they can be clamped and a slot's positions are known at every step.
    """
    length = adapter.canvas_length
    text, spans = reading.serialize(flat)
    space = (adapter.encode_text(" ")[0] or [adapter.eos_token_id or 0])[0]
    ids: list[int] = []
    template: list[bool] = []
    slots: dict[str, list[int]] = {}
    cursor = 0
    for slot in reading.SLOTS:
        start, end = spans[slot.name]
        segment = adapter.encode_text(text[cursor:start])[0]
        ids += segment
        template += [True] * len(segment)
        value = adapter.encode_text(text[start:end])[0] if end > start else []
        width = widths[slot.name]
        value = value[:width] + [space] * (width - min(len(value), width))
        slots[slot.name] = list(range(len(ids), len(ids) + width))
        ids += value
        template += [False] * width
        cursor = end
    tail = adapter.encode_text(text[cursor:])[0]
    ids += tail
    template += [True] * len(tail)
    if len(ids) > length:
        raise ValueError(f"fixed layout needs {len(ids)} tokens > canvas {length}; reduce value widths")
    pad = adapter.eos_token_id if adapter.eos_token_id is not None else 0
    template += [True] * (length - len(ids))
    return Layout(ids + [pad] * (length - len(ids)), template, slots, True)


class DiffusionGemmaFiller(Filler):
    def __init__(self, adapter, config: DenoiseConfig | None = None):
        import torch  # deferred so the stdlib-only parts of the harness import without torch

        self.torch = torch
        self.adapter = adapter
        self.config = config or DenoiseConfig()
        # Draw noise on the model's device: a [canvas, vocab] uniform tensor is ~270 MB per step.
        self.generator = torch.Generator(device=str(adapter.device)).manual_seed(self.config.seed)
        self._last_logits = None
        self._widths = None
        mode = "warm" if self.config.warm_start else "cold"
        self.name = f"diffusiongemma:{mode}:{self.config.warm_steps if self.config.warm_start else self.config.max_steps}"

    def reset(self) -> None:
        self._last_logits = None
        self.adapter.reset()

    # -- canvas construction -------------------------------------------------------------------------------
    def _random(self, count: int):
        return self.torch.randint(0, self.adapter.vocab_size, (count,), generator=self.generator,
                                  device=self.adapter.device)

    def _layout(self, flat: dict[str, str]) -> Layout:
        if self.config.clamp_template:
            if self._widths is None:
                self._widths = default_widths(self.adapter)
            return fixed_layout(self.adapter, flat, self._widths)
        return free_layout(self.adapter, flat)

    def _initial_canvas(self, request: FillRequest, warm: bool):
        torch, adapter = self.torch, self.adapter
        layout = self._layout(request.previous if warm else reading.default_reading())
        layout_ids = torch.tensor(layout.ids, dtype=torch.long, device=adapter.device)
        template_mask = torch.tensor(layout.template, dtype=torch.bool, device=adapter.device)
        if warm:
            canvas = layout_ids.clone()
            if self.config.renoise_dirty:
                for slot in request.dirty_slots:
                    for position in layout.slots.get(slot, []):
                        canvas[position] = self._random(1)[0]
        else:
            canvas = self._random(adapter.canvas_length)
            if self.config.clamp_template:
                canvas = torch.where(template_mask, layout_ids, canvas)
        return canvas.unsqueeze(0), layout_ids, template_mask, layout

    # -- per-step bookkeeping ------------------------------------------------------------------------------
    def _slot_snapshot(self, argmax_ids: list[int], entropy: list[float], layout: Layout) -> dict:
        if layout.fixed_width:  # positions are fixed: read each slot's tokens directly
            slots = {}
            for name, positions in layout.slots.items():
                value, _ = self.adapter.pieces([argmax_ids[i] for i in positions])
                slots[name] = {"value": value.strip(),
                               "entropy": round(sum(entropy[i] for i in positions) / len(positions), 5)}
            return {"slots": slots}
        text, offsets = self.adapter.pieces(argmax_ids)
        eos = self.adapter.eos_token_id
        if eos is not None and eos in argmax_ids:
            cut = argmax_ids.index(eos)
            text = text[: offsets[cut][0]] if cut < len(offsets) else text
        spans = reading.value_spans(text)
        positions = positions_for_spans(offsets, spans)
        slots = {}
        for name, (start, end) in spans.items():
            hits = positions.get(name) or []
            slots[name] = {
                "value": text[start:end],
                "entropy": round(sum(entropy[i] for i in hits) / len(hits), 5) if hits else None,
            }
        return {"slots": slots}

    # -- main loop -----------------------------------------------------------------------------------------
    def fill(self, request: FillRequest) -> FillResult:
        torch, adapter, config = self.torch, self.adapter, self.config
        started = time.perf_counter()
        prefill = adapter.prefill(adapter.prompt_ids(prompts.filler_messages(request.doc)))
        after_prefill = time.perf_counter()

        warm = config.warm_start and request.previous is not None
        budget = request.step_budget or (config.warm_steps if warm else config.max_steps)
        budget = max(1, min(budget, config.max_steps))
        offset = config.max_steps - budget if warm else 0
        canvas, template_ids, template_mask, layout = self._initial_canvas(request, warm)
        clamp = config.clamp_template

        self_conditioning = self._last_logits if (warm and config.carry_self_conditioning) else None
        history: list = []
        trajectory: list[dict] = []
        argmax = canvas[0]
        entropy = None
        steps = 0
        step_times: list[float] = []
        with torch.no_grad():
            for step in range(budget):
                step_started = time.perf_counter()
                logits = adapter.logits(canvas, self_conditioning)[0].float()  # [C, V]
                temperature = temperature_at(offset + step, config.max_steps, config.t_min, config.t_max)
                scaled = logits / temperature
                log_probs = torch.log_softmax(scaled, dim=-1)
                entropy = -(log_probs.exp() * log_probs).sum(dim=-1)  # [C]
                if config.greedy:
                    candidates = scaled.argmax(dim=-1)
                else:  # Gumbel-max == sampling from softmax(scaled)
                    uniform = torch.rand(scaled.shape, generator=self.generator, device=scaled.device).clamp_(1e-10, 1.0)
                    candidates = (scaled - torch.log(-torch.log(uniform))).argmax(dim=-1)
                accept = entropy_bound_accept(torch, entropy, config.entropy_bound)
                if clamp:
                    accept = accept | template_mask
                    candidates = torch.where(template_mask, template_ids, candidates)
                noise = self._random(candidates.shape[0])
                canvas = torch.where(accept, candidates, noise).unsqueeze(0)
                self_conditioning = scaled.unsqueeze(0)
                argmax = scaled.argmax(dim=-1)
                if clamp:
                    argmax = torch.where(template_mask, template_ids, argmax)
                steps = step + 1
                step_times.append((time.perf_counter() - step_started) * 1000.0)

                stable = len(history) >= config.stability_threshold and all(
                    torch.equal(previous, argmax) for previous in history[-config.stability_threshold:])
                history.append(argmax)
                mean_entropy = float(entropy.mean())
                if config.record_trajectory:
                    snapshot = self._slot_snapshot(argmax.tolist(), entropy.tolist(), layout)
                    trajectory.append({"step": step, "temperature": round(temperature, 4),
                                       "mean_entropy": round(mean_entropy, 6), "accepted": int(accept.sum()),
                                       "slots": snapshot["slots"]})
                if stable and mean_entropy < config.confidence_threshold:
                    break
        if config.carry_self_conditioning:
            self._last_logits = self_conditioning
        denoise_ms = (time.perf_counter() - after_prefill) * 1000.0

        final_ids = argmax.tolist()
        if layout.fixed_width:  # read values from their fixed positions; the template is known
            values = {name: adapter.pieces([final_ids[i] for i in hits])[0].strip()
                      for name, hits in layout.slots.items()}
            text = reading.serialize(values)[0]
            positions = layout.slots
        else:
            text, offsets = adapter.pieces(final_ids)
            if adapter.eos_token_id is not None and adapter.eos_token_id in final_ids:
                cut = final_ids.index(adapter.eos_token_id)
                text = text[: offsets[cut][0]] if cut < len(offsets) else text
            positions = positions_for_spans(offsets, reading.value_spans(text))
        parsed = reading.parse(text)
        entropies = entropy.tolist() if entropy is not None else []
        confidence = {}
        for name, hits in positions.items():
            if hits and entropies:
                confidence[name] = math.exp(-sum(entropies[i] for i in hits) / len(hits))
        total_ms = (time.perf_counter() - started) * 1000.0
        timings = {"prefill_ms": (after_prefill - started) * 1000.0, "denoise_ms": denoise_ms,
                   "mean_step_ms": sum(step_times) / len(step_times) if step_times else 0.0,
                   "encoded_tokens": float(prefill.get("encoded_tokens", 0)),
                   "reused_tokens": float(prefill.get("reused_tokens", 0)), "warm": float(warm)}
        return FillResult(parsed.reading, parsed.parse_ok, parsed.exact, total_ms, slot_confidence=confidence,
                          steps=steps, timings=timings, trajectory=trajectory, raw_text=text)


class HFDiffusionGemmaAdapter:
    """transformers-backed adapter (transformers >= 5.11); mirrors diffusers' DiffusionGemmaPipeline calls.

    KV-prefix reuse: the encoder cache is cropped to the prompt prefix shared with the previous refresh and only
    the changed tail is re-encoded. transformers refuses to crop a sliding-window layer that has seen more tokens
    than its window, so reuse only applies while the cached prompt is shorter than `sliding_window`; longer state
    documents are re-encoded in full (E1 reports prefill cost separately). vLLM/SGLang prefix caching does not
    have this limitation, but they do not expose warm starts.
    """

    def __init__(self, model, processor, reuse_prefix: bool = True):
        import torch

        self.torch = torch
        self.model = model.eval()
        self.processor = processor
        self.tokenizer = getattr(processor, "tokenizer", processor)
        self.canvas_length = int(model.config.canvas_length)
        self.text_config = model.config.get_text_config(decoder=True)
        self.vocab_size = int(self.text_config.vocab_size)
        self.eos_token_id = self.tokenizer.eos_token_id
        self.device = next(model.parameters()).device
        self.reuse_prefix = reuse_prefix
        self.sliding_window = int(getattr(self.text_config, "sliding_window", 0) or 0)
        self.reset()

    @classmethod
    def load(cls, model_id: str = "google/diffusiongemma-26B-A4B-it", dtype: str = "bfloat16",
             device_map: str = "auto", reuse_prefix: bool = True, adapter_path: str | None = None):
        import torch
        from transformers import AutoProcessor, DiffusionGemmaForBlockDiffusion

        model = DiffusionGemmaForBlockDiffusion.from_pretrained(model_id, dtype=getattr(torch, dtype),
                                                                device_map=device_map)
        if adapter_path:  # LoRA from E5; adapters must stay unmerged (encoder and decoder share weights)
            model.load_adapter(adapter_path, adapter_name="s15")
            model.set_adapter("s15")
        return cls(model, AutoProcessor.from_pretrained(model_id), reuse_prefix)

    def reset(self) -> None:
        self.cache = None
        self.cached_ids: list[int] = []
        self.mask_mapping = None
        self.positions = None

    def prompt_ids(self, messages: list[dict]) -> list[int]:
        encoded = self.processor.apply_chat_template(messages, add_generation_prompt=True, tokenize=True,
                                                     return_dict=True, return_tensors="pt")
        return encoded["input_ids"][0].tolist()

    def _reusable_prefix(self, ids: list[int]) -> int:
        if self.cache is None or not self.reuse_prefix:
            return 0
        if self.sliding_window and len(self.cached_ids) >= self.sliding_window:
            return 0  # sliding layers already dropped tokens; cropping would raise
        common = 0
        for a, b in zip(self.cached_ids, ids):
            if a != b:
                break
            common += 1
        return min(common, len(ids) - 1)  # always encode at least one token

    def prefill(self, ids: list[int]) -> dict:
        torch = self.torch
        from transformers import DynamicCache

        reused = self._reusable_prefix(ids)
        if reused > 0:
            try:
                self.cache.crop(reused)
                if self.cache.get_seq_length() != reused:
                    reused = 0
            except ValueError:
                reused = 0
        if reused == 0:
            self.cache = DynamicCache(config=self.text_config)
        input_ids = torch.tensor([ids], device=self.device)
        attention_mask = torch.ones_like(input_ids)
        self.model.model.encoder(input_ids=input_ids[:, reused:], attention_mask=attention_mask,
                                 past_key_values=self.cache,
                                 position_ids=torch.arange(reused, len(ids), device=self.device).unsqueeze(0))
        self.cached_ids = list(ids)
        length = self.canvas_length
        decoder_attention_mask = torch.nn.functional.pad(attention_mask.bool(), (0, length), value=True)
        self.mask_mapping = self.model.model.decoder.create_diffusion_decoder_attention_mask(
            config=self.text_config, inputs_embeds=torch.empty((1, length, 0), device=self.device),
            past_key_values=self.cache, decoder_attention_mask=decoder_attention_mask)
        self.positions = torch.arange(len(ids), len(ids) + length, device=self.device).unsqueeze(0)
        return {"encoded_tokens": len(ids) - reused, "reused_tokens": reused}

    def logits(self, canvas, self_conditioning):
        output = self.model(decoder_input_ids=canvas, past_key_values=self.cache,
                            self_conditioning_logits=self_conditioning, decoder_attention_mask=self.mask_mapping,
                            decoder_position_ids=self.positions)
        return output.logits

    def encode_text(self, text: str) -> tuple[list[int], list[tuple[int, int]]]:
        encoded = self.tokenizer(text, add_special_tokens=False, return_offsets_mapping=True)
        return list(encoded["input_ids"]), [tuple(span) for span in encoded["offset_mapping"]]

    def pieces(self, ids: list[int]) -> tuple[str, list[tuple[int, int]]]:
        """Text and per-token character spans (SentencePiece: '▁' is a space; byte tokens decoded)."""
        text_parts, offsets, cursor = [], [], 0
        for token in self.tokenizer.convert_ids_to_tokens(ids):
            token = token or ""
            if token.startswith("<0x") and token.endswith(">") and len(token) == 6:
                piece = chr(int(token[3:5], 16))
            elif token.startswith("<") and token.endswith(">"):
                piece = ""  # special tokens (eos, pad) render as nothing
            else:
                piece = token.replace("▁", " ")
            text_parts.append(piece)
            offsets.append((cursor, cursor + len(piece)))
            cursor += len(piece)
        return "".join(text_parts), offsets
