"""The HF adapter against a tiny *random* DiffusionGemma built from the real transformers code (>= 5.11).

Checks API usage, KV-prefix reuse and the sliding-window guard, and that the adapter's cached prefill + decoder
call reproduces the model's own one-shot forward. Says nothing about output quality (weights are random).
Skipped unless torch, tokenizers and a transformers with DiffusionGemma are installed.
"""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

try:
    import torch
    from tokenizers import Regex, Tokenizer, models, pre_tokenizers
    from transformers import (DiffusionGemmaConfig, DiffusionGemmaForBlockDiffusion, DiffusionGemmaTextConfig,
                              Gemma4VisionConfig, PreTrainedTokenizerFast)
except ImportError:  # pragma: no cover
    torch = None

from s15 import reading  # noqa: E402
from s15.backends.base import FillRequest  # noqa: E402
from s15.recordings import load_run  # noqa: E402
from s15.statedoc import build_docs  # noqa: E402


def char_tokenizer():
    vocab = {"<pad>": 0, "<eos>": 1, "<bos>": 2, "<unk>": 3, "\n": 10, "<unused127>": 127}
    vocab.update({f"<unused{i}>": i for i in range(4, 32) if i != 10})
    vocab.update({chr(i): i for i in range(32, 127)})
    backend = Tokenizer(models.WordLevel(vocab, unk_token="<unk>"))
    backend.pre_tokenizer = pre_tokenizers.Split(Regex("."), behavior="isolated")
    tokenizer = PreTrainedTokenizerFast(tokenizer_object=backend, eos_token="<eos>", pad_token="<pad>",
                                        bos_token="<bos>", unk_token="<unk>")
    tokenizer.chat_template = ("{% for m in messages %}{{ m['role'] }}: {{ m['content'] }}\n{% endfor %}"
                               "{% if add_generation_prompt %}model: {% endif %}")
    return tokenizer


def tiny_model(sliding_window: int):
    text = DiffusionGemmaTextConfig(vocab_size=128, hidden_size=32, intermediate_size=64, num_hidden_layers=2,
                                    num_attention_heads=2, num_key_value_heads=1, head_dim=16, global_head_dim=16,
                                    sliding_window=sliding_window, layer_types=["sliding_attention", "full_attention"],
                                    max_position_embeddings=8192, pad_token_id=0, eos_token_id=1, bos_token_id=2,
                                    num_experts=4, top_k_experts=2, moe_intermediate_size=32,
                                    num_global_key_value_heads=1)
    torch.manual_seed(0)
    vision = Gemma4VisionConfig(hidden_size=16, intermediate_size=32, num_hidden_layers=1, num_attention_heads=2,
                                num_key_value_heads=2, head_dim=8, position_embedding_size=64)
    config = DiffusionGemmaConfig(text_config=text, vision_config=vision, canvas_length=512)
    return DiffusionGemmaForBlockDiffusion(config).eval()


@unittest.skipIf(torch is None, "torch/transformers with DiffusionGemma not installed")
class TinyDiffusionGemmaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.docs = build_docs(load_run(ROOT / "tests" / "fixtures" / "run-a"), "event")

    def adapter(self, sliding_window: int):
        from s15.backends.diffusiongemma import HFDiffusionGemmaAdapter
        return HFDiffusionGemmaAdapter(tiny_model(sliding_window), char_tokenizer())

    def test_cached_decoder_matches_one_shot_forward(self):
        adapter = self.adapter(4096)
        ids = adapter.prompt_ids([{"role": "user", "content": "hello world"}])
        adapter.prefill(ids)
        canvas = torch.randint(0, 128, (1, 512), generator=torch.Generator().manual_seed(1))
        with torch.no_grad():
            cached = adapter.logits(canvas, None)
            direct = adapter.model(input_ids=torch.tensor([ids]), decoder_input_ids=canvas).logits
        self.assertEqual(tuple(cached.shape), (1, 512, 128))
        self.assertTrue(torch.allclose(cached, direct, atol=1e-4))

    def test_prefix_reuse_and_sliding_window_guard(self):
        short = self.adapter(4096)
        base = short.prompt_ids([{"role": "user", "content": "state A " * 20}])
        changed = short.prompt_ids([{"role": "user", "content": "state A " * 19 + "state B "}])
        self.assertEqual(short.prefill(base)["reused_tokens"], 0)
        second = short.prefill(changed)
        self.assertGreater(second["reused_tokens"], 100)
        self.assertEqual(second["reused_tokens"] + second["encoded_tokens"], len(changed))
        with torch.no_grad():  # reuse must not change the result
            canvas = torch.zeros((1, 512), dtype=torch.long)
            reused_logits = short.logits(canvas, None)
            fresh = self.adapter(4096)
            fresh.prefill(changed)
            self.assertTrue(torch.allclose(reused_logits, fresh.logits(canvas, None), atol=1e-4))

        windowed = self.adapter(64)  # prompt longer than the window: crop would raise, so re-encode fully
        windowed.prefill(base)
        self.assertEqual(windowed.prefill(changed)["reused_tokens"], 0)

    def test_filler_runs_cold_and_warm_end_to_end(self):
        from s15.backends.diffusiongemma import DenoiseConfig, DiffusionGemmaFiller
        filler = DiffusionGemmaFiller(self.adapter(64), DenoiseConfig(max_steps=4, warm_steps=2, seed=3))
        cold = filler.fill(FillRequest(self.docs[0]))
        warm = filler.fill(FillRequest(self.docs[1], cold.reading, reading.dirty_slots({"NOW", "RECENT"})))
        self.assertLessEqual(cold.steps, 4)
        self.assertLessEqual(warm.steps, 2)
        self.assertEqual(len(warm.trajectory), warm.steps)
        self.assertEqual(set(warm.reading), set(reading.SLOT_NAMES))  # random weights: values are defaults/garbage
        self.assertGreater(cold.timings["prefill_ms"], 0.0)

    def test_clamped_layout_fits_and_runs(self):
        from s15.backends.diffusiongemma import DenoiseConfig, DiffusionGemmaFiller
        filler = DiffusionGemmaFiller(self.adapter(64), DenoiseConfig(max_steps=3, warm_steps=2, clamp_template=True))
        result = filler.fill(FillRequest(self.docs[2], reading.default_reading(), {"chat.intent"}))
        self.assertEqual(result.steps, 2)
        self.assertEqual(set(result.slot_confidence), set(reading.SLOT_NAMES))


if __name__ == "__main__":
    unittest.main()
