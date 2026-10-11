# Episodic memory from the flight recorder (experiment)

Idea (from Rin, prompted by the "optchat" append-only chat design): use the recorder's raw stream as the
memory, and have a separate pass turn it into semantically organised episodes the planner can recall later.
This is an offline experiment; nothing is wired into the planner.

## Pieces

- `EpisodeStream` (opt in with `-Dairicraft.episodeStream=true`): append-only `airicraft/episodes/stream.jsonl` in the
  world save. Semantic events and debug-timeline entries only (no frames, snapshots or LLM envelopes), so it outlives the
  12,000-tick rolling window.
- `scripts/episoder.py RUN_DIR`: reads `events.jsonl` + `debug-timeline.jsonl` (a flight-recorder or evaluation
  directory) or a world's `stream.jsonl`. It drops noise, collapses per-tick pings, redacts dashboard tokens, cuts segments
  at goal/task/death/world boundaries, long gaps and a size cap, then asks an LLM for one episode per segment:
  title, attempted, outcome, why, facts, inferred, people_said, open_loops, plus the raw tick range and source line
  pointers. The prompt separates observed `facts` from `inferred`.

## First run

`iron-pickaxe` scenario, NIM `z-ai/glm-5.3-flash`, reasoning effort low, about 52,000 ticks (the run was stopped before the
scenario budget ended). 718 events and 5,034 timeline entries became 37 episodes, 10 of which first came back as
`unsummarized` because NIM returned `502 upstream request failed`; retries with backoff and `--retry-failed` fixed that.

Observations:

- Failure reasons are kept exactly (`missing_required_harvest_tool`, `acquisition_budget_exhausted`, `crafting_table_missing_materials`).
- Inference stays out of `facts` in the episodes read; it appears under `inferred` and is usually reasonable but sometimes speculative
  (for example intent behind a navigate).
- Episode cuts follow task boundaries, so a long tool-less stretch can become one coarse episode.
- The run itself shows what such memory is for: the stone pickaxe craft failed with `crafting_table_missing_materials` at least 3 times,
  at ticks about 33,000, 44,000 and 46,000.

Not yet measured: whether a planner using these episodes repeats fewer mistakes, and how many tokens an index costs.
Samples: `/mnt/project-files/episodic-memory/` (episodes plus the raw inputs).
