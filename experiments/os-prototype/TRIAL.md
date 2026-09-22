> Historical Node prototype evidence. The active runtime is now the Java/GraalJS mod implementation; see the archived-directory README and docs/embedded-os.md. Results below do not qualify the new execution path.

# OS driver prototype trials — 2026-09-15

These are engineering trials in the user's `OS-Exp` survival world. They are not the planned 30-minute mixed-duty benchmark or a baseline comparison. The client ran in verified external Codex-driver mode. Codex prepared inventory and configured locations before each run; the host performed gameplay during runs. No growth-speed change, item spawning, teleportation, or manual crop-maturity injection was used.

**Result:** the prototype planted 22 starting crops, used growth waits for fishing, observed a natural readiness handoff, and—after correcting a native approach failure—harvested and replanted potatoes before returning to fishing. The final inventory contains 50 fish. Scheduling and native corrections were made between runs; this is not one uninterrupted successful run.

## Starting world and preparation

The existing farm has 80 farmland cells around one water block: X -309 through -301, Z -393 through -385, soil Y 63. Crop Y is 64. Three disjoint strips share the space: wheat X -309..-307, potato X -306..-304, carrot X -303..-301. The verified fishing stand is (-289, 63, -358), targeting water at (-289, 62, -353).

Live chest inspection found six wheat seeds, eight potatoes, eight carrots, eight birch saplings, 64 sticks, 128 string, 64 oak planks, an iron hoe, an iron axe, a water bucket, and two shears. Preparation withdrew the planting stock and materials for one fishing rod and crafting table, crafted through the normal tool executor, and closed the chest. A starting save copy is retained locally under `artifacts/world-backup/OS-Exp/`.

## Initial trial and correction

`artifacts/live-01/` records about 137 seconds of advancing world time before a manual diagnostic stop. The three native crop passes planted all 22 available crops: six wheat, eight potatoes, eight carrots. Each instance entered `WAITING_WORLD` after exhausting its planting stock. Fishing then received the player without a Codex gameplay command.

Two casts failed at approximately 100 active ticks with `fishing_cast_not_in_water`. A separate reproduction using the same native arguments was paused and exported before changing code. At that boundary, bobber entity 716 was at approximately (-288.482, 62.915, -351.661), floating over a source-water block at (-289, 62, -352), while its generic `touchingWater` flag was false. The old check therefore rejected a valid floating hook. The fix samples fluid just below the float instead. The native bite flag still triggers retrieval on the client tick.

The reproduction's native work ID was `JOB:job-e8c195e3-4df1-4de4-875b-448177b8904f`. `artifacts/live-01/incident.jsonl` contains 1,610 exported observations, including frames, and reports an untruncated export. The first run's manual Ctrl-C was initially recorded as an interrupted CLI error; the host now labels a requested interruption explicitly and keeps its persistent wrapper alive for cleanup.

The initial trace also exposed per-command JVM startup overhead. A persistent wrapper stream replaced repeated CLI process launches. Two subsequent preflight attempts (`live-02`, `live-03`) stopped before any player grant because raw loaded-world status exceeded the bounded response size. The stream now selects compact status fields; a regression test covers the large recipe-catalog case.

## Five-minute corrected trial

`artifacts/live-04/` ran for 6,020 advancing world ticks, approximately five minutes. It reused the planted world and the same behavior definition hashes. The host completed 14 fishing activities while all three crop instances waited for maturity.

The final inventory contained **4 cod, 5 salmon, 2 pufferfish, 10 ink sacs, leather boots, and a potion**, alongside the rod. This is 11 fish plus other fishing loot. Actual inventory is the yield evidence; a successful `fish_once` alone only establishes that a bounded cast completed.

The trace audit found:

- No recorded overlapping player grants or overlapping native work admissions.
- Median grant-to-first-native-admission time **179 ms**, maximum **194 ms**, across 15 grants. This measures receipt latency, not the first physical input.
- Median release-to-next-grant interval **1,115 ms**, maximum **1,128 ms**. This includes the deliberate fresh-observation polling boundary; it is not a complete measure of avoidable idle time.
- The final in-progress cast was cancelled by its exact work ID, cancellation was confirmed, the hook was released, and no owner or native work remained unresolved at shutdown.

