# Native planner image budget

With `plannerNativeVisionEnabled: true`, `plannerMaxImages` in `agent.yml` limits images in the main planner context. It defaults to **8**, with a minimum of 1.

The first eight image results enter the conversation normally. Once the budget is full, each new screenshot or provider image (including `take_map_look`) goes to a fresh visual-observer context using the active planner's provider, credentials and model. That context receives the image, tool metadata and requested focus, without the main conversation or action tools. Its text interpretation returns as the tool result. The separately configured external vision model is not used for this fallback.

Existing main-conversation messages and images remain unchanged, preserving their cache prefix. The budget counts the actual outgoing conversation, including raw replay images; successful compaction or session reset frees capacity. Codex app-server history is remote, so its image budget is conservatively held until session reset. Each Codex fallback uses a separate inference thread, which is closed afterward.

A failed interpretation returns `VISION_UNAVAILABLE` as text and does not attach the extra image. External-vision mode and the external driver's raw-image tool interface retain their existing behavior.

Validation uses a local HTTP provider and orchestration tests at limits 1 and 8: repeated screenshots and map images, identical retained prefixes, fresh observer contexts, planner credentials/model selection, interpretation failure, reset, and compaction. This is offline validation, not a resumed Minecraft playtest.
