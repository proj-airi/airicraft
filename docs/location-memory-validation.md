# Unified location-memory validation

Validated on 2026-09-15 with Minecraft 1.21.8 and the pinned JourneyMap 6.0.0-beta.52 addon.

## Automated checks

The full multi-project build passed:

```sh
./gradlew -Dorg.gradle.jvmargs=-Xmx2G --max-workers=1 --no-parallel \
  -Pairicraft.includeEvaluator=true build --continue --console=plain
python3 -m unittest discover -s scripts/tests -v
```

The root suite ran 1,308 tests with two skips and no failures. JourneyMap ran 20 tests, evaluator 30, wrapper 94, and launcher/evaluator Python 23, all passing. Earlier concurrent attempts encountered heap pressure and timing-sensitive failures; the completed serial build passed without changing test time limits.

Both location providers exercise the same create/recall/list/update/rename/delete contract, stable IDs, notes, protection, and reopening storage. Additional tests cover installed-but-unavailable selection without fallback, legacy-file preservation, ambiguous names, native ownership and unrelated metadata, malformed protection data, world-store changes, and fail-closed protection snapshots. JourneyMap unit tests use a copy-returning native API fixture; real native persistence is checked below.

## Disposable JourneyMap client

The client used `scripts/external-driver`, an isolated bridge/game directory under `/tmp/airicraft-location-live`, and copies of the pickup fixture. External-driver mode was verified before mutations. Native UI actions targeted only this disposable client; UI evidence came from Minecraft's F2 screenshots.

- A seeded legacy `places.json` contained a local-only location. JourneyMap started empty and never imported it.
- `remember_place` created a persistent native waypoint with a GUID, purpose note, and protected bounds. The map CLI and native waypoint manager displayed the same single entry.
- Editing its name and coordinates in JourneyMap's native editor changed the generic recall result under the same GUID. The note and protected bounds survived.
- A real `/kill` and automatic respawn produced one native death marker. Generic listing included it once, with its native name/coordinates, empty note, and no protection. Deleting that GUID through `forget_place` removed the native entry.
- Native dimension travel and config reload kept the selected backend. The dimension test exposed JourneyMap's display-coordinate projection: `getX()/getZ()` scale for the viewer's dimension. Both generic tools and map CLI now use the unprojected `getBlockPos()` coordinates. A regression test covers the exact scaling case.
- After restarting the client, a second disposable world had an empty JourneyMap store. Returning to the original world restored the same GUID, edited native coordinates, note, and protected bounds. In the Nether, the Overworld entry correctly retained Overworld coordinates. The deleted death marker stayed deleted.
- The legacy `places.json` SHA-256 remained unchanged throughout JourneyMap operations, including restart and world changes.

World/server store switching and unavailable states also have provider-level tests. A separate remote multiplayer server was not launched for this smoke test.

## Client without JourneyMap

The same disposable game directory was launched through `scripts/external-driver-evaluator` with `AIRICRAFT_EVALUATOR_SCENARIO_MANIFEST` unset. The runtime loaded no optional integrations and map status reported unavailable.

`list_places` selected `backend=airicraft` and returned only the original legacy local location, even though native JourneyMap waypoint files remained in the game directory. Creating a local location, reloading Airicraft, and recalling its ID preserved the complete coordinates, note, and protected area. Deleting it left only the legacy location. JourneyMap's `WaypointData.dat` checksum remained unchanged during fallback mutations.

All disposable clients were stopped after validation. No live remote multiplayer session was used, and these checks do not claim a full gameplay/evaluation-scenario batch run.