The water-check reproduction no longer occurred. Repeated casts produced loot in the live world. Crops had not matured during this five-minute window, so this run alone does not establish a live harvest handoff. A subsequent longer run is recorded separately below.

## Ten-minute continuation

`artifacts/live-05/` ran for 12,008 advancing world ticks and completed 25 fishing activities, with no failed activities or recorded ownership conflicts. The ending inventory contained 24 cod, nine salmon, and three pufferfish: **25 additional fish** since the previous run. Crops remained growing; there was no farm wake in this window.

Median grant-to-admission time was 512 ms (maximum 520 ms), and median release-to-next-grant time was 1,216 ms (maximum 1,322 ms). The run ended between casts with no unresolved owner or native work. Timing differs from the previous run and should not be treated as a stable performance guarantee.

The original rod had 16 durability remaining. Between runs, Codex withdrew six sticks and four string from the same chest and crafted two spare rods through the normal executor. The host can equip another carried rod; automatic crafting and restocking remain outside this slice.

## Final runtime corrections

Cancellation now keeps the native fishing executor attached if a cast was sent but no hook acknowledgement has arrived. A missing acknowledgement cannot become permission to grant the player again merely because a timeout expired. If acknowledgement never arrives, the host reports an unconfirmed release and stops. Another regression test covers a new cast after a session pause, ensuring its first bite is reeled immediately.

A strict 25 ms wall deadline caused healthy cold guest initialization to be interrupted while Minecraft and a Gradle build competed for CPU. The sandbox now meters Node-process CPU time during synchronous guest execution, allows 250 ms CPU for source initialization, and retains a one-second wall watchdog. Resumes retain a 25 ms CPU budget. This is bounded prototype accounting, not exact per-guest hardware metering.

## Natural readiness handoff and crop approach failure

`artifacts/live-06/` used a restarted client with the fishing cancellation corrections. A potato matured naturally while fishing was running. Its behavior woke at 08:08:07.940 UTC and won the next player grant at 08:08:10.104 UTC, ahead of the ready fishing instance: **2,164 ms from observed readiness to grant** in this case.

The native crop pass then failed with `crop_approach_failed` before harvesting. The host released that activity, the farm entered its retry delay, and fishing resumed. Codex stopped the run for diagnosis after 6,227 advancing world ticks (about 5.2 minutes). The audit records 11 completed fishing activities, one failed potato activity, no overlapping ownership, and confirmed cancellation of the final cast. Inventory increased by ten fish and one lily pad.

The paused incident export contains 8,966 observations with frames and no truncation or budget drops. The recorded approach targeted potato (-306, 64, -385) from standing cell (-303, 64, -382). The cell center was within interaction range, but navigation arrived near its edge at (-302.216, 64, -381.292), outside reach. Work-position selection now requires the whole standing cell to fit within reach, with a regression based on these coordinates. This corrects a native executor assumption exposed by the scheduler trial.

## Successful replay after the approach correction

`artifacts/live-07/` ran for 2,418 advancing world ticks on another restarted client. The same behavior sources were reused. Two potatoes were already mature at startup, so the potato instance received the first grant. Native work `JOB:job-0f43dee1-2f97-41a0-8efe-93cfa1d86140` completed with **two harvested and nine planted**. Direct block inspection confirmed young crops at the harvested positions and 15 growing potato plants, up from eight total plants before the pass.

The farm then waited for growth while the host navigated back to the beach and completed four fishing activities. Inventory gained three cod and one pufferfish. Final inventory across the trials: **34 cod, 11 salmon, four pufferfish, and one tropical fish**, plus the other recorded loot: 50 fish in total.

The audit records no failed activities or overlapping ownership in this replay. Median grant-to-admission time was 207 ms (maximum 626 ms); median release-to-next-grant time was 1,097 ms (maximum 1,232 ms). The duration limit cancelled the final in-progress cast, confirmed hook release, and left no unresolved owner or native work. `replay.jsonl` preserves 3,349 native observations with frames and a completed, untruncated export. The test client was stopped after evidence collection.

This replay verifies the corrected harvest/replant/return path. Its potatoes were ready at startup; the natural wake and its measured delay belong to `live-06`, whose crop approach failed. The separate simulation exercises repeated readiness cycles, and no full live repetition or throughput comparison is claimed.

