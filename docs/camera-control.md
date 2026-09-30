# Camera control

`ClientRuntimeController` owns the production `CameraController` and ticks it once
at the end of each client tick. Task executors, social looking, survival reflexes,
vision capture, the player bridge, and navigation submit targets to that instance.
Only the controller writes player yaw and pitch. Minecraft still owns manual
mouse input, server corrections, and interpolation of previous/current angles.

`RotationSpring` integrates a critically damped angular spring analytically at
50 ms per client tick. Yaw follows the shortest arc; pitch stays within ±90°.
Retargeting retains angular velocity. A motion finishes only when both angle
error and angular velocity are small. Previous rotation fields are not rewritten,
so Minecraft can interpolate between ticks when rendering.

Navigation submits look targets through the control plane's look channel, and an
explicit Airicraft target takes precedence until it settles. Navigation can
continuously retarget its own motion. Combat and item delivery submit direct aim
only when they own steering.
Combat waypoint steering converts world directions to keys using the current
player yaw, rather than assuming that the requested aim has already been reached.

Block placement retains precise alignment. Mining starts as soon as a fresh
raycast along the current view hits the requested block's outline within reach,
using the actual hit face. It does not wait for spring convergence. Block-breaking
aim points use outline-shape centers, including thin leaf litter. Entity
interactions wait until the current viewing ray intersects the target's bounds.
Targeted vision waits for the spring to settle before scheduling a screenshot;
competing targeted captures fail as busy. World leave, reload, player replacement,
and death discard spring state and cancel pending alignment waits.

The existing `cameraLerpDefaultTicks` and `--duration-ticks` fields are retained.
They now select an approximate spring response time, not a linear interpolation
deadline. Zero selects the default spring (18 radians per second natural
frequency), rather than snapping. `player look-at` always reports `scheduled`.

## Validation

Unit coverage includes convergence, shortest yaw arc, integration across time
steps, retargeting momentum, repeated targets, ownership, and cancellation.

With the former Baritone backend, a real client completed a short eastward route
and its near-180° return. The return trace showed successive yaw values of
−82.846, −43.568, 9.887, 46.862, 68.091, 79.241, and 84.799 degrees; forward input
remained off until the next tick. A targeted eastward screenshot completed.
Evidence from that copied-world smoke is in `/tmp/camera-spring-turn.jsonl` and
`/tmp/camera-spring-return-events.txt` on the development machine.

This is limited navigation/capture proof. The copied survival world later died
to a spider while idle; combat, parkour, elytra, water navigation, and all
interaction types are not established by that smoke. Final ownership and aim
bounds changes require their own live coverage in those conditions.

### Mining regression, 2026-09-20

Commit `b0f0e0ad` also makes `BlockBreakTaskExecutor` yield when a target has not
advanced. Previously, waiting for camera aim could repeat the same target inside
one client tick indefinitely, preventing the camera itself from ticking.

A disposable copy of the failed Nether playtest checkpoint reproduced the bridge
timeout before the fix. A fresh patched copy completed the same four-target
`break_blocks` request at X=-229, Z=-2, Y=70 through 67 (leaf litter, grass block,
and two dirt blocks). Work `JOB:job-be993d3d-6e9b-400f-b385-3b0aaddea20d` reached
`SUCCEEDED` at tick 391; subsequent inspection found all four cells air and the
bridge remained responsive. Local evidence is under
`run/camera-mining-regression/{before,retry}/`. An intervening attempt under
`after/` was interrupted by combat deaths and is not a completed regression.

The full Gradle build passed. Camera tests cover off-center hits, rejection of
misses/other blocks, thin outlines, and a spring trajectory that intersects the
target before settling. The live replay validates explicit block breaking;
acquisition, crop harvesting, and underwater harvesting have not each received a
separate live speed comparison.

### Combat controller wiring, 2026-09-20

The subsequent Nether run exposed a missed production injection:
`EmbodiedAgentRuntime` constructed `SurvivalReflexRuntime` with its default private
camera rather than the client-owned camera. With immediate rotation writes this
had worked; spring requests on the private instance never advanced because only
the shared controller ticks. The first skeleton encounter closed to 2.5 blocks
without attacks and reached the progress timeout; some later zombie attacks
occurred when a target happened to intersect the existing viewing ray.

Production now supplies the shared camera to the reflex and its underwater
recovery controller. An identity regression fails with the old constructor call
and verifies the production runtime forwards the supplied instance. Source run:
`20260920-214730-045922-175-4ac6e232-2f17-4ab9-8da7-45aaf906ecbd`.

### External rotation synchronization

The spring now compares its current orientation with the player's actual view
when accepting a target and before advancing a client tick. An external angular
change greater than 0.01 degrees rebases the spring and clears stale velocity;
normal tracking preserves momentum. Equivalent yaw wrapping rebases only the
angle representation, retaining velocity so rendering does not jump 360 degrees.

Regression tests cover the observed stale-state reproduction (an external move to
90 degrees previously led to a spring write near 50.6 degrees), changes without
a new target request, momentum retention, and equivalent wrapped yaw. These tests
establish the resynchronization fix, not that external corrections caused every
reported navigation jitter. The 10-degree movement-input threshold is unchanged.
