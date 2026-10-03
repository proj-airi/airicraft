# AIRI link

The AIRI link connects the mod to the AIRI desktop app. AIRI is the self of the character, and the mod is its body in Minecraft. [ADR 0005](adr/0005-link-to-airi-as-the-body.md) gives the design.

## Status

The link connects, authenticates, announces the module `airicraft`, sends heartbeats and reconnects. The planner acts on AIRI commands. The mod sends body status, chat, alarms and command progress to AIRI.

The link does not yet read the character card from `module:configure` or send `output:speech`. Those parts come in later changes.

## Turn it on

1. In AIRI, open Settings > Connection and set the channel server token.
2. In `config/airicraft/airicraft.yml`, set the `airi` block:

   ```yaml
   airi:
     enabled: true
     url: ws://127.0.0.1:6121/ws
     token: "<the same token>"
   ```

3. Run `/airicraft reload`.
4. Run `/airicraft airi` to see the link state.

The link does not connect without a token. AIRI's channel server accepts any local client when its token is empty.

## States

| State | Meaning |
| --- | --- |
| `disabled` | `airi.enabled` is false. |
| `missing_token` | The link is enabled, but `airi.token` is empty. |
| `connecting`, `authenticating`, `announcing` | The handshake is in progress. |
| `ready` | AIRI lists the module `airicraft`. |
| `waiting_to_reconnect` | The last attempt failed. The link tries again after 1 to 30 seconds. `last error` gives the cause. |

## Commands from AIRI

AIRI sends `spark:command` to the module `airicraft`. The `destinations` list must name `airicraft`. The channel server delivers an event with an empty list to nobody.

The mod turns the intent, the guidance options, the steps and the contexts into one text of at most 1200 characters. The planner gets that text as direct guidance from the speaker `AIRI (your own self outside the game)`. Safety holds still come first. The planner prompt tells the planner not to answer AIRI in game chat.

The mod answers each command with `spark:emit`:

| State | When |
| --- | --- |
| `queued` | The planner has the command. |
| `working` | The first top-level work after the command started. |
| `done` | That work succeeded. |
| `blocked` | That work failed. The note gives the failure. |
| `dropped` | The planner is off, no world is loaded, the work was cancelled, a newer command replaced it, or the mod reloaded. |

Only one command is open at a time. A command that the planner answers without work stays `queued`.

## Reports to AIRI

| Event | Lane or kind | Content |
| --- | --- | --- |
| `context:update` | `status`, replace | World, position, health, food and the current work. Sent on change, at most every 5 seconds, and again after 60 quiet seconds. |
| `context:update` | `chat`, append | Each chat line from a player and each line that the body says. |
| `spark:notify` | `alarm` | The body died, the body took damage (at most every 20 seconds), or top-level work failed without a command. |

`spark:notify` and `spark:emit` go to `proj-airi:stage-*`, the same destination as the Mineflayer bot. `context:update` has no destination, so every module gets it.

## Wire format

The link writes plain JSON events. It reads plain JSON and the SuperJSON wrapper `{"json": event}` that the AIRI server writes. Code is in `src/client/java/ai/moeru/airicraft/airi/`.