## Limits and next design questions

The protocol now has concrete seams for code-defined behaviors, suspended waits, scoped effects, one player owner, and confirmed release. The persistent wrapper addresses an observed control-transport bottleneck. This does not establish optimal scheduling, exact physical utilization, inventory sustainability, or recovery after a hard host crash.

At the end of the crop/fishing slice, sheep, birch, compost, LLM workers, and durable running instances were still unimplemented. The mixed-duty extension below adds stock floors and the homestead behaviors; tool replenishment, worker contracts, and native lease expiry remain open. The current cast is allowed to finish before ready land work takes over; finer interruption policy remains a measured design question.

## Automated checks

Seven host tests cover guest computation/allocation/output bounds, capability isolation, repeated farm/fishing handoffs, stale observations, and failure to confirm native release. Sixty-six distinct focused native tests passed for fishing, dispatch, crop passes and geometry, active jobs, and tool schemas, including both cancellation/session-pause regressions and the recorded crop approach case. Seventy-seven distinct wrapper tests passed across the focused CLI and stream runs, including compact loaded-world status. `git diff --check` passes.

Raw trial directories are local ignored artifacts. `node src/audit.mjs artifacts/<run>` writes a reproducible protocol audit beside each trace. These trials do not complete the full Airicraft OS or its reference evaluation.

## Mixed homestead extension

The next implementation adds scoped sheep, birch, compost, and output-storage behaviors alongside the three crop instances and fishing. It adds consumer-owned stock floors, temporary claims, bounded client-visible worksite observations, and postconditions on sheep containment and gate closure. Host definition source remains persistent; running continuations do not.

Preparation in the same world withdrew the supplied iron axe, two shears, and eight birch saplings, verifying the settled chest contents. Two verified grass plots at (-314,64,-388) and (-314,64,-396) are managed birch roots. The pen interior is X -295..-289, Z -386..-382 at Y64, with gates at (-292,64,-381) and (-292,64,-387). The composter is (-300,64,-383), and the output chest is (-299,64,-382).

`mixed-02` completed storage, two sheep-shearing visits, a pass on each of the three crops, two verified birch plantings, and a fishing activity in 1,646 advancing ticks before diagnostic interruption. There were no recorded overlapping grants or native admissions. Actual inventory contained three brown wool and six remaining birch saplings. The crop observations showed nine growing wheat, 22 potatoes, and 15 carrots after those passes. This was **not** a successful containment trial: a sheep escaped during gate use.

Subsequent recovery replays exposed several distinct contract gaps:

- An exit opened the gate before walking over from the back of the pen. Opening now requires an adjacent player, and exit navigation approaches the closed gate first.
- Recovering one sheep could tempt another through the open gate. Recovery includes the other managed sheep when possible, and ordinary visits put wheat away before exiting.
- The pathfinder opened the second gate. Cleanup now reconciles every fence gate on the pen boundary, including unconfigured entrances, and the adapter checks closure before releasing the visit.
- A two-row lure destination was too small for the followers' stopping distance. Recovery uses the full pen interior; a separate gather step moves the herd away from the entrance before exit.
- Escaped sheep wandered beyond the regional scan. Optional known UUIDs can now be located among client-loaded entities within 128 blocks, with an approach before the native lure action's 32-block admission limit. Unobserved animals are not assumed contained.
- A stop request could race required exit cleanup and cancel it again. Cleanup now has a separate bounded execution scope while the scheduler remains stopped; a regression covers that race.

`mixed-03` demonstrated a successful single-animal lure, but later lost containment. `mixed-04` included a failed narrow-destination lure and an interrupted exit with unconfirmed cleanup. `mixed-05` exposed the regional observation limitation. Their local exports preserve the failure evidence; these attempts must not be described as an uninterrupted successful mixed run.

A later client restart crashed in JourneyMap's `JmUI.getQuarterMaxScale`, which dereferenced a null window monitor. The crash reproduced before host gameplay began and left its JVM stuck during shutdown. A save copy was retained before terminating the task-owned crashed process. The compatibility module now returns JourneyMap's ordinary scale of 1 when the monitor is unavailable; integrations remain enabled. This correction is separate from behavior scheduling.

