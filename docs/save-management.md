# Singleplayer save management

Run these commands with Minecraft at the title or world-selection screen:

```sh
airicraft worlds list
airicraft worlds create --name "New survival" --seed 42
airicraft worlds rename --world-id <id-from-list> --name "New display name"
airicraft worlds delete --world-id <id-from-list>
```

`create` uses Minecraft's normal generator and starts joining the new world.
It creates a survival world with normal difficulty, structures enabled, no bonus
chest, and cheats off. Omit `--seed` for a random seed. The save folder gets a unique
name, so an existing save is never overwritten. The response includes `started`,
`worldId`, folder `name`, `displayName`, and the actual seed. `started: true` means
the integrated server started; it does not guarantee the player has joined yet.
Check `airicraft status` for world availability. Return to the title screen through
Minecraft before renaming or deleting.

`rename` changes the display name stored in `level.dat`; it preserves the folder,
world ID, player data, and chunks. `delete` permanently removes the selected save,
including its player data and chunks. There is no undo or automatic backup.

Save mutations require a closed world and acquire Minecraft's save lock. Locked
saves return `world_locked`; missing saves return `world_not_found`. A world already
open in this client returns `already_in_world`; other screens or loading transitions
return `world_management_busy`. Symlinked save folders are refused. Save names must
be nonblank, at most 255 characters, and contain no control characters.

The authenticated internal bridge exposes:

| Method | Route | JSON body |
| --- | --- | --- |
| POST | `/v1/worlds/create` | `{"name":"New survival","seed":42}` (`seed` optional) |
| POST | `/v1/worlds/rename` | `{"worldId":"<id>","name":"New display name"}` |
| POST | `/v1/worlds/delete` | `{"worldId":"<id>"}` |

Use the wrapper as the public interface. Save mutations allow up to 120 seconds
in the bridge and 125 seconds in the wrapper. After a timeout, inspect the client
and save list before retrying: an operation that already started may still finish.
If creation fails after reserving its folder, that folder may remain for diagnosis;
the error identifies it. Creation uses the standard installed datapack loading flow.

## Launch directly into a saved world

```sh
scripts/codex-driver --world "New survival"
# Normal launch with the embedded planner:
./gradlew runClient -Pairicraft.world="New survival"
```

Use the save **folder name** (`name` from `worlds list`), not its display name or
hashed `worldId`. The save must already exist under the launched client's `saves/`
folder. Minecraft Quick Play loads it during startup, so no separate status/list/join
loop is required. This works for the normal compatibility launch and the bare
Minecraft development client. Other production launch tasks accept the same Gradle
property; evaluator launches resolve the name within their isolated game directory.
Missing or incompatible saves use Minecraft's normal Quick Play error screens.
Without `--world` or `airicraft.world`, startup behavior is unchanged.

The launcher remains attached to the Minecraft process. Startup is not a readiness
receipt; when subsequent automation needs a loaded world, use the bridge's
`worldLoaded` state as the readiness check, without issuing another join request.
