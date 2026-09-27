#!/usr/bin/env python3
"""Live pixel check for PR #68 against the copied farm_easy world.

Run with AIRICRAFT_BRIDGE_STATE_FILE pointing at an isolated client that has
joined the farm_easy save with its player near (38, 64, 94). Requires Pillow.
Captures are written to run/pr68-validation/ and contain no bridge token.
"""

import base64
import json
import os
import urllib.request
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "run" / "pr68-validation"
POSE = {"x": 42.46815667871856, "y": 69.09116882454315,
        "z": 90.53126438152934, "yaw": 45, "pitch": 45}
QUERY_BOX = [36, 63, 91, 41, 65, 96]


def main():
    bridge_path = os.environ.get("AIRICRAFT_BRIDGE_STATE_FILE")
    if not bridge_path:
        raise SystemExit("Set AIRICRAFT_BRIDGE_STATE_FILE to the isolated client's state file")
    state = json.loads(Path(bridge_path).read_text())
    output = OUTPUT
    output.mkdir(parents=True, exist_ok=True)

    def post(path, body):
        request = urllib.request.Request(
            f"http://127.0.0.1:{state['port']}{path}",
            data=json.dumps(body).encode(),
            headers={"Authorization": "Bearer " + state["token"],
                     "Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)

    post("/v1/player/command", {"command": "time set day"})
    post("/v1/player/command", {"command": "gamerule doDaylightCycle false"})
    post("/v1/player/command", {"command": "weather clear"})

    def capture(name, **options):
        result = post("/v1/camera/tactical", {
            "mode": "pose", **POSE, "settleFrames": 80, "keepPose": False,
            "fadeLeaves": False, **options,
        })
        path = output / f"{name}.png"
        path.write_bytes(base64.b64decode(result["imageBase64"]))
        return Image.open(path).convert("RGB")

    box_off = capture("box-off")
    box_on = capture("box-on", queryBox=QUERY_BOX)
    box_cleared = capture("box-cleared")
    leaf_off = capture("leaf-off")
    leaf_on = capture("leaf-on", fadeLeaves=True)
    capture("combined", fadeLeaves=True, queryBox=QUERY_BOX)

    leaf_area = (0, 20, 300, 420)
    before_leaves = leaf_off.crop(leaf_area).load()
    after_leaves = leaf_on.crop(leaf_area).load()
    leaf_changes = sum(
        1 for y in range(leaf_area[3] - leaf_area[1])
        for x in range(leaf_area[2] - leaf_area[0])
        if max(abs(before_leaves[x, y][channel] - after_leaves[x, y][channel])
               for channel in range(3)) > 20
    )
    tint_points = [(400, 320), (500, 290)]
    tint_blue_gains = [
        (box_on.getpixel(point)[2] - box_on.getpixel(point)[0])
        - (box_off.getpixel(point)[2] - box_off.getpixel(point)[0])
        for point in tint_points
    ]
    residual_blue_gains = [
        (box_cleared.getpixel(point)[2] - box_cleared.getpixel(point)[0])
        - (box_off.getpixel(point)[2] - box_off.getpixel(point)[0])
        for point in tint_points
    ]
    print(f"leaf changed pixels: {leaf_changes} (need > 5000)")
    print(f"query box blue-vs-red gains at {tint_points}: {tint_blue_gains} (need > 25 each)")
    print(f"query box blue-vs-red residue after clear: {residual_blue_gains} (need < 20 each)")
    if (leaf_changes <= 5000 or any(gain <= 25 for gain in tint_blue_gains)
            or any(abs(gain) >= 20 for gain in residual_blue_gains)):
        raise SystemExit("FAIL: leaf translucency, query-box tint, or tint clearing is broken")
    print("PASS: leaf translucency and query-box tint are visible and tint clears")


if __name__ == "__main__":
    main()
