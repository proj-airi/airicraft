# Navigation watchdog live validation — 2026-09-22

Revision tested: `20f54bb3`. Two fresh evaluator Minecraft clients, external Codex driver enabled, embedded planner disabled. Isolated game directory; the archived Cortico cooperative world was not changed. No production class hot swap. The evaluator used Zulu Java 21.0.5 rather than the crashed cooperative run's JBR/DCEVM runtime, so this is not a reproduction or clearance of that native GC crash.

## Results

| Check | Evidence | Result |
| --- | --- | --- |
| Normal follow from underground to surface | `job-469a6dd9-1021-4e54-9cb0-d7a805c79693`; player moved from roughly (64,48,1) to (56,66,24), near target (54.5,66,26.5); task remained running after arrival | Passed |
| Immobilized follow | `job-1454a8c9-bba0-42f4-a47a-d5cfede1677d`; started tick 502, shared dispatcher failed at 602 with `navigation_stuck` | Passed, exactly 100 ticks |
| Immobilized navigation | `job-2a424a55-f98a-4bcc-a194-f38133f1b8f7`; started tick 845, failed at 945 | Passed, exactly 100 ticks |
| New navigation after timeout | `job-a639f5d3-91da-4108-b555-9f7895dc7b40`; started tick 4562, completed at 4607; inventory inspection confirms (12,200,0) | Passed |
| Slow obsidian excavation | Repeated attempts timed out. Read-only Arthas samples reported `breakingTarget=null`, `breakingProgress=0`, stationary at (1.7,200,0.5); diamond pickaxe selected and undamaged | Inconclusive for progressing mining: mining never started. Timeout consistent with actual lack of progress |

No native crash occurred during either session. Both clients and the Mineflayer target were stopped afterward. This is a bounded smoke test, not long-duration stability proof.

The previous full build passed 1,618 tests, with two skipped. Unit regressions cover repeated jumps, advancing breaking progress, interrupted same-block breaks and alternating unfinished blocks. Actual repeated jumping and interrupted active mining were not reproduced in this live fixture.

## Fixture and evidence

Local raw evidence: `run/watchdog-validation-20260922/` (ignored, not versioned). Includes requests, terminal events, inventories, screenshots, launcher logs, live progress samples and the temporary `WatchdogValidationFixture.java` source. Logs may contain local dashboard credentials; do not publish raw logs without redaction.

First client used an isolated copy of the existing underground scenario. Second client added a temporary evaluator-only setup endpoint: flat stone platform at Y=200; test player teleports and inventory; movement-speed zero plus slowness for immobilization; restored speed for recovery; bedrock corridor with two obsidian blocks for excavation. The normal `follow_player` and `navigate_to` tools drove all task execution. Fixture did not modify watchdog state or fabricate terminal results. Its initializer registration and source were removed after stopping the client.

Read-only Arthas instrumentation was added only after the uninstrumented follow/navigation timeout checks, to distinguish actual breaking from a false watchdog timeout. The first Optional-object watch failed serialization; subsequent string watches returned usable samples. No structural redefinition was attempted.

The excavation failure remains an unresolved mining/control observation, not proof of a watchdog regression. Do not describe this validation as proving uninterrupted excavation in live gameplay.
