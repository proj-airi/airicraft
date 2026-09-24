# Test Plan — PR #72: Split conversation debug overlay into chronicle/context views

## Setup (completed / pre-recording)
- Branch `devin/1790223127-conversation-debug-views` checked out; `./gradlew build` already green.
- Write `run/config/airicraft/agent.yml` with `providerBaseUrl`/`apiKey`/`model` expanded from env secrets (run/ is gitignored runtime dir; values never echoed).
- Extract `scenarios/pickup/world.zip` into `run/saves/` for a joinable world.
- Launch plain `JAVA_HOME=... ./gradlew runClient` (NOT codex-driver — `submitPlannerTrigger` early-returns under `externalDriverActive`, so codexDriver mode produces an empty chronicle).
- Wait for `~/.airicraft/bridge-state.json` + `airicraft status` ok; join world via `airicraft worlds list`/`worlds join`.
- Maximize client window (`wmctrl`), then start recording.

## Test 1 — CHRONICLE view with real planner turn
1. In-game, open chat (`t`), type `/airicraft debug conversation`, Enter.
   - PASS: chat feedback shows `Airicraft debug overlay: conversation (chronicle)`; a left-side pane appears with title starting `Chronicle |` — NOT `Conversation |` and NOT `Context |`.
2. From shell: `airicraft agent debug chat --message "look around and say what you see"`. Wait ~30-60s for the LLM turn.
   - PASS: chronicle gains a trigger card whose header matches `HH:mm:ss · <sender> · gN` (real clock time, middle-dot separators; NOT bare `gN aN`), body = the chat text; then a `→ <tool> {args}` / `← <result>` TOOL card and/or an assistant reply card appear. Header role labels are words like `user`/`assistant`/`tool` — no raw enum names or phase jargon.
   - FAIL if: pane stays `Chronicle | empty`, headers lack `HH:mm:ss`, or assistant reply appears twice (old projected-view bug).
3. `airicraft agent debug state --verbose` → check JSON for `chronicleConversation` and `contextConversation` keys (programmatic corroboration).
   - PASS: both keys present; chronicle message count > 0 and grows across turns.

## Test 2 — Supersede + reset NOTICE markers
4. `airicraft agent debug chat --message "count the blocks around you"` then immediately `airicraft agent debug chat --message "actually just tell me the time of day"`.
   - PASS: a NOTICE card `generation gN superseded` appears; the superseded turn's cards remain visible with `· superseded` in their headers (greyed/marker, not deleted). (Best-effort — depends on LLM latency; if the first turn finishes before the second lands, mark inconclusive rather than failed.)
5. `airicraft agent debug chat --message "@agent reset"`.
   - PASS: chronicle is cleared and a NOTICE card `reset: <reason>` renders (header `HH:mm:ss · notice`).

## Test 3 — CONTEXT view
6. In-game: `/airicraft debug context`.
   - PASS: feedback `Airicraft debug overlay: conversation (context)`; title starts `Context |`; cards show the model's live context — a SYSTEM card plus user/assistant/tool entries; headers show ONLY the role/kind label (e.g. `system`, `user`, `assistant`, `tool`) — NO `HH:mm:ss`, NO `gN`, NO `superseded`.
   - PASS: SYSTEM card body is capped at ~480 chars ending `… [+N]` (system prompt is far longer than 480 chars, so truncation must be visible).

## Test 4 — Scrolling (HUD keys + wheel-in-screen)
7. After ≥1 turn + reset + another turn (enough cards to overflow the pane — if not overflowing, send another chat to add cards):
   - Press `End` → PASS: view jumps to latest card (bottom).
   - Press `Home` → PASS: view jumps to top (first card).
   - Press `Page_Down`/`Page_Up` → PASS: view scrolls ~48px per poll step, visibly moving content.
   - Open chat screen (`t` — a Screen IS open) and scroll mouse wheel → PASS: overlay scrolls (wheel path preserved).
   - FAIL if keys do nothing with no screen open, or content doesn't move.

## Test 5 — Command surface intact
8. `/airicraft debug off` → PASS: overlay disappears; feedback `Airicraft debug overlay: off`.
9. `/airicraft debug states` → PASS: states overlay renders (different pane, state list); feedback `Airicraft debug overlay: states`.
10. `/airicraft debug conversation` again → returns to CHRONICLE (view persists as chronicle since context selection is per-command).

## Evidence
- Continuous screen recording of the in-world session.
- Screenshots at each assertion (chronicle populated, superseded marker, context view, truncation `… [+N]`, scroll positions).
- `agent debug state --verbose` JSON captured to file showing `chronicleConversation`/`contextConversation`.
