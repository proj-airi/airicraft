# Qwen blueprint experiment — 2026-09-30

The initial interface was not reliably usable by the configured smaller model. A reasoning-enabled follow-up with semantic rules is recorded below. This is a small exploratory test, not a benchmark. Compilation succeeded for two designs under one setting, but neither met its complete brief.

## Setup and resource use

Minecraft was stopped at the user's request. No game, renderer, world placement or new Gradle build was used for these trials. The offline adapter used a flat ground fixture (top at local y=0), the shared component library and shared JS rules. Inference ran remotely on the configured provider `https://api.runinfra.ai/v1`, requesting `qwen3-8-27b`. No Airicraft configuration was changed.

Three fixed tasks: lit one-room dwelling; lit two-story house with a usable staircase; intentionally dark spawning room with a separate lit maintenance room. Each fresh conversation received the full constructor library and coordinate conventions, with no completed-house example. Tools were `design`, `lint`, and `inspect`; maximum 8 model turns and 3 accepted design submissions. Sources were not manually repaired. An extra design request beyond the limit was refused.

The harness used provider sampling defaults, JSON tool calling and a longer HTTP timeout than Airicraft. It did not run the embedded planner prompt, scheduler or real Minecraft block-state validation. Later arms preserved the provider's reasoning field when replaying tool turns. The first arm never reached a tool turn.

## Recorded outcomes

| Setting | Lit room | Two-story | Dark chamber |
| --- | --- | --- | --- |
| Provider reasoning default, 4,096 completion tokens/request | No tool call | No tool call | No tool call |
| Provider reasoning default, 16,384 tokens/request | No tool call | No tool call | No tool call |
| Requested `reasoning_effort: none`, 16,384 tokens/request | Compiled 335 cells; brief failed | Compiled 702 cells; brief failed | No successful compilation |

The first arm consumed all 4,096 completion tokens in each response, with empty visible answers and no tools. Its harness originally labeled these `model_finished` and did not retain finish reasons; budget exhaustion is inferred from the recorded usage/content. The larger-budget arm explicitly returned `finish_reason: length` for all three requests. Neither establishes blueprint ability because no source was submitted.

With reasoning disabled:

- **Lit room:** 3 submissions, first rejected for an overlap, next two compiled. Door/window were plain `type` labels without constructor-generated volumes, so the wall remained solid. The entrance rule warned. A lantern was outside the offline shape fixture, so lighting was unverified. Roof dimensions also did not match the room footprint. The run reached the 8-turn limit.
- **Two-story:** 3 accepted submissions (first rejected for overlap), plus a refused fourth request. It used real Door/Window constructors and repaired stair/interior overlap. The final draft still had entrance and lighting warnings. Lights placed through unsupported `Room.children` were silently discarded. Full-cube exterior steps required jumping; the roof was only six blocks wide for a ten-block-wide room. The staircase opening/landing was not aligned for a usable route. The run reached the 8-turn limit.
- **Dark chamber:** all 3 accepted submissions failed overlap checks; a fourth request was refused. The model then finished. No final draft or lint result was available.

Remote response time summed across turns was 22.2 / 46.9 / 33.6 seconds respectively for the non-reasoning arm; these exclude local execution and are not end-to-end latency. The default 16k arm took 108.2 / 91.7 / 93.7 seconds, each without a tool call. There is one trial per task/setting; no confidence intervals or general model ranking are justified.

## What this tells us

The model can call tools, compose constructors and act on overlap diagnostics. It also confuses semantic descriptors with constructed objects, misses dimensions and relationships, and relies on properties that are silently ignored. The current entrance and lighting rules expose some defects but do not certify a complete building.

Next interface work should reject unsupported authoring fields, distinguish raw component nodes from constructor arguments, and provide semantic attachments plus roof-coverage and stair-connectivity advice. Increasing reasoning budget alone did not solve this run.

After freezing the trials, an example JS extension (`rules/empty-geometry.js`) flagged both empty door/window components in the lit-room output. That diagnostic was **not** fed back to the model and does not improve its recorded outcome. It demonstrates adding a rule without changing the engine.

