# Finite JavaScript policies

As of 2026-09-20, action policies are disabled. `run_policy` is not advertised
or executable, and the planner shell does not start speculative continuations.
Action-policy prompts are retained only as test fixtures. `query_world`,
self-created read-only tools, and query-only `read_policy_docs` remain available.
Use ordinary gameplay tools for actions. The action-policy details below document
the retained experimental implementation, not the current planner interface.

`run_policy` lets the controller planner express a finite procedure as a JavaScript generator. Java executes its yielded effects and resumes the same generator with their results. No model call is needed between effects. The existing work history owns the invocation's identity, terminal outcome and cancellation surface.

`run_policy` exposes named JavaScript functions for most native gameplay tools: navigation, mining/gathering, crafting, construction, containers, equipment, eating, entity interactions, smelting, and reads. Each uses the existing argument contract and native executor. `yield p.describe('craftRecipe')` returns the exact schema. No container is required to start; the original verified container helpers remain available in singleplayer. Policies are finite and do not persist JavaScript stacks across restarts.

`query_world` shares the GraalJS engine but exposes only a detached local block/entity snapshot to a synchronous `function query(world, input)`. It creates no foreground work and can run while an action is active. Both planner roles receive it; only the controller receives `run_policy`. The controller prompt recommends policies for dependent gameplay sequences and includes restock, reserve-aware withdrawal and selected-count examples. Both roles receive examples recommending `query_world` for custom block/entity projections, while specialized interaction/placement checks retain their native tools.

`read_policy_docs` returns the API and debugging guide packaged in the running build (`src/main/resources/airicraft/policies/api.md`). It is read-only, needs no world, and does not end the turn. It covers signatures, observation fields, limits, errors and partial-effect recovery without granting repository or filesystem access.

## Read-only world queries

```json
{
  "source": "function query(w, input) { return w.blocks.filter(b => b.blockId === input.id).map(b => b.position); }",
  "input": {"id": "minecraft:oak_door"},
  "radius": 4,
  "verticalRadius": 2,
  "includeEntities": false
}
```

Capture runs on the client thread, guest evaluation off-thread, and successful observation registration back on the client thread. Bounds default to the player's position, with an optional integer `center`. Horizontal radius is 0..8, vertical radius 0..4, and every corner must lie within 64 blocks of the player. At most 2601 block positions and 64 nearest entities enter the snapshot. No chunks are loaded. Read metadata identifies unloaded/outside-world cells and entity truncation. The result envelope preserves this host-owned metadata even if the guest modifies its detached copy. The world must still match at delivery, and successful block observations enter normal read-freshness tracking.

Only the selected JSON and metadata return to the planner; the full block/entity snapshot is not inserted into the prompt. The 2 MiB snapshot input allowance is specific to queries; policy inputs and guest output remain limited to 16384 characters. Queries have no policy effect API or access to live Minecraft objects. Combined client light, spawn-group hostility and loaded terrain are observations, not reachability, threat or visibility guarantees.

## Authoring

Define `function* main(policy, input)` and supply JSON input. Named methods mirror snake_case tool names in camelCase, for example `yield policy.navigateTo({x,y,z,exactY:true})` and `yield policy.craftRecipe({recipeId,times:1})`. Read `ok` before continuing. `result` preserves the native JSON/text; `work` reports the final tracked outcome. No generic string-dispatch API is exposed. The complete method list is in the bundled policy API guide.

The original container helper example remains valid:

```js
function* main(policy, input) {
  let state = yield policy.observeContainer();
  const missing = Math.max(0, input.target - (state.inventory[input.itemId] || 0));
  if (missing > (state.container[input.itemId] || 0))
    return {restocked: false, reason: 'insufficient_stock'};
  if (missing > 0)
    state = yield policy.withdraw(state.syncId, [{itemId: input.itemId, quantity: missing}]);
  yield policy.closeContainer(state.syncId);
  return {restocked: true, inventory: state.inventory};
}
```

The bundled `src/main/resources/airicraft/policies/restock-open-container.js` handles several stock targets in one transfer. Its input is `{"stock":{"minecraft:bread":8,"minecraft:torch":16}}`. Counts cover the 36 carried storage/hotbar slots; armor and offhand are excluded.