`mixed-07` ran for 6,205 advancing world ticks on the restarted client. It recovered both original escaped sheep, completed three shearing visits and one feeding visit, and observed the herd grow from two to three. All five visit postconditions observed closed gates and no sheep outside the configured pen bounds. It also completed two potato passes, one carrot pass, and five fishing activities. A separate potato approach failed before harvesting; its later retry succeeded. No overlapping ownership was recorded, and interruption left no unresolved owner or native work. The native replay contains 9,279 observations with frames and reports no truncation.

Containment is not established over the whole run: the new sheep was subsequently flagged outside the configured bounds, leaving recovery ready when the host stopped. A later read-only final observation found all three sheep inside those bounds and both gates closed. Whether the intervening detection represents an escape or a pen-geometry issue remains unresolved. The two birch saplings were still waiting for growth during the run; live birch harvest/replant, compost production, and surplus culling remain unverified.

The user identified a scheduling defect visible in this replay: every individual shearing or feeding action owns a complete pen entry and exit. Serial action ownership prevents overlap but does not amortize travel and gate use. The next design discussion concerns exposing ready work and shared access contexts so the runtime can serve several independent actions during one bounded pen visit. That scheduling change is not implemented in this trial. The host and task-owned client were stopped after preserving evidence.

The mixed extension passed 23 host tests, 62 distinct focused native tests, and 20 JourneyMap/location compatibility tests. These checks cover policy, ownership, cleanup, observation bounds, and the compatibility change; they do not replace the missing live outcomes above.

## Shared pen contexts

The next experiment replaces the combined sheep generator with independent shearing, breeding, and sheep-care rules. Each publishes a bounded set of eligible operations. The runtime deduplicates identical requests, acquires their shared `pen:sheep` context, and selects operations across behavior definitions without exiting between them. The context provider owns entry, gate reconciliation, and exit. Scheduling uses base priority, one aging point per 15 seconds, and a three-point context-reuse preference. Visits have an eight-operation/60-second budget checked between operations; cleanup remains mandatory when another task wins or the host stops.

`context-live-01` exercised the actual definitions in the prepared world for 2,413 advancing ticks before manual interruption. Its first visit served six operations from **three independent behaviors**: three adult shears, wool collection, feeding a pair, and another shear after one adult's wool regrew. The visit entered once and exited once. At exit the herd had grown from three to four, all observed inside the pen, with both gates closed. Wheat and potato passes then completed, followed by fishing.

A second visit served two shears and wool collection before releasing the player for more crop work. Across the replay, nine completed sheep operations used two pen entries and two exits. The audit recorded six successful shears, two collections, one feeding operation, a wheat pass, a potato pass, and one fishing activity, with no failed completed activities or overlapping ownership. The final crop pass was interrupted; its exact native job was reconciled before shutdown. No owner, native work, or context remained unresolved. The replay export contains 3,229 observations with frames and no truncation. These are observed visit counts, not a controlled throughput comparison against the prior implementation.

`context-stop-01` specifically tested stopping a retained context. A local parent process watched the host snapshot and sent SIGINT at 11:22:33.061 UTC while the pen context was active and shearing owned the player. This happened before a sheep interaction was admitted, so it verifies cleanup of an active visit, not mid-interaction native cancellation. Cleanup exited the pen, verified four sheep inside and both gates closed, and released the context at 11:22:40.988 UTC. The host exited successfully. The audit found one entry, one exit, no overlap, and no unresolved work or context. Its untruncated export contains 3,838 observations with frames; the rolling recording includes earlier retained observations. A later direct observation again found four contained sheep, both gates closed, and the player outside. The task-owned client was then stopped.

Thirty-seven distinct host tests passed. New coverage includes independent behaviors sharing a visit, duplicate subscribers receiving one outcome, breeding-pair identity independent of ordering, capability/selection validation, disappearing targets after entry, urgent work, visit budgets, aging of waiting work, retry after an operation failure, rejected cleanup retaining ownership, and interruption during an operation through the context provider. After the live replays, a missing-gate guard and regression were added so a destroyed entrance cannot be mistaken for a secured pen. The crop/fishing simulation also passed its ownership audit. No native Java changes were needed for this scheduling step.

