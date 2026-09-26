# Hosted playtests

A hosted playtest lets human testers play alongside the companion while Airicraft records the whole session. The companion's client hosts a fresh copy of a template world and opens it to LAN on one fixed port. Testers join it like any LAN world, directly or through a forwarded port. The recording uses the [automatic playtest](automatic-playtest.md) pipeline: flight records, live FPV video, Recorder Plays and a world save. It adds a Recorder Play for each tester connection plus a join/leave log.

The companion hosts the world because many agent capabilities need the integrated server: recipe and block-drop knowledge, mob-aggro detection, saved planner goals, the logbook and server-tick-aligned recording. A companion that joined a dedicated server as a client would be weaker than the one you evaluate.

## Run a session

```sh
scripts/hosted-playtest \
  --world 'run/saves/Playtest Template' \
  --recorder-jar /absolute/path/to/recorder-profile.jar \
  --companion-name Airi \
  --lan-port 25565
```

Prerequisites are the same as for automatic playtests: JDK 25+ to run Gradle (the build provisions its JBR 21 toolchain), initialized submodules, `ffmpeg`/`ffprobe` on `PATH`, and a prebuilt recording profile (`--recorder-jar` or `AIRICRAFT_RECORDER_JAR`). The launcher copies the template world, starts the compatibility client with JDWP disabled and joins the copy. When the world is open on the requested port, it prints:

```text
Airi is hosting on port 25565.
  Same network: Multiplayer lists the world under LAN, or Direct Connect to 192.168.1.20:25565
  Internet: forward TCP 25565 to this machine (or tunnel it), then share <public address>:25565
```

Each session opens only the requested port, so the forwarding rule stays valid. If that port is in use, or the world opens on another port, the run stops with `lan_unavailable` before anyone is told to join. For internet testers, forward the TCP port on your router or use a TCP tunnel. Testers need a Minecraft: Java Edition 1.21.8 client. Airicraft opens LAN worlds in offline mode (see [LAN hosting](../README.md#lan-hosting)), so anyone who can reach the port can join under any name. Share a forwarded address only with invited testers, and end the session when they are done.

`--companion-name` sets the companion's in-game name: 3–16 letters, digits or underscores, default `Airi`. It is an offline development profile, not a Minecraft account.

## When a session ends

| `terminationReason` | Cause |
| --- | --- |
| `testers_left` | Every tester has been gone for `--leave-grace` seconds (default 120). A rejoin within the grace keeps the session. |
| `no_tester_joined` | Nobody joined within `--join-timeout` seconds of the port opening (default 1800; `0` waits forever). |
| `time_limit` | `--max-seconds` elapsed after the world was joined (default 14400; `0` means no limit). |
| `manual_interrupt` | Ctrl-C or SIGTERM. |
| `lan_unavailable` | The fixed port did not open within 60 seconds, or opened on a different port. |
| `startup_failed` | The world did not start recording within `--startup-timeout` seconds of joining. |
| `capture_error` | Recording failed, the bridge returned an unexpected error, or the client is not in hosted mode because its Airicraft build predates the launcher. |
| `crash`, `runtime_fatal_error`, `bridge_unresponsive`, `world_left`, `client_exit` | Same meaning as for automatic playtests. |

Every stop goes through the automatic playtest save-and-stop, Recorder Play finalization and publication. A stopped launcher's `.in-progress` run is archived with `scripts/hosted-playtest --recover hosted_playtest/.in-progress/<run-id>`.

## Planner recovery during a session

Three consecutive planner failures put the companion in degraded mode. Automatic playtests stop on degradation; hosted sessions keep going. After the planner stays degraded for `--reset-degraded-after` seconds (default 20), the launcher sends `@agent reset` as the same-client operator. It makes at most one automatic reset attempt per hosted session. If the planner degrades again, the launcher logs `planner_reset_exhausted` and sends no further reset. The companion tells testers when an automatic reset is pending or has already been used, without asking them to send an operator command. `planner_recovered` means the degraded state cleared after reset; it does not establish that the provider is healthy. Use `--reset-degraded-after 0` to record degradation without automatic reset; in that mode the companion gives the usual manual-reset guidance.

Hosted mode does not offer the planner `something_wrong`: a report would pause the integrated server and freeze everyone's game.

## What is recorded

Everything an automatic playtest records is recorded for the companion (see [evidence for offline analysis](automatic-playtest.md#evidence-for-offline-analysis)). In addition:

| File | Contents |
| --- | --- |
| `players.jsonl` | Tester `join`/`leave` records with UUID, name, observed server tick, wall time and connected-tester count. Connections are sampled once per second from the integrated server; `session_ended` closes intervals still open at the end. The companion is never listed. |
| `launcher-events.jsonl` | `lan_opened`, planner degradation/reset/recovery/exhaustion and `session_ended` with its termination reason. |
| Tester Recorder Plays | The recording profile records every connection. Each tester connection, including reconnects, is its own Play beside the companion's. |

The `airicraft.playtest` extension belongs to the companion's Play, identified by the `playerUuid` in `recording-start.json`. Its `playtest.json` has these hosted-session fields:

- `environment.mode`: `hosted` or `automatic`.
- `environment.companion`: the companion's player UUID and name.
- `participants`: one entry per other Play, with its path relative to the output root, player UUID/name, connection ID, server-tick bounds, and whether it finalized.

Tester Plays stay unmodified and have no Airicraft extension. The recording profile ignores chat packets, so tester chat reaches the dataset through the companion's event stream (`social.player_spoke` in the flight records).

Sessions are written under `hosted_playtest/` (git-ignored). Workers live under `run/hosted-playtest-workers/<run-id>/`.

## Consent and privacy

A session records testers' usernames, UUIDs, chat, movement, and the companion's first-person video. In offline mode, names are self-reported and UUIDs are derived from them. A name maps to the same UUID across sessions but does not prove who the tester is. Tell testers before they join, get their consent, including for any future training use, and keep recordings out of the repository. Pseudonymizing exports is not implemented yet.

## Verification status

The launcher, the tester presence and planner-recovery rules, multi-Play publication and the Java participant bookkeeping have unit tests (`scripts/tests/test_hosted_playtest.py`, `scripts/tests/test_automatic_playtest.py`, `HostedPlaytestParticipantsTest`, `LanPortScanTest`). A local protocol tester joined a live hosted session with controlled provider failures: the launcher sent one reset, reported `planner_reset_exhausted` after renewed degradation, and sent no second reset before the session ended. The tester received the matching chat guidance. A session through a forwarded port has not been run yet. Before inviting remote testers, check that:

- a tester joins through the forwarded port;
- `players.jsonl` records the join and leave;
- `playtest.json` lists the tester's Play under `participants`;
- the published run reports `recordingComplete: true`.
