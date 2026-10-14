---
name: airicraft-external-driver
description: Launch and directly drive a live Airicraft Minecraft client from the current coding-agent session (Codex, Claude Code, or any agent with a shell) through the local wrapper CLI. Use for interactive Airicraft debugging, reproducing gameplay bugs while editing code, manually exercising planner tools, observing events and terminal task state, or replacing the embedded planner with the coding agent for a debug session.
---

# Airicraft External Driver

Drive Airicraft from the agent session that is already open. Never start, resume, or fork another agent session for this workflow.

## Launch

1. Work in the checkout whose code must run. Confirm no other Minecraft client owns the selected bridge-state file.
2. Start `scripts/external-driver` in a long-running terminal execution and retain its session ID. The script sources `.envrc`, builds the wrapper distribution, and runs Minecraft with only the embedded planner suppressed.
3. The launch task seeds `run/options.txt` with `onboardAccessibility:false`, so a brand-new worktree must reach the title screen and bridge without waiting for the narrator/accessibility screen.
4. Wait for the localhost bridge, then set a task-specific shell variable:

```bash
AIRICRAFT_DRIVER_CLI=wrapper/build/install/airicraft/bin/airicraft
$AIRICRAFT_DRIVER_CLI status
$AIRICRAFT_DRIVER_CLI agent status --verbose
```

Shell executions may be independent. Redefine `AIRICRAFT_DRIVER_CLI` in each new shell call or use the wrapper path directly.

Every wrapper command needs localhost network access. When the agent's shell execution is sandboxed (Codex, for example), authorize the wrapper CLI outside the sandbox before the first bridge call. A sandbox-blocked connection looks stale to the wrapper and removes the selected bridge-state file, forcing a client restart.

Bridge discovery defaults to `~/.airicraft/bridge-state.json`. For an isolated client, export an absolute path before launch:

```bash
export AIRICRAFT_BRIDGE_STATE_FILE=/tmp/airicraft-driver-bridge.json
scripts/external-driver
```

Use the same exported value for each wrapper command. The evaluator batch script assigns its bridge-state paths automatically.

For an embedded-planner batch, use `scripts/run-evaluation-scenarios`. Add `--jobs N` to run isolated scenario clients in parallel:

```bash
scripts/run-evaluation-scenarios \
  --scenario pickup \
  --scenario underground \
  --jobs 2
```

The batch script assigns each client a game directory and bridge file. Parallel runs stop all clients. It deletes passed worker directories and retains other worker directories for diagnosis.

Require `externalDriverActive: true` before acting. A normally launched client must be restarted through `scripts/external-driver`; there is no `agent.yml` switch and no live attach.

For evaluator scenarios, launch `scripts/external-driver-evaluator` instead. It builds the wrapper, loads the evaluator addon, and propagates external-driver mode to the production-style client. Use a fresh worktree when the run must not inherit an existing provider or observability config. Then start and drive a scenario explicitly:

```bash
$AIRICRAFT_DRIVER_CLI evaluation scenarios --verbose
$AIRICRAFT_DRIVER_CLI evaluation run --scenario underground --output-dir /tmp/airicraft-underground --verbose
$AIRICRAFT_DRIVER_CLI evaluation config --verbose
$AIRICRAFT_DRIVER_CLI agent tools call --name return_to_surface --arguments '{"useTowering":true}'
$AIRICRAFT_DRIVER_CLI evaluation results --verbose
```

External-driver evaluation does not submit the scenario prompt or heartbeats to an embedded LLM. Read the scenario config, own the tool loop, and wait for deterministic checks or the elapsed-time budget to terminate the report.

## Inspect and act

List the complete tool schemas before guessing arguments:

```bash
$AIRICRAFT_DRIVER_CLI agent tools list --verbose
```

Call any listed tool with validated JSON arguments:

```bash
$AIRICRAFT_DRIVER_CLI agent tools call --name inspect_inventory --arguments '{}'
$AIRICRAFT_DRIVER_CLI agent tools call --name navigate_to --arguments '{"x": 10, "y": 64, "z": -3}'
```

For an image-producing tool, provide a path, then inspect that local file with the agent's image viewer:

```bash
$AIRICRAFT_DRIVER_CLI agent tools call --name take_a_look --arguments '{}' --output-image /tmp/airicraft-look.png
```

The external call path uses the same schemas, providers, safety reflex ownership, active-task preemption checks, and action executors as the embedded planner. Do not bypass them with Arthas, OGNL, or direct Minecraft-thread mutation.

## Own the loop

1. Read `agent status --verbose`, `agent debug state --verbose`, and `agent events recent --verbose` before deciding.
2. Keep the last `latestSeqNo`. Poll later with `agent events recent --since <seq> --verbose`; do not repeatedly ingest the full buffer.
3. Treat pickup events produced while `mine_blocks` is active as noise unless the active event policy explicitly routes them.
4. Issue one state-changing tool at a time. A result containing `accepted` or `queued` is not completion.
5. Poll `agent tasks --verbose`, `agent goals --verbose`, and matching task events until the same task ID reaches a terminal state. Preserve exact task IDs when interpreting cancellation or supersession.
6. Use `agent event-policy --verbose`, `agent debug timeline --since <entry> --verbose`, world snapshots, screenshots, and action traces to ground a bug before editing.

Polling is intentionally local to this debug task. Do not add production coalescing, supersession, or app-server session management to this workflow.

## Patch and retry

Trace the actual runtime owner, make the narrow code change, and run focused tests after sourcing `.envrc`. A running client keeps old classes loaded after a rebuild, so stop the launcher process and restart `scripts/external-driver` before claiming a live fix.

Use the real bridge events and terminal runtime snapshots as behavioral evidence. Static or unit evidence alone does not prove a gameplay bug is fixed.

When finished, interrupt only the long-running Airicraft launcher terminal. Leave the current agent session running.
