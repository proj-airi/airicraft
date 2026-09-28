"""Exercise launcher argument handling without starting Minecraft or Gradle."""
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


class DirectWorldLaunchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "scripts").mkdir()
        shutil.copy2(Path(__file__).resolve().parents[1] / "codex-driver", self.root / "scripts/codex-driver")
        # The executable boundary records the actual argv, including spaces.
        gradle = self.root / "gradlew"
        gradle.write_text("#!/usr/bin/env python3\nimport json,sys\nfrom pathlib import Path\nPath('args.json').write_text(json.dumps(sys.argv[1:]))\n")
        gradle.chmod(0o755)
        (self.root / ".envrc").write_text("")

    def launch(self, *args):
        return subprocess.run([str(self.root / "scripts/codex-driver"), *args], text=True, capture_output=True)

    def test_world_folder_with_spaces_is_forwarded_as_one_gradle_property(self):
        result = self.launch("--world", "My survival world", "--offline", "-Pairicraft.jdwp.port=5009")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["wrapper:installDist", "runClient", "-Pairicraft.codexDriver=true",
                          "-Pairicraft.world=My survival world", "--offline", "-Pairicraft.jdwp.port=5009"],
                         json.loads((self.root / "args.json").read_text()))

    def test_no_world_preserves_normal_launch(self):
        self.assertEqual(0, self.launch("--offline").returncode)
        self.assertEqual(["wrapper:installDist", "runClient", "-Pairicraft.codexDriver=true", "--offline"],
                         json.loads((self.root / "args.json").read_text()))

    def test_plain_launch_without_arguments(self):
        result = self.launch()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["wrapper:installDist", "runClient", "-Pairicraft.codexDriver=true"],
                         json.loads((self.root / "args.json").read_text()))

    def test_missing_world_value_does_not_launch(self):
        for args in [("--world",), ("--world", ""), ("--world", "--offline")]:
            with self.subTest(args=args):
                result = self.launch(*args)
                self.assertEqual(2, result.returncode)
                self.assertFalse((self.root / "args.json").exists())

    def test_duplicate_world_does_not_launch(self):
        self.assertEqual(2, self.launch("--world", "one", "--world", "two").returncode)
        self.assertFalse((self.root / "args.json").exists())


if __name__ == "__main__":
    unittest.main()
