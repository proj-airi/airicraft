# Place memory

The planner chooses what a destination means. Built-in place memory stores named coordinates and optional purpose notes; navigation executes the chosen destination. No map mod is required.

These tools are part of the planner tool catalog. The external Codex driver can call them directly through `agent tools call`.

```text
remember_place {"name":"cave entrance","position":"current","note":"Return here after gathering iron"}
remember_place {"name":"home","position":{"x":120,"y":70,"z":-30},"note":"Bed and supplies"}
recall_place {"name":"cave entrance"}
list_places {}
forget_place {"name":"old camp"}
```

Omitting `position` captures the current block position. Explicit coordinates require all of `x`, `y`, and `z`; their optional `dimension` defaults to the current dimension. Names are trimmed, case sensitive and unique within the world. Remembering an existing name replaces its coordinates and note; an omitted note becomes empty. Names allow 128 characters, notes 2,048 characters, and each world allows 256 places.

A recall result includes the saved dimension and exact coordinates plus the current dimension. The planner can pass the coordinates to `navigate_to` with `exactY=true` after checking the dimensions match. Navigation does not automatically traverse portals. Remembered places do not count as fresh block observations for interaction tools, and a bookmark does not establish safety or reachability. Navigation failure requires observation and replanning, not substitution of a different destination.

Places are stored immediately in `<world save>/airicraft/places.json`, using versioned JSON and atomic file replacement. Every operation reads that world's file; there is no planner-owned cache to lose on reload, compaction, or world switching. The file travels with copies and backups of the world save. Corrupt/unsupported data and write failures return errors rather than discarding the previous memory. Remember/forget calls serialize on the client executor and are excluded from read-only tool batches; they do not acquire or replace gameplay task ownership.

This first implementation supports locally hosted saves, including integrated-server LAN hosts. Remote multiplayer clients report `world_persistence_unavailable`; server-address-based storage would not reliably identify the remote world. Without a loaded world, calls report `world_not_loaded`.

The legacy `return_to_surface` tool and its executor remain for existing callers in this first pass. Planner guidance uses explicit remembered destinations for contextual travel. This change does not repair that legacy tool's ravine-floor selection or claim that the current paused encounter has been escaped.

## Validation — 2026-09-10

Full build passed: root 1,032 tests, zero failures/errors, two skipped; wrapper 92 tests, zero failures/errors. New regressions cover persistence after recreating memory/providers, replacement/deletion, separate world saves, dimension changes, invalid coordinates, corrupt data preservation, tool discovery without map integration and write exclusion from read batches.

HotSwap plus a targeted replacement of the live tool registry installed the provider without resetting the runtime or restarting the client. Through the normal driver tools, saved `ravine incident` from the current position `(199,30,29)` and `wood tree approach` from previously observed coordinates `(284,64,-141)`. Verified both in `run/saves/driver/airicraft/places.json`; recreated the provider and recalled the same data. A temporary probe bookmark was created and deleted through the tools. Server tick 9797, agent tick 9980 and event sequence 233 remained frozen; the original combat hold remained intact. Actual navigation to a recalled destination and a full client restart/world rejoin were not exercised during this paused incident.
