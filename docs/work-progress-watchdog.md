# Advisory work progress observer

The runtime samples foreground running leaf work once per client tick. After 200
eligible ticks without observed progress (about ten seconds at 20 TPS), it emits
`task.notice` with `reason=work_stalled`, the work ID, position, stalled tick count,
and whether input occurred. The existing task-attention route wakes the planner
even while accepted work is running. A busy planner receives the notice after its
current request. The observer never cancels, fails, pauses, or replaces work.

The planner can inspect the work and world, recover with its existing controls,
or use `continue` to grant another full observation window. Each stall episode
produces one notice; real progress rearms it. `continue` keeps the observation
history, so repeating the same movement or partial block damage cannot immediately
masquerade as fresh progress.

Progress means visiting a new rounded block position, increasing block damage,
increasing carried item counts or collected count, or reducing a targeted living
entity's health. Input alone, changing aim, executor messages, and updated ticks
do not count. Each work item retains up to 256 recent positions and 256 metric
high-water marks. This is a bounded heuristic: novel wandering can look productive,
and a large loop beyond the retained positions may evade detection. Notices ask
for inspection, not an assumption that the task failed.

Queued/waiting/background work, user questions, satisfied following, session or
reflex pauses, and a paused client do not spend the budget. A graph with a live
child is observed through that child to avoid duplicate notices. Finished or
removed work is forgotten; a world change clears the observer. The observer has
no planner-history or death-reset behavior. Notices continue to use the existing
tool-result event path.

Regression tests cover inactive aiming, oscillation despite input, advancing vs
repeated mining damage, paused time, notification deduplication, continue grace,
rearming after progress, leaf selection, and planner attention during accepted work.
