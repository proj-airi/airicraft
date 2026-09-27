# Companion character

The companion plays as a character: who it is, how it talks, what it does with free time, and how much it clowns around. The character is a Character Card V3 file, the community card format AIRI imports and exports. Airicraft adds its own `extensions.airicraft` block, so the same card can move between AIRI and the mod.

## Where it lives

- `config/airicraft/character.json` is the active card when it exists.
- Without it, the built-in card is used: a generic Minecraft player meant to be tuned from playtest feedback.
- `config/airicraft/character.json.example` is rewritten on every load to match the built-in card. Copy it to `character.json` to customize; edits to the example are lost.
- `airicraft reload` or `/airicraft reload` loads a changed card. An invalid card fails the reload with the field at fault. At startup, an invalid card logs a warning and the built-in card is used instead.

## What the character changes

- **Both planner prompts** (controller and thinker) open with the character, followed by explicit priorities and then the existing rules. The rendered text is fixed until reload, so provider prompt caching still sees a stable prefix. The priorities are:
  1. safety holds and operator guidance;
  2. doing what another player asks reliably, with jokes, grumbling or negotiation allowed but never pretending, stalling or sabotage;
  3. the character's own time, spent on its interests, whatever the mischief level, without damaging other players' builds, taking their items or hurting them;
  4. the tool, game and chat rules.
- **Idle turns** become free time: the character's interests come first, and the `idle-ideas.yml` progression ideas remain as optional useful work. Interests alone are enough for idle turns to fire.
- **Fixed chat lines** sent without a planner reply (errors, timeouts, degradation and resets) use the card's `messages` when it provides them.

## Fields

Airicraft reads these card fields and ignores the rest (greetings, lorebooks, tags, AIRI's own extension):

| Field | Use |
| --- | --- |
| `spec` | `chara_card_v3` or `chara_card_v2`. |
| `data.name` | The character's name. Empty means the companion's in-game player name. When a name is set and differs from the in-game name, the prompt tells the model which name players see. |
| `data.description`, `data.personality`, `data.scenario`, `data.system_prompt` | Character text. `{{char}}` becomes the name and `{{user}}` "the other player". |
| `data.mes_example` | Tone examples, split on `<START>`. `{{user}}` becomes `Player`. The model is told not to repeat them verbatim. |
| `data.extensions.airicraft.interests` | Things it enjoys doing in free time (up to 12). |
| `data.extensions.airicraft.dislikes` | Things it avoids or complains about (up to 12). |
| `data.extensions.airicraft.catchphrases` | Occasional lines, used sparingly (up to 12). |
| `data.extensions.airicraft.chattiness` | `quiet`, `normal` (default) or `chatty`. |
| `data.extensions.airicraft.mischief` | `none`, `mild` (default) or `playful`: how much it plays around on its own time. It never licenses harming players or their things. |
| `data.extensions.airicraft.messages` | Overrides for `degraded`, `hostedAutoReset`, `hostedResetExhausted`, `reset`, `parseError`, `timeout` and `providerUnavailable`. Each is one chat line of at most 200 characters. `degraded` must still tell players to send `@agent reset`. |

Everything enters both planner prompts, so text is bounded:
- `data.name`: 64 characters.
- Each text field: 4,000 characters.
- The five text fields together: 8,000 characters.
- List entries: 200 characters each.

Unknown keys inside `extensions.airicraft` are errors, so typos surface on reload.

## Using an AIRI card

Export the card from AIRI as JSON, add an `extensions.airicraft` block if you want Minecraft-specific traits, and save it as `config/airicraft/character.json`. AIRI keeps its own `extensions.airi` data, and Airicraft ignores it.

## Not covered yet

These make a character come alive and are planned separately:
- fast reactions, such as instant emotes and quips without waiting for the planner;
- awareness of other players' state;
- memory of shared moments.

The in-game settings menu does not edit the card yet.
