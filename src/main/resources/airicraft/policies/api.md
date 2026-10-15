# Query API for this build

Use query_world for read-only world computations and ordinary gameplay tools for actions. Tool availability in your role's advertised schema is authoritative.

## query_world

Arguments: {source, input, radius?, verticalRadius?, center?, includeBlocks?, includeEntities?}. Define a normal synchronous function query(world, input), not a generator or async function. Return JSON. input is your JSON object. No policy methods or further reads are available.

The snapshot is detached from the live world. Local JavaScript edits cannot change Minecraft. Capture happens once on the client thread; guest computation runs off-thread. This tool does not create foreground work, end the planner turn, require an open container or cancel an action in progress.

Bounds: horizontal radius 0..8 (default 4), vertical radius 0..4 (default 2); center defaults to the player's integer block position or specify {x,y,z}. Every corner must be within 64 blocks of the player. Max 2601 block positions. includeBlocks and includeEntities default true. No chunks are loaded for the query. Blocks outside loaded chunks or world height are omitted and counted in metadata. Entities intersect the box, exclude the controlled player, and are limited to the nearest 64, ordered by distance then UUID. Their positions can lie just outside the box when their bounding boxes intersect it.

Snapshot fields:
- world.player: {position:{x,y,z}, health, food}.
- world.blocks: [{position:{x,y,z}, blockId, properties, air, replaceable, fluid, collisionEmpty, light}]. Includes loaded air cells. properties maps property names to strings (e.g. open:"true", half:"lower"); light is the client's combined light level 0..15. These facts are not a path, placement or spawn-safety proof.
- world.entities: [{uuid, type, position:{x,y,z}, distance, hostile, alive, health?}]. health exists for living entities. hostile means Minecraft's MONSTER spawn group, not current aggression or a complete threat assessment (e.g. an angry neutral mob may be dangerous). No inventory, equipment or unopened-container contents are exposed.
- world.metadata: {source:"client_loaded_snapshot", serverTick, worldTime, timeOfDay, day, dimension, bounds:{min,max}, blocks:{included,requested,returned,unloaded,outsideWorld,truncated}, entities:{included,matched,returned,truncated,order}}. worldTime is the game clock and never jumps; timeOfDay (0-23999: 0 sunrise, 12000 sunset, 13000-23000 night) and day jump forward when the night is slept through. serverTick=-1 means unavailable; client snapshot values need not match authoritative server state at that tick.

Tool output is {metadata, result}. Metadata is retained by the host even if your program edits its local copy or returns only a scalar. Empty results apply only to captured coverage. Guest-filtered or sliced results are your own additional subset; report your own truncation if slicing. Successful reads register the loaded block positions with normal action read-freshness tracking; specialized native action checks still apply. Capture is rejected if the world changes before the result is delivered.

Example: source="function query(w, input) { return w.blocks.filter(b => b.blockId === input.id).map(b => b.position); }", input={"id":"minecraft:oak_door"}. Use ordinary inspect_world for specialized standing, interaction and placement-site checks. Loaded blocks can include hidden terrain: use survey_cave and visible-only native gathering for cave ore selection.

Source and input limits are 32768 and 16384 characters. Host snapshot limit is 2097152 JSON characters. Returned JSON is limited to 16384 characters including the guest result envelope. Execution allows 200000 guest statements per evaluation, a 10-second initialization deadline and a 3-second query deadline. No host, network, process or filesystem access. An oversized output fails; reduce fields, aggregate or narrow bounds. read_policy_docs is a separate no-argument read tool available without a world.

## Self-created read-only tools

`survey_surroundings` is a bundled editable `query(world,input)` policy. It returns
ranked landmarks, a north-up terrain grid and an aligned relative-height grid.
Both grids select the same local supporting surface, nearest the requested absolute
`elevation` (default player feet). Height values remain relative to player feet.
Multiple candidate floors are marked M; this is not a route or standing guarantee.
All captured cells participate before output limits. Landmarks use semantic priority,
optional comma-separated `focus` fragments, then block rarity and distance.

Call `inspect_tool({name:"survey_surroundings"})` to obtain its complete definition.
Copy that definition to `define_tool`, with a `custom_` name, edited description,
parameters, source and capture settings. All five fields are required. Updates replace
the definition immediately, without versioning. The next model request advertises
its function schema. Call it by name with its declared arguments. `inspect_tool({})`
lists definitions; `remove_tool({name:...})` removes one, including the survey if desired.
Definitions live in the planner shell's memory and are lost when that shell is rebuilt;
there is no disk persistence. At most 16 definitions may exist.

`parameters` is a JSON schema with `type:"object"`, `properties`, optional `required`,
and `additionalProperties:false`. Properties support string, boolean, number and
integer types, descriptions, enum, and numeric minimum/maximum. Nested objects,
arrays and other schema keywords are not supported and are rejected.
`capture` accepts the same radius, verticalRadius, center, includeBlocks and
includeEntities settings as query_world (not source/input). Capture settings are
fixed per definition; query arguments are passed as `input`. To change capture
bounds, edit the definition or use query_world directly. The default survey captures
radius 8 and verticalRadius 4. Try source through query_world before registration.
Registration validates the definition; JavaScript errors are reported when invoked.

JavaScript receives the existing detached query_world snapshot. Each block also has
`collisionBoxes`, an array of local-coordinate `[minX,minY,minZ,maxX,maxY,maxZ]` boxes.
These retain slab heights and partial shapes; coordinates are relative to the block.
The host supplies coverage metadata outside the editable result and records the read
coverage. Existing source, snapshot, result-size and execution-time limits apply.
Self tools cannot invoke other tools, yield actions, access Java, or mutate the world.

Survey landmark markers are independent of clearance: a cell prefix is a landmark
number, L for several landmarks in that column, or @ for self. The suffix retains
the terrain symbol. Each landmark includes a zero-based map row/column and the
underlying terrain/height. Height still describes the selected supporting surface,
not the landmark. Missing headroom beyond the capture boundary is unknown (?);
it is not a proven absence of support. Block properties use canonical Minecraft
serialized values (for example chest type "single"). Read-only query execution
has a three-second wall-clock budget.
The 200,000-statement limit still applies to both.

Queries may return a string for direct planner-facing text: it is emitted with real
newlines after a compact host-owned coverage line, without JSON quoting. Object,
array and other results retain the structured JSON envelope. The bundled survey
uses text landmarks and aligned ASCII grids; it does not repeat per-landmark map
metadata in the planner result. Its internal `survey(world,input)` helper still
returns geometry for custom policies that need to transform it.

Survey ores are excluded from landmark ranking by default. Set `includeOres:true`
to include blocks whose IDs end in `_ore`; `focus:"ore"` alone does not override
this exclusion. Ore blocks still participate in terrain geometry. `M` means two
or more body-clear supporting surfaces at different heights in one X/Z column;
the height grid selects the one nearest the requested elevation.