The two compiled final sources are preserved unchanged in `examples/` and exposed by named buttons in the offline editor. They are failure cases for inspection, not recommended house templates. Full responses, attempts, final drafts, lint results and post-hoc findings remain locally under ignored `run/blueprint-evidence/model-trial*`. Credentials are absent from the recorded transcripts.

## Follow-up: semantic rules and reasoning enabled

At the user's request, reasoning stayed enabled for **three fresh designs plus one focused repair task**. Settings: requested `reasoning_effort: low`, 32,768 completion tokens per request, at most 12 model turns and 5 accepted design submissions. Responses included nonempty reasoning and completed tool calls; none of these four runs exhausted the output allowance. No game was launched, no Airicraft configuration was changed, and no model-generated source was manually repaired.

The library/rules now supply room dimensions and ignored-field metadata, roof/ceiling coverage, straight-stair clearance/landing checks, and empty-component advice. Prompts explain these extra diagnostics. Per-trial source hashes, the full system prompt and all attempts are recorded. These changes and the increased attempt budget mean this is **not a controlled comparison** of reasoning effort or of rules alone.

| Task | Accepted design submissions | Final observed result |
| --- | --- | --- |
| Lit dwelling | 2 | Compiled 347 cells. Cleared entrance warning by lowering the floor flush with exterior ground; coverage passes. Lighting remains unverified because the lantern shape is unsupported by the offline fixture. |
| Two-story house | 5 (including one overlap failure) | Compiled 923 cells. Correct roof coverage and exterior access. Cleared both lighting warnings by repositioning/adding lights. Staircase still fails clearance/landing advice; the model's added support blocks did not resolve it. |
| Dark chamber + maintenance room | 3 | Compiled 561 cells. Added full ceilings after coverage findings; final bundle has zero findings. Source inspection confirms separate rooms, dark intent/no windows for the chamber, lit maintenance room and an aligned exterior stair. This is still offline layout evidence, not a mob-spawning test. |
| Focused roof repair | 2 | Starter had exactly one finding: 15/35 interior columns uncovered. Qwen widened the roof from 5 to 9 blocks and added a full ceiling. Final bundle has zero findings. Independent comparison confirms the entire Room subtree, including entrance, window and lamp, is unchanged. |

The focused repair is defined separately in `semantic-repair-tasks.json`: it requires compiling/linting a fixed defective starter before changing it, preserving room parameters and rerunning lint. The successful repair demonstrates using a concrete semantic diagnostic; it is one seeded case, not a general success rate. The final zero-findings state is bounded by the checks implemented. Notably, the two-story failure suggests more specific stair obstruction/landing evidence would help beyond the current combined warning.

Run the fresh-design follow-up with:

```sh
BLUEPRINT_TRIAL_ID=new-semantic-run BLUEPRINT_REASONING=low \
  BLUEPRINT_MODEL_TOKENS=32768 BLUEPRINT_MAX_DESIGNS=5 BLUEPRINT_MAX_TURNS=12 \
  python3 prototypes/blueprint/model-trial.py
```

For the focused repair, additionally set `BLUEPRINT_TASKS_FILE=prototypes/blueprint/semantic-repair-tasks.json` and use a different trial ID. Runtime records are in `run/blueprint-evidence/model-trial-semantic-low-32k/` and `model-trial-semantic-roof-low-32k/`. The final sources are preserved under `examples/low-*.js` and `roof-repair-after.js`; `roof-repair-before.js` is the fixed starter. All appear as named buttons in the offline editor. Orange diagnostic markers identify uncovered columns without adding authored blocks.

Semantic regression probes passed for full/holey/rotated/unknown/open roofs, correction of a narrow roof, empty components, ignored fields, corrected stair headroom and scoped suppression. The new Java metadata/default-rule wiring was not rebuilt or run in Minecraft in this follow-up; validation used the shared JS rules and offline adapter to respect the resource constraint.
