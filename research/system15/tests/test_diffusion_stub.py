"""Denoising-loop tests with a stub adapter (needs torch; skipped otherwise). No model weights involved."""
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / "tests"))

try:
    import torch  # noqa: F401
except ImportError:  # pragma: no cover
    torch = None

from s15 import reading  # noqa: E402
from s15.backends.base import FillRequest  # noqa: E402
from s15.recordings import load_run  # noqa: E402
from s15.statedoc import build_docs  # noqa: E402

TARGET = dict(reading.default_reading(), **{"chat.intent": "stop", "say": "Stopping.", "escalate.level": "now"})
PREVIOUS = dict(TARGET, **{"chat.intent": "none", "say": "", "escalate.level": "no"})
DIRTY = {"chat.intent", "say", "escalate.level"}


@unittest.skipIf(torch is None, "torch not installed")
class DenoiserTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.doc = build_docs(load_run(ROOT / "tests" / "fixtures" / "run-a"), "event")[3]

    def _filler(self, **config):
        from s15.backends.diffusiongemma import DenoiseConfig, DiffusionGemmaFiller
        from stub_adapter import StubAdapter
        stub = StubAdapter(TARGET)
        return stub, DiffusionGemmaFiller(stub, DenoiseConfig(seed=7, **config))

    def test_entropy_bound_accepts_lowest_entropy_first(self):
        from s15.backends.diffusiongemma import entropy_bound_accept
        accept = entropy_bound_accept(torch, torch.tensor([0.5, 0.01, 0.2, 0.02]), 0.1)
        # Sorted 0.01, 0.02, 0.2, 0.5: entropy already accepted before each is 0, 0.01, 0.03, 0.23 -> the first
        # three stay within the 0.1 bound (the diffusers EntropyBoundScheduler rule).
        self.assertEqual(accept.tolist(), [False, True, True, True])

    def test_temperature_schedule_matches_released_sampler(self):
        from s15.backends.diffusiongemma import temperature_at
        self.assertAlmostEqual(temperature_at(0, 48, 0.4, 0.8), 0.8)
        self.assertAlmostEqual(temperature_at(47, 48, 0.4, 0.8), 0.4 + 0.4 / 48)

    def test_warm_start_converges_faster_than_cold(self):
        stub, filler = self._filler()
        cold = filler.fill(FillRequest(self.doc))
        warm = filler.fill(FillRequest(self.doc, PREVIOUS, DIRTY))
        self.assertEqual(cold.reading, TARGET)
        self.assertEqual(warm.reading, TARGET)
        self.assertLess(warm.steps, cold.steps)
        self.assertEqual(len(warm.trajectory), warm.steps)
        self.assertEqual(warm.trajectory[-1]["slots"]["chat.intent"]["value"], "stop")

    def test_step_budget_is_respected(self):
        _, filler = self._filler(warm_steps=1)
        result = filler.fill(FillRequest(self.doc, PREVIOUS, DIRTY))
        self.assertEqual(result.steps, 1)

    def test_clamped_fixed_layout_keeps_template_and_reads_slots(self):
        from s15.backends.diffusiongemma import default_widths, fixed_layout
        stub, filler = self._filler(clamp_template=True)
        layout = fixed_layout(stub, TARGET, default_widths(stub))
        stub.set_target_ids(layout.ids)
        cold = filler.fill(FillRequest(self.doc))
        warm = filler.fill(FillRequest(self.doc, PREVIOUS, DIRTY))
        self.assertEqual(cold.reading, TARGET)
        self.assertEqual(warm.reading, TARGET)
        self.assertEqual(set(warm.slot_confidence), set(reading.SLOT_NAMES))

    def test_fixed_layout_positions_do_not_move_when_values_change(self):
        from s15.backends.diffusiongemma import default_widths, fixed_layout
        from stub_adapter import StubAdapter
        stub = StubAdapter(TARGET)
        widths = default_widths(stub)
        a, b = fixed_layout(stub, PREVIOUS, widths), fixed_layout(stub, TARGET, widths)
        self.assertEqual(a.slots, b.slots)
        self.assertEqual(a.template, b.template)


if __name__ == "__main__":
    unittest.main()
