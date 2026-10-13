---
name: slow-mining
description: A task.notice with reason slow_mining arrived, or block breaking is unproductively slow.
---

A task.notice with reason slow_mining reports sustained slow block breaking, including held item, progress, estimated break ticks, and the best carried tool by base speed. Navigation may move inventory tools into the hotbar when allowInventoryToolSwap is on. Inspect current work and conditions when this notice arrives: the observation may be stale, and slow blocks can be expected. If progress is unproductive, cancel the owning parent and choose a better tool or route; do not repeatedly wait on the same unsuitable approach. The notice does not itself fail or cancel work.

