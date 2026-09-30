# Qwen blueprint experiment — 2026-09-30

The interface is not yet reliably usable by the configured smaller model. This is a small exploratory test, not a benchmark. Compilation succeeded for two designs under one setting, but neither met its complete brief.

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
