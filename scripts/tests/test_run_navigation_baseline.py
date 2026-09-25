#!/usr/bin/env python3
"""Focused tests for the unattended navigation baseline launcher."""

from __future__ import annotations

import importlib.machinery
import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
from types import ModuleType


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "run-navigation-baseline"


def load_script() -> ModuleType:
    module_name = "airicraft_run_navigation_baseline_under_test"
    loader = importlib.machinery.SourceFileLoader(module_name, str(SCRIPT_PATH))
    spec = importlib.util.spec_from_loader(module_name, loader)
    if spec is None:
        raise RuntimeError(f"cannot load {SCRIPT_PATH}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    loader.exec_module(module)
    return module


launcher = load_script()


class OptionsTest(unittest.TestCase):
    def test_overrides_replace_existing_keys_and_append_missing_ones(self) -> None:
        merged = launcher.merge_options("renderDistance:12\nfov:0.0\n", {"renderDistance": "6", "maxFps": "30"})
        self.assertEqual("renderDistance:6\nfov:0.0\nmaxFps:30\n", merged)


class GameDirectoryTest(unittest.TestCase):
    def test_world_archive_becomes_a_save_without_its_session_lock(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "world.zip"
            with zipfile.ZipFile(archive, "w") as world:
                world.writestr("level.dat", b"level")
                world.writestr("session.lock", b"lock")
                world.writestr("region/r.0.0.mca", b"region")
            game_dir = root / "game"

            launcher.prepare_game_dir(game_dir, archive)

            save = game_dir / "saves" / launcher.WORLD_NAME
            self.assertTrue((save / "level.dat").is_file())
            self.assertTrue((save / "region" / "r.0.0.mca").is_file())
            self.assertFalse((save / "session.lock").exists())
            self.assertIn("pauseOnLostFocus:false", (game_dir / "options.txt").read_text(encoding="utf-8"))


class WorldLookupTest(unittest.TestCase):
    def test_world_is_found_by_save_directory_name(self) -> None:
        payload = {"worlds": [{"name": "other", "worldId": "other-1"}, {"name": launcher.WORLD_NAME, "worldId": "nb-2"}]}
        self.assertEqual("nb-2", launcher.find_world_id(payload, launcher.WORLD_NAME))

    def test_missing_world_is_an_error(self) -> None:
        with self.assertRaises(launcher.baseline.BaselineError):
            launcher.find_world_id({"worlds": []}, launcher.WORLD_NAME)


if __name__ == "__main__":
    unittest.main()