Both live trials used the localhost control bridge with the singleplayer world already ticking. Automatic approval review rejected opening the world to LAN; no LAN opening or substitute network exposure was performed.

The result supports explicit work requests and reusable contexts as a useful composition contract. Only the sheep operations currently share a context; generalized route optimization, other context providers, native hard-stop/lease expiry, durable queues, worker calls, and sustained production evaluation remain future work. Gate and containment observations in these replays do not establish that every possible route or interruption is safe.

## Shared chest contexts

`work-contexts/v3` adds `chest:home` as a second provider for the same scheduler. Independent output-storage, farm-supply, and sheep-supply definitions offer work without calling one another or opening/closing the chest themselves. Restock requests name an item; the host derives a fresh deficit from the sum of configured consumer floors. Duplicate goals share that request rather than increasing its quantity. Deposits use the same floors and temporary claims. Every operation is bounded to 64 items and rechecks the current window, inventory, and source stock.

The native worksite observation now exposes screen-handler kind, sync ID, and cursor state. `close_container` accepts an optional exact window ID guard. The provider verifies both sides of a transfer twice after submission and confirms the GUI is closed before releasing the context. Empty sources and full destinations defer a request while healthy compatible work continues. A relevant resource change wakes deferred work; otherwise a 1,200-world-tick retry bounds probing of unavailable stock. The final host refinement removes the generic failure cooldown from these provider-owned deferrals, so freeing a slot can immediately unblock a withdrawal in the same visit.

The live fixture used existing items only: eight potatoes and three wheat were moved into the home chest, and sixteen stored cod were withdrawn to make depositing ready. No items were spawned and no crop growth was accelerated. `chest-fixture-preparation.json` records those ordinary verified transfers separately from the trial.

`chest-live-01` ran for 2,599 advancing world ticks before diagnostic interruption. Its first chest visit served **five requests from three independent definitions** with one open and one close:

| Request | Observed result |
| --- | --- |
| Store cod | Deposited 23; carried 23 → 0, stored 18 → 41. |
| Restock wheat seeds | Deferred: no source stock. |
| Restock potatoes | Withdrew 2; carried 6 → 8, stored 8 → 6. |
| Restock carrots | Deferred: no source stock. |
| Restock sheep wheat | Withdrew 2; carried 0 → 2, stored 3 → 1. |

The retained visit ran from 12:51:52.022 to 12:52:08.397 UTC on 2026-09-15. The GUI was confirmed closed before acquiring the pen context. The next visit served three shears, one pair feeding, and wool collection; each of the three crop passes also completed. One later sheep recovery failed, and a subsequent recovery was interrupted and reconciled. That gameplay containment/recovery issue is a separate scope, not the next OS roadmap milestone. This replay establishes shared chest service and context handoffs, not sustained homestead efficiency. Its audit reports no overlapping ownership, unresolved native work, retained context, or open chest window.

Before a second run, a live `close_container` call deliberately supplied the wrong sync ID. The native guard rejected it with `container_changed`; inspection confirmed the original window remained open. Preparation then withdrew sixteen cod to make output work ready. In `chest-stop-01`, a parent process sent SIGINT at 12:56:17.032 UTC while the chest context was active and storage owned the player. Stop occurred before transfer submission. Cleanup verified the owned chest closed at 12:56:18.085 UTC, and the host exited successfully with no unresolved owner, native action, or context. This proves interruption of an active chest visit; the separate host test covers stopping after a transfer has been submitted and waiting for its verification before closure.

The two replay exports contain 3,554 and 4,224 native observations with frames and completed, untruncated export markers; the later export also includes earlier retained observations. The final host revision passed all **50 host tests**, including duplicate demands, additive consumer floors, full/empty storage, urgent restocking unblocked by a deposit, native preflight refusal, stale source/inventory/window observations, and interruption/uncertain-transfer cleanup. Twelve focused native tests passed for container transfer planning and tool contracts. The crop/fishing simulation and both live protocol audits passed. Both hosts and the task-owned client were stopped after saving evidence.

This completes the second-context experiment. It provides evidence for the open behavior-composition and resource-contract tickets; it does not resolve parent/child skill ownership, structured cancellation, durable continuations, worker calls, or the full reference evaluation.
