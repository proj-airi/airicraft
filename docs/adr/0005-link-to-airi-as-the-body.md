# Link to AIRI as the body

Status: accepted (2026-10-03). Partly implemented. [AIRI link](../airi-link.md) gives the current state.

## Context

AIRI is a desktop companion with a character, a voice, an avatar and its own chat model. Airicraft is a complete companion agent inside Minecraft. Users want one character that lives in both places.

Earlier experiments joined the two in other ways. One sent each `say` line to AIRI as speech and left AIRI with no agency. One put an external persona runtime between AIRI and the mod and made AIRI's chat model drive about 70 mod tools. That runtime opened an unauthenticated WebSocket server. Neither shape is kept.

AIRI already has a protocol for sub-agents on its channel server. A sub-agent sends `context:update` and `spark:notify`. The character answers with `spark:command`. Progress goes back as `spark:emit`. AIRI's Mineflayer bot uses this protocol today, and its README marks it for replacement by a Fabric mod.

## Decision

The mod has two tracks:

- **Standalone.** The built-in planner is the brain. It plays the character from `character.json`, talks in game chat and decides all work. This is the behavior before the link. It is the default, and it needs no AIRI.
- **Linked.** AIRI is the self, and the mod is the body. The rest of this decision describes this track.

The `airi.enabled` setting selects the track. The standalone track must keep working without AIRI. With the link off, the link code opens no connection and does no work on the client tick. Each track has its own planner system prompt. The linked prompt is the standalone prompt plus an AIRI body section from `prompts/planner-airi-body.md`. The section tells the planner that AIRI is its self and how to treat AIRI commands.

In the linked track, AIRI is the self. Airicraft is the body.

| Concern | Owner |
| --- | --- |
| Movement, work, combat and every game tool call | Airicraft planner and reflexes |
| Chat with players in the game | Airicraft dialogue |
| Talk with the user outside the game | AIRI character |
| The voice for lines that the body says in the game | AIRI speech |
| Intentions such as "come back" or "build a house" | AIRI decides, airicraft executes |
| Alarms and milestones | Airicraft reports, AIRI reacts |
| The character card | AIRI, copied to the mod |

The mod gets an AIRI link. The link is a WebSocket client inside the mod. It connects to the AIRI channel server as the module `airicraft`. It authenticates, announces itself, sends heartbeats and reconnects with backoff. It uses only the wire format. It has no dependency on AIRI packages.

| Event | Direction | Content |
| --- | --- | --- |
| `context:update` | mod to AIRI | Short status text in lanes: `status`, `chat` and `goal`. Sent on change, with a minimum interval per lane. |
| `spark:notify` | mod to AIRI | Alarms and milestones from an allow-list of agent events. |
| `spark:emit` | mod to AIRI | `working`, `done`, `blocked` or `dropped` for each command id. |
| `spark:command` | AIRI to mod | Intent from the character. It enters the planner as guidance, below safety holds and above player requests. |
| `module:configure` | AIRI to mod | The active character card. While the link is up, it replaces `character.json`. |
| `output:speech` | mod to AIRI | One line that the body said in the game. AIRI speaks it with the character voice. This event is new in AIRI. |

Rules:

- The link is off by default. The `airi` block in the mod config turns it on and holds the URL and the token.
- The link does not connect without a token. AIRI's channel server accepts an empty token by default.
- A command is gameplay input, not code. It never reaches the bridge debug routes.
- No API key crosses the link. Each side keeps its own model provider.
- Pushed cards obey the same field limits as `character.json`.
- The HTTP bridge stays a debug and CLI surface. AIRI does not use it.

## Consequences

- The mod keeps its planner, reflexes and wake rules. AIRI gets real agency over the body.
- There is no new process and no new listening port.
- The mod must follow AIRI protocol changes for six events and the module lifecycle. A contract test with JSON fixtures detects drift.
- AIRI must accept `airicraft` where it accepts `minecraft-bot`. The Mineflayer bot is removed in a later AIRI change.
- Two models run at the same time: the mod planner and the AIRI chat model.