| Yielded effect | Completion and result |
| --- | --- |
| `policy.observeContainer()` | Fresh integrated-server observation of the same open window: `{syncId, container, inventory}`, with registry-ID count maps. |
| `policy.withdraw(syncId, items)` | Preflights through the existing container controller, submits ordinary screen clicks once, then waits for both server-observed source and destination counts to match the expected transfer. Returns the confirmed snapshot. |
| `policy.closeContainer(syncId)` | Sends the normal close and waits for the server to stop observing that window. Returns `{closed:true,syncId}`. |

Reads run on the integrated server thread; click submission runs on the client thread; guest evaluation runs on its own thread. No guest code receives Java or Minecraft objects. The server adapter reads only the controlled player's currently open window, never unopened containers.

## Run and inspect

The controller's tool schema and external Codex-driver tool list expose `run_policy` with `source` and `input` fields. A call returns an `OPERATION:` work ID and yields the planner turn until the work changes. Use `inspect_work` for the result and effect evidence, or `cancel_work` with the exact work ID. The thinking planner does not receive this mutation tool.

For a driver client with an open chest:

```sh
python3 - <<'PY'
import json, pathlib, subprocess
source = pathlib.Path('src/main/resources/airicraft/policies/restock-open-container.js').read_text()
subprocess.run([
    'wrapper/build/install/airicraft/bin/airicraft', 'agent', 'tools', 'call',
    '--name', 'run_policy', '--arguments', json.dumps({
        'source': source, 'input': {'stock': {'minecraft:bread': 8}}
    })
], check=True)
PY
```

`SUCCEEDED` means the program returned normally. Interpret its returned value too: `{restocked:false}` is a valid program result, not proof that the requested stock was obtained. Named tool methods return `{ok,tool,result,work?}` and wait for tracked child work to become terminal. Rejections and failures return `ok:false` for script branching. Legacy container-helper failures still fail the invocation. Unsupported effects fail rather than retrying automatically. The work details retain source, input, yielded effects, completed results and the terminal reason.

## Interruption and limits

One invocation owns normal actuation at a time. Its child jobs/graphs run through the normal scheduler, are parented to the policy, and are cancelled with it. New planner mutations are rejected while it runs; safety reflexes retain priority. Reflex takeover, death, world leave, reset, cancellation and replacement of normal work stop the invocation. A cancelled policy is not automatically resumed or replayed. Transfers already committed remain committed; a pending effect may have changed the world without its confirmation being retained. Inspect fresh counts before starting again. Cancellation deliberately does not issue cleanup clicks or close a window owned by a reflex/user.

Limits: 32,768 source characters; 16,384 characters per JSON value; 128 effects; 12,000 client ticks per invocation; 100 client ticks per container effect; 200,000 guest statements per evaluation; 10-second initialization and 1-second resume deadlines. The guest has no host-class, filesystem, network or process access. These restrictions do not impose a hard guest heap limit within the shared JVM: this remains an experimental behavior-authoring surface.

GraalJS 25.0.4 is included in the mod as nested dependencies. A separate GraalVM installation is not required; ordinary Java 21 uses the interpreter fallback.

## Verification

The 2026-09-19 named-action extension passed the full build and focused tests of delayed child completion, failure branching, cancellation, schema lookup, rejected recursive/control effects, and actual runtime scheduling/ownership. The live driver smoke used a fresh `codex-policy-actions-20260919` world copy:

- `OPERATION:72f30725-7b73-414b-a790-8d88f537b0d7` sequenced navigation, query, chest opening, verified withdrawal, closure, recipe lookup, furnace crafting, placement attempt, and inventory inspection (11 effects). Navigation and crafting succeeded. Placement returned the native line-of-sight failure as `ok:false`, allowing the script to continue inspecting.
- `OPERATION:33b09ef7-3b85-4058-b139-1e74b743c988` mined one grass block and confirmed one collected drop. Its fixed placement site also failed native visibility checks.
- `OPERATION:7320ef33-3c26-4634-b719-b0e22265f87f` selected a site using a fresh query and successfully placed the crafted furnace at `-51,66,-107`.
- Cancelling `OPERATION:47c045ce-8b78-435e-a0b3-ae48fa985175` cancelled its running navigation child. A competing external navigation call was rejected while the policy owned the lane.

Evidence is retained locally in `run/policy-actions-smoke-20260919/`. These are hand-authored live procedures, not proof of planner adoption, all-method gameplay coverage, live reflex interruption, or multiplayer behavior. Image/LLM and planner-control tools remain outside the policy API. Named calls reuse native completion guarantees; they do not strengthen ordinary tool semantics to server-confirmed transactions.

