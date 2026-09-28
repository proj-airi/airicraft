# Speak through AIRI Tamagotchi

Airicraft can copy its existing `say` output to AIRI Tamagotchi.
Minecraft chat still receives the line. AIRI uses its configured TTS provider and avatar stage without calling its chat model.

## Setup

1. Run Tamagotchi with `output:speech` support in its server and stage.
2. Configure a non-streaming speech provider, voice, and Live2D model. Keep the stage open.
3. Copy the auth token from AIRI **Settings → Connection** into your local `config/airicraft/airicraft.yml`:

```yaml
airiSpeech:
  enabled: true
  websocketUrl: "ws://localhost:6121/ws"
  token: "<AIRI auth token>"
```

4. Run `airicraft reload` after saving the config.

Keep the token out of version control. Set `enabled: false` to stop forwarding.

## Behavior

Both immediate and queued `say` calls use the same path. After Minecraft accepts the chat send, Airicraft forwards the sanitized chat text.
The tool name, arguments, timing, and result stay the same.

The sender authenticates over AIRI's WebSocket and sends the `plugin-protocol` event `output:speech` with `{ "text": "..." }`.
Tamagotchi receives it through its existing `server-sdk` channel. Its speech host queues the literal text in the existing TTS pipeline.

Forwarding runs on a bounded background worker. A failed connection does not block Minecraft or fail `say`.
Failures appear in the Minecraft log without the token or speech text. Failed sends are not retried.
Each line uses a short connection, so no persistent connection or reconnect loop is required.

This first version forwards planner `say` calls only. It does not forward player chat or every fixed system message.
Tamagotchi's separate bidirectional streaming voice transport is not supported by this first version.
It has no AIRI LLM call, expression command, playback acknowledgement, or speech cancellation tool.
The successful `say` result confirms Minecraft chat only; it does not confirm audible playback in Tamagotchi.
