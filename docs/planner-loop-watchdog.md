# Planner loop watchdog

The work progress watchdog (`docs/work-progress-watchdog.md`) watches running work. It cannot see the planner itself
going around in circles while no work runs: the planner queues `inspect_world`, the empty FIFO wakes it for a review, it
queues `inspect_world` again, and so on. Every lap is a paid model turn and nothing changes. Seen live: nemotron looped on
`inspect_world`/`inspect_work` and never built, and the beat-the-game playtest answered `continue` about 50 times to a
stuck furnace craft. `PlannerLoopWatchdog` (in `agent/llm`, fed by `PlannerOrchestrator`) catches these.

## What it detects

After every accepted planner turn it compares a progress state taken from the decision context: dimension, carried
item counts, block position and the planner goal. Work state is deliberately left out: starting work and cancelling it
again changes work ids and states without getting anywhere, and work that does something changes items or position. The player's guidance resets
everything; a turn whose state differs from the streak's first turn starts a new streak. A reply without tool calls is
not counted: it is the planner waiting for the next event, which is what a loop should turn into.

- **No progress**: within one streak, 3 autonomous turns made the same set of tool calls (`REPEAT_TURNS`), or 8
  autonomous turns made any calls (`STREAK_TURNS`). Arguments are ignored, so `report_to_me` with a different question
  each time still counts.
- **Repeated failure**: one tool failed with the same result 3 times (`REPEAT_FAILURES`), whatever ran between the
  attempts. A failure is a `TOOL_ERROR`/`TOOL_UNAVAILABLE`, a receipt with `accepted: false`, or a work outcome
  `FAILED` or carrying `failure`. Ids, ticks and ages are left out of the comparison; item counts are not, so a smaller
  shortfall is a different failure. Any success of that tool clears its history.
- **Abandoned work**: the planner's `clear_queue` cancelled its own running call of one tool 3 times
  (`REPEAT_ABANDONS`), counted until that tool succeeds. Seen live on the cabin build: glm cancelled a running
  `construct_blueprint` five times and redesigned at new spots instead of resuming.

## What it does

1. **Notice** (`planner.loop_detected`, advisory): the event rides on the next wake as evidence with a one-time
   `observe.hints` entry saying what repeated and that more of the same will not change anything. It never wakes the
   planner by itself. Repeated failures and abandoned work are only ever noticed, every third time.
2. **Hold** (`planner.loop_held`): if a no-progress streak goes on for 3 more turns after its notice
   (`HOLD_AFTER_TURNS`), autonomous wakes are held for 60 s, doubling per hold up to 10 min:
   - tool-queue reviews wait, keeping their results (they run when the hold ends);
   - ordinary task wakes are deferred (gate `G5.planner_loop`);
   - other autonomous triggers are dropped (gate `G8.planner_loop`), as degraded mode drops them, including debounced
     ones that were waiting when the hold began;
   - the player's messages, damage, high or critical urgency, attention and reflex wakes still go through.
3. **Release** (`planner.loop_released`): when the cooldown ends, or progress or guidance ends it early, the event is
   published and queued as a task wake so the planner looks again. A loop that resumes after release is held again,
   twice as long. Real progress resets the doubling. Progress is checked every tick during a hold too, so work that was
   already running and changes inventory, place or goal ends the hold without waiting for a planner turn.

The events are planner-visible and recorded like any other event, so the dashboard and wake ledger show them. An agent
reset clears the watchdog.

## Limits

A planner that moves a block or picks something up every turn is making progress by this measure even if it is not
getting anywhere; the work watchdog's spatial novelty covers running work, not planner turns. Legitimate waiting with
nothing running (a furnace cooking) looks like no progress if the planner keeps waking itself; the hint tells it to
reply without tool calls and wait for the event, and the hold caps the cost if it does not.