### Earlier container/query qualification

The query extension executes all six prompt examples in focused tests, including restock shortfall and already-stocked branches. Tests also cover host/effect access rejection, runaway computation, output/input bounds, invalid query bounds, async/generator rejection, coverage preservation against guest edits, failed-query evidence exclusion and world-change rejection. The full build reports 1490 tests, zero failures/errors and two skipped.

A 2026-09-19 dev-client query smoke in the same isolated world copy read all 2601 positions in the maximum box and projected only two door records. The lower door at `-51,66,-103` matched a separate `inspect_world` observation (`open=false`, `half=lower`). Entity-only and dark-cell examples completed; host-class access and out-of-range bounds were rejected. A query completed while `OPERATION:cad023b3-d540-4c3b-a396-f560ece86422` remained RUNNING, then that test policy was explicitly cancelled and a separate close policy succeeded. `read_policy_docs` also succeeded before joining a world. Local evidence is in `run/query-smoke-20260919/`. This checks hand-authored calls through the live planner-tool path; it does not establish model preference, token savings, multiplayer behavior or positive live entity/truncation cases.

Focused tests execute the real GraalJS generator with delayed host completions, cancellation, failed transfers, host-access rejection and bounded loops. The restock test checks that three dependent effects complete in one policy invocation without inference between them. Container arithmetic tests require matching source and destination totals. These tests do not by themselves qualify live Minecraft packet handling or model-authored gameplay.

The 2026-09-19 dev-client smoke used an isolated copy of the latest playtest world, a separate bridge and Codex-driver mode. One invocation (`OPERATION:efafe461-2ca5-4644-8416-22a3b56f6812`) completed observe → withdraw → close in 27 client ticks. The server-observed chest changed from 18 to 10 torches and 13 to 9 oak planks; carried storage changed from zero to 8 and 4 respectively. A separate inventory tool confirmed those counts. Repeating the same stock targets issued only observe and close, transferring nothing. A second policy was explicitly cancelled while observing; a competing transfer was rejected as `policy_active`. Evidence remains locally under `run/policy-smoke-20260919/`.

This smoke validates the hand-authored procedure through the real tool/work path. It does not measure model-authored policy quality, live reflex takeover, multiplayer support, restart recovery, or the packaged production client's Graal class loading. The production artifact is built with the engine JARs nested separately; the dev smoke caught and removed aggregate Graal POM dependencies from Fabric's launch classpath.

```sh
source .envrc
./gradlew :test --tests 'ai.moeru.airicraft.policy.*' --tests 'ai.moeru.airicraft.agent.Container*Test'
```

## Planning during policy execution

The runtime prepares at most one continuation for each root policy after the controller's starting turn finishes. A dedicated backend receives accepted context, the active policy source/input, current facts, and the build's policy API. Its only tool is a proposal form of `run_policy` with a required `guard`; it cannot read or mutate the live world. The normal controller tool contract remains `source` plus `input`.

The proposal guard contains an exact `parentResult` JSON object, `inventoryMin` item counts, and up to 32 observed `blocks` (`x`, `y`, `z`, `blockId`). The owning client thread checks world/decision ownership, goal, user guidance, safety epoch, travel restrictions and health while work runs. At handoff it additionally requires successful parent work and effects, the exact return value, sufficient inventory, matching loaded blocks, and idle actuators. The ordinary action executor then admits the policy. Unexpressed prerequisites require fresh reads inside the procedure before effects; otherwise the speculative planner must abstain. No world simulation is claimed.

A late, invalid, failed or abstaining request falls back immediately to normal planning. One uncancellable request may drain in its dedicated worker, but no additional speculative requests queue behind it. Speculative output never advances the normal conversation or speaks in chat. Only actual admission is recorded as `policy.continuation.accepted`, including source, input and receipt; `started`, `ready`, `validated`, `discarded`, and `rejected` events explain other stages. Intermediate child completions do not wake the normal planner while the root policy still owns work. Safety and user guidance remain able to interrupt.

Prefer small deterministic success return objects to make handoffs verifiable. Policies returning native receipts with unpredictable work IDs usually cause the speculative planner to abstain. Initial planning and rejected/late continuations can still leave idle time.
