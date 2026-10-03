# AIRI link

The AIRI link connects the mod to the AIRI desktop app. AIRI is the self of the character, and the mod is its body in Minecraft. [ADR 0005](adr/0005-link-to-airi-as-the-body.md) gives the design.

## Status

The link connects, authenticates, announces the module `airicraft`, sends heartbeats and reconnects. It does not yet send game state or act on AIRI commands. Those parts come in later changes.

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

## Wire format

The link writes plain JSON events. It reads plain JSON and the SuperJSON wrapper `{"json": event}` that the AIRI server writes. Code is in `src/client/java/ai/moeru/airicraft/airi/`.
