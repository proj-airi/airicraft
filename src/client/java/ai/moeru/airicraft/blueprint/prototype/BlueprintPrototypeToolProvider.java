package ai.moeru.airicraft.blueprint.prototype;

import ai.moeru.airicraft.agent.llm.*;
import ai.moeru.airicraft.policy.GraalPolicyInvocation;
import com.google.gson.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.Heightmap;
import net.minecraft.util.WorldSavePath;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static ai.moeru.airicraft.agent.llm.PlannerToolCatalog.*;

/** THROWAWAY: external-driver-only tools; direct placement limited to creative scratch worlds. */
public final class BlueprintPrototypeToolProvider implements PlannerToolProvider {
    private static final Gson JSON=new Gson();
    private BlueprintPrototype draft,committed;
    private int revision,committedRevision;
    private BlockPos committedOrigin;
    private ServerWorld committedWorld;
    private JsonObject terrain;
    private Map<String,Integer> surface;
    private BlockPos terrainOrigin;
    private ServerWorld terrainWorld;
    private String draftWorld;
    private static String library() {
        try(var in=BlueprintPrototypeToolProvider.class.getResourceAsStream("/blueprint-prototype/components.js")) {
            return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    @Override public String id(){return "blueprint_prototype";}
    @Override public boolean available(){return Boolean.getBoolean("airicraft.codexDriver");}
    @Override public boolean handles(String name){return "blueprint_prototype".equals(name);}
    @Override public boolean isReadTool(String name){return false;}
    @Override public List<Map<String,Object>> openAiTools(){return List.of(toolForProvider("blueprint_prototype",
        "THROWAWAY semantic blueprint. op=draft runs JS function design(input) using Component, Assembly, Solid, Clearance, Room, Door, Window, Floor, Staircase, GableRoof, Foundation constructors; no world effects. get returns tree/cells. explain uses position (local by default; world=true uses last commit). sample captures 16x16 terrain at origin, passed to next design as input.terrain. commit requires revision and origin, writes direct blocks only in creative Blueprint-* scratch worlds. prepare sets scratch world creative; view teleports camera to position with yaw/pitch; verify compares committed states. lint runs optional rules [{id,source}] defining check(ctx), or bundled advisory rules. rule_docs returns authoring and rule APIs. Lint never blocks commit. Drafts and provenance are in memory.",
        propertiesForProvider(propForProvider("op",stringForProvider("draft|get|explain|sample|commit|prepare|view|verify|save|lint|rule_docs")),
          propForProvider("rules",Map.of("type","array","items",Map.of("type","object"))),
          propForProvider("source",stringForProvider("JavaScript defining design(input).")),
          propForProvider("position",Map.of("type","array","items",Map.of("type","integer"),"minItems",3,"maxItems",3)),
          propForProvider("origin",Map.of("type","array","items",Map.of("type","integer"),"minItems",3,"maxItems",3)),
          propForProvider("revision",Map.of("type","integer")),propForProvider("world",Map.of("type","boolean")),
          propForProvider("yaw",Map.of("type","number")),propForProvider("pitch",Map.of("type","number"))),List.of("op")));}
    @Override public CompletableFuture<String> execute(PlannerToolCall call) {
        MinecraftClient client=MinecraftClient.getInstance();var a=call.arguments();
        return CompletableFuture.supplyAsync(()->{
            if(!available())throw new IllegalStateException("codex_driver_required");
            if(client.world==null||client.player==null||client.getServer()==null)throw new IllegalStateException("singleplayer_required");
            return client.getServer();
        },client::execute).thenCompose(server->{
            String worldKey=server.getSavePath(WorldSavePath.ROOT).toString();
            String op=a.get("op").getAsString();
            if(op.equals("rule_docs"))return CompletableFuture.completedFuture(JSON.toJson(Map.of("components",library(),"ruleApi",BlueprintLint.resource("lint-api.js"),"defaultRules",BlueprintLint.defaults())));
            if(op.equals("lint")) {
                requireDraft();var fixed=draft;int fixedRevision=revision;
                var dimension=client.world.getRegistryKey();
                BlockPos origin=BlueprintPrototype.vector(a,"origin",terrainOrigin!=null?terrainOrigin:(committedOrigin!=null?committedOrigin:client.player.getBlockPos()));
                var rules=a.has("rules")?a.getAsJsonArray("rules").deepCopy():BlueprintLint.defaults();
                return server.submit(()->BlueprintLint.capture(fixed,fixedRevision,server.getWorld(dimension),origin)).thenCompose(snapshot->BlueprintLint.run(snapshot,rules));
            }
            if(op.equals("draft")) {
                JsonObject input=new JsonObject();if(terrain!=null)input.add("terrain",terrain.deepCopy());
                return GraalPolicyInvocation.query(library()+"\n"+a.get("source").getAsString()+"\nfunction query(world,input){return design(input);}",new JsonObject(),input)
                    .thenApplyAsync(tree->{
                        if(client.getServer()!=server)throw new IllegalStateException("world_changed");
                        if(terrainWorld!=null&&terrainWorld!=server.getWorld(client.world.getRegistryKey()))throw new IllegalStateException("terrain_world_changed");
                        BlueprintPrototype next=new BlueprintPrototype(tree.getAsJsonObject(),surface);
                        draft=next;revision++;draftWorld=worldKey;return JSON.toJson(draft.snapshot(revision));
                    },client::execute);
            }
            // Capture player identity and dimension on the client thread, then touch server state on its owner thread.
            UUID playerId=client.player.getUuid();var dimension=client.world.getRegistryKey();
            return server.submit(()->{
                var world=server.getWorld(dimension);var player=server.getPlayerManager().getPlayer(playerId);
                if(world==null||player==null)throw new IllegalStateException("world_changed");
                if(op.equals("get")){requireDraft();return JSON.toJson(draft.snapshot(revision));}
                if(op.equals("sample")) {
                    BlockPos origin=BlueprintPrototype.vector(a,"origin",player.getBlockPos());
                    if(!origin.isWithinDistance(player.getBlockPos(),96))throw new IllegalArgumentException("sample_too_far");
                    var heights=new LinkedHashMap<String,Integer>();var sampled=new ArrayList<Map<String,Object>>();int max=-128,min=128;
                    for(int x=-1;x<=14;x++)for(int z=-1;z<=14;z++) {
                        BlockPos p=origin.add(x,0,z);if(!world.isChunkLoaded(p))throw new IllegalStateException("terrain_chunk_unloaded");
                        int y=world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ())-1;
                        if(Math.abs(y-origin.getY())>48)throw new IllegalStateException("terrain_outside_capture_height");
                        heights.put(x+","+z,y-origin.getY());max=Math.max(max,y-origin.getY());min=Math.min(min,y-origin.getY());
                        for(int sy=Math.max(world.getBottomY(),y-2);sy<=y;sy++) sampled.add(Map.of("position",List.of(x,sy-origin.getY(),z),"state",BlueprintPrototype.stateText(world.getBlockState(new BlockPos(p.getX(),sy,p.getZ()))),"owner","terrain","source","observed"));
                    }
                    var t=new JsonObject();t.add("surface",JSON.toJsonTree(heights));t.addProperty("maxSurface",max);t.addProperty("minSurface",min);t.add("origin",JSON.toJsonTree(BlueprintPrototype.xyz(origin)));
                    terrain=t;surface=heights;terrainOrigin=origin;terrainWorld=world;draft=null;
                    return JSON.toJson(Map.of("terrain",t,"cells",sampled,"note","Observed terrain context, never committed as design blocks; 16x16 height field and top three layers."));
                }
                if(op.equals("explain")) {
                    boolean worldCoordinates=a.has("world")&&a.get("world").getAsBoolean();
                    BlockPos p=BlueprintPrototype.vector(a.get("position"));
                    BlueprintPrototype target=draft;
                    if(worldCoordinates){if(committed==null||committedWorld!=world)throw new IllegalStateException("no_commit_in_this_world");target=committed;p=p.subtract(committedOrigin);}else requireDraft();
                    var cell=target.cells.get(p);var out=new LinkedHashMap<String,Object>();
                    out.put("position",BlueprintPrototype.xyz(p));out.put("specified",cell!=null);
                    if(cell!=null)out.putAll(BlueprintPrototype.describe(cell));
                    if(worldCoordinates){var actual=world.getBlockState(p.add(committedOrigin));out.put("actual",BlueprintPrototype.stateText(actual));out.put("matches",cell!=null&&actual.equals(cell.state()));out.put("revision",committedRevision);}
                    return JSON.toJson(out);
                }
                if(op.equals("verify")) {
                    if(committed==null||committedWorld!=world)throw new IllegalStateException("no_commit_in_this_world");
                    var mismatches=committed.cells.values().stream().filter(c->!world.getBlockState(c.position().add(committedOrigin)).equals(c.state())).map(BlueprintPrototype::describe).toList();
                    return JSON.toJson(Map.of("revision",committedRevision,"checked",committed.cells.size(),"mismatchCount",mismatches.size(),"mismatches",mismatches.stream().limit(30).toList()));
                }
                if(!server.getSavePath(WorldSavePath.ROOT).normalize().getFileName().toString().startsWith("Blueprint-"))throw new IllegalStateException("requires_Blueprint_scratch_world");
                if(op.equals("prepare")) {
                    player.changeGameMode(GameMode.CREATIVE);server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL,false);world.setTimeOfDay(6000);world.setWeather(0,6000,false,false);
                    return JSON.toJson(Map.of("creative",true,"world",worldKey,"position",BlueprintPrototype.xyz(player.getBlockPos())));
                }
                if(!player.isCreative())throw new IllegalStateException("creative_required");
                if(op.equals("save")) {
                    return JSON.toJson(Map.of("saved",server.save(false,true,true)));
                }
                if(op.equals("view")) {
                    BlockPos p=BlueprintPrototype.vector(a.get("position"));
                    player.teleport(world,p.getX()+.5,p.getY(),p.getZ()+.5,Set.<PositionFlag>of(),a.has("yaw")?a.get("yaw").getAsFloat():0,a.has("pitch")?a.get("pitch").getAsFloat():20,true);
                    player.getAbilities().flying=true;player.sendAbilitiesUpdate();return JSON.toJson(Map.of("position",BlueprintPrototype.xyz(p)));
                }
                if(!op.equals("commit"))throw new IllegalArgumentException("unknown_op");
                requireDraft();if(!worldKey.equals(draftWorld))throw new IllegalStateException("draft_world_changed");
                if(a.get("revision").getAsInt()!=revision)throw new IllegalStateException("stale_revision");
                BlockPos origin=BlueprintPrototype.vector(a.get("origin"));
                if(terrainOrigin!=null&&(!origin.equals(terrainOrigin)||terrainWorld!=world))throw new IllegalStateException("terrain_anchor_mismatch");
                for(var cell:draft.cells.values()) {
                    BlockPos p=cell.position().add(origin);
                    if(!world.isChunkLoaded(p)||p.getY()<world.getBottomY()||p.getY()>world.getTopYInclusive()||!world.getWorldBorder().contains(p)||!p.isWithinDistance(player.getBlockPos(),128))throw new IllegalStateException("commit_out_of_loaded_nearby_world");
                }
                for(var cell:draft.cells.values())world.setBlockState(cell.position().add(origin),cell.state(),2);
                committed=draft;committedOrigin=origin;committedWorld=world;committedRevision=revision;
                long matched=committed.cells.values().stream().filter(c->world.getBlockState(c.position().add(origin)).equals(c.state())).count();
                return JSON.toJson(Map.of("revision",revision,"written",draft.cells.size(),"matched",matched,"origin",BlueprintPrototype.xyz(origin),"mode","creative_direct_blocks"));
            });
        }).exceptionally(error->{Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();return "TOOL_ERROR: blueprint_prototype "+cause.getMessage();});
    }
    private void requireDraft(){if(draft==null)throw new IllegalStateException("no_draft");}
}
