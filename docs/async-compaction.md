# Asynchronous context compaction

Full compaction summarizes the planner's history into a checkpoint. It used to run in front of the next turn, so the
planner sat idle for the length of one model call (tens of seconds, two minutes on a timeout). It now runs beside the
planner.

## Behavior

1. Once the last observed prompt reaches 75% of `plannerCompactionTriggerTokens`, `PlannerOrchestrator.poll()`
   starts a compaction. The threshold is early on purpose: the checkpoint should land before the budget does.
2. `PlannerContextAggregator.beginCompaction()` freezes what is being summarized. Suppose the history is A, B, C.
   The compaction request holds A, B, C and a `CompactionRequest.Cut` naming the end of C.
3. The planner keeps working. New events produce D, and the next turn is built from A, B, C, D as usual.
4. When the summary arrives, `applyCheckpoint(checkpoint, cut)` replaces A, B, C with the checkpoint and keeps D.

The cut is a prefix in which no tool call is separated from its result, so the summary never splits an exchange. A
tool call still waiting for its result stays in the kept part.

## How the summary is applied

The retained wire conversation is written from many places, and a turn in flight holds its own copy of it. The
aggregator therefore keeps each finished compaction as a rewrite (the last covered message, and the checkpoint message
that replaces everything up to it). `retainConversation` and the tool follow-up base apply the rewrites to any
conversation that still contains a replaced prefix. A rewrite is a no-op on a conversation that already carries its
checkpoint. This is the same idea as `PlannerMicroCompactor.update`, which rewrites individual observations without
gating the planner.

The anchor is a tool result matched by call id (micro-compaction keeps ids while it rewrites content), or a message
matched by identity. If the anchor cannot be found the rewrite does nothing; nothing is dropped.

Clients that rebuild history from the accepted tape (no frozen tool prefix) record the tape length in the cut and drop
that many entries when the summary lands.

## After the summary lands

Observations kept after the cut are deltas against state that only the replaced part described, so the orchestrator
forces the next observation to be a full one (`decisionRefreshPending`).

## Failures

A failed compaction never blocks planning. Retryable failures (timeouts, 429, 5xx) are retried on the next trigger,
not on every poll. A permanent HTTP 400/401/403/404/405/413/415/422 stays failed until an explicit debug compaction or
a reset. Before this change that state also stopped the planner; now the context keeps growing until the provider's own
limit produces ordinary planner failures, so the failure is still visible.

## Known limits

- The token count of a model call that was already in flight when the summary landed describes the old prefix. It can
  start one more compaction, which then summarizes an already-compacted conversation.
- Compaction does not run for provider-managed history (Codex app server).
