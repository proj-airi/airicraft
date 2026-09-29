Stage-side changes for the AIRI checkout pinned in `../../pins.json`, as `git format-patch` files applied in name
order on top of the `pinned` tag by `scripts/cortico-experiment setup airi` (re-applied from scratch when the series
changes).

- `0001`: the stage store that replays a Cortico persona's speak/act/delta frames through the chat hooks, plus the
  `enabled` early-returns in `chat.ts`, `context-bridge.ts` and the character orchestrator, and the shared frame
  schema. Reduced from moeru-ai/airi#2634 (by peachoolong-uwu) and rebased onto the pinned main; the bridge package,
  spark/channel client, memory settings page, i18n, vendor patch and voice-input routing are left out.
- `0002`: reports playback end. The stage remembers which `speak` ids belong to a stage turn and sends `speech_end`
  frames when the speech pipeline ends the turn, or `interrupted` when it cancels it.

To change a patch: apply the series in `.cortico-experiment/airi`, edit and commit there, then
`git format-patch pinned -o experiments/cortico-world/patches/airi`.
