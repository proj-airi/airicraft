# Planner-selected report checkpoints (experiment)

Branch `codex/planner-report-checkpoints` changes routine planner scheduling from automatic coalesced reviews to explicit queued checkpoints. User messages and urgent safety interruptions remain enabled. The original async-FIFO playtest remains on its original branch/build.

Calls append to a role-owned FIFO and execute sequentially without waiting for inference. Ongoing work must actually finish before the next call starts. `continue` adds no work and ends the decision turn. `clear_queue` immediately discards pending calls and aborts active foreground work; replacement calls wait for abort completion. Completed physical effects are not undone.

Routine intermediate results accumulate without launching the planner. A review starts when:

1. A queued `report_to_me` call executes; or
2. The FIFO becomes empty with new results to deliver.

For example, the planner can queue `inspect_inventory`, `inspect_world`, `report_to_me({"question":"Is the site suitable?","includeTools":["inspect_world"]})`, then a previously justified long-running action. At the checkpoint, the long-running action starts independently and the planner receives the selected observation and question. The checkpoint does not pause execution. Plans that depend on an answer not yet known should end at the checkpoint instead.

`report_to_me` accepts optional arguments:

- `question`: what to assess or decide at this checkpoint.
- `includeTools`: names of tools whose buffered raw outputs to include. Omit for all outputs; an empty list requests outcome acknowledgments and fresh decision context without buffered raw outputs.

Unselected outputs are explicitly marked omitted, not summarized as success. Full outputs remain in recording evidence. Output selection does not strip the fresh decision context, including current work and safety state. FIFO-empty reviews with no explicit checkpoint include all buffered outputs. Multiple checkpoints reached while inference is busy merge into the next review; output selections are combined, and any checkpoint requesting all outputs takes precedence.

Every submitted call retains a valid tool-call/result pair. Queued acknowledgments become execution results, or explicit omitted-output markers, when a review is constructed. `TOOL QUEUE` shows current active and pending calls, which may advance while the planner thinks. Queue state and buffered results are session-local.

Short-term findings apply only to delivered raw observations. A findings-only reply can commit summaries incrementally; the next empty-FIFO review identifies the next unsummarized query. A reply adding new gameplay work must summarize remaining delivered observations first. This fixes the original async-FIFO run's all-or-nothing validation loop, where a valid south-forest finding was rejected because east/west observations remained.

## Next playtest

Use the saved checkpoint from run `20260920-225411-723089-16816-54fc7654-ad27-403a-a45b-003fb4b6ae48` after explicitly resuming testing. That run ended in `planner_degraded`; its recording and world checkpoint finalized successfully. Do not label it a successful async-FIFO gameplay test.

Keep the original run artifacts unchanged. Compare review triggers, calls per batch, time without active work, rejection counts, and actual survival progress. The current branch has automated verification only until a new live run is launched.
