# Episodic memory

The companion remembers what happened between sessions: the lava accident, who gave it bread, what Rin asked it to build. It does not remember plans. Location memory, goals and the interaction logbook cover competence; episodic memory covers the relationship.

## How it works

- **Writing.** When the controller planner's context is about to end, a sidecar sends that same context plus an `EPISODE TASK:` message to the planner model. Compaction, `@agent reset`, reload, world leave and shutdown all end a context. The reply is at most one episode: a title, a summary of at most three sentences, the players involved, tags, up to two verbatim quotes and a salience from 1 to 5. The runtime adds the world day, dimension, position and the reason the stretch ended. Episodes are neutral first-person facts about the past. The prompt forbids intentions and predictions; a request or promise someone made is recorded as a fact.
- **Story boundaries.** Deaths, planner goals set or cleared, and players joining or leaving the game also end a stretch. The cut waits 30 seconds so the planner has taken the event in, and cuts stay at least three minutes apart. Quiet play still becomes an episode after twenty minutes. A cut with nothing new since the last one makes no model call, and the model may answer `{"episode": null}`. Each task lists the five latest episodes so the same moment is not written twice.
- **Consolidation.** After six new episodes, and when leaving the world, a second call folds them into the digest. It adds one chapter of two or three sentences and rewrites the fact list for each player involved: preferences, running jokes, open requests and shared moments, at most eight facts each.
- **Recall.** A new context starts with a MEMORY block. That happens at session start and right after compaction. The block holds facts about players who are here first, the two latest chapters, the three latest episodes and the three most memorable older ones. It is capped at 3,200 characters and fixed for that context, so the prompt prefix stays cacheable. Nothing the sidecar writes enters a live context. The episode task tells the model never to re-record the MEMORY block or the compaction checkpoint.

Memory calls run on their own thread and never block or wake the planner. Failures are logged and the stretch is skipped.

## Files

Per world, next to `places.json` and `interactions.jsonl`:

- `<world>/airicraft/episodes.jsonl`: append-only episodes, one JSON object per line. To make the companion forget, delete lines here and delete `memory.json`.
- `<world>/airicraft/memory.json`: the digest, consolidated from the episodes. Deleting it rebuilds chapters and people from the next consolidation onward.

## Limits

- Needs a locally hosted world save, like the logbook. Hosted playtests work; joining a remote server does not remember.
- Needs the OpenAI-compatible planner backend with client-side history. Codex app-server history lives with the provider, so there is nothing local to read.
- Off during evaluator scenarios. Turn it off anywhere with `-Dairicraft.episodicMemory=false`.
- Quitting the game waits at most three seconds for the last episode to finish writing.
- The planner cannot search memories yet. A `recall_memories` tool for "do you remember…?" questions is planned.
