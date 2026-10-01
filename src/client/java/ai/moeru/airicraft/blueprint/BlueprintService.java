package ai.moeru.airicraft.blueprint;

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

/** Mod-owned, serialized blueprint session. Direct placement remains limited to creative scratch worlds. */
public final class BlueprintService {
    private static final Gson JSON=new Gson();
    private Blueprint draft,committed;
    private int revision,committedRevision;
    private BlockPos committedOrigin;
    private ServerWorld committedWorld;
    private JsonObject terrain;
    private Map<String,Integer> surface;
    private BlockPos terrainOrigin;
    private ServerWorld terrainWorld;
    private String draftWorld;
    private static String library() {
        try(var in=BlueprintService.class.getResourceAsStream("/blueprint/components.js")) {
            return new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    private static final BlueprintService INSTANCE = new BlueprintService();
    public static BlueprintService instance() { return INSTANCE; }
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);
    private volatile long generation;
    private volatile String dashboardSnapshot = "{\"available\":false,\"message\":\"No blueprint draft\"}";
    private net.minecraft.server.MinecraftServer ownerServer;
    private String source;
    private JsonElement lastLint;

    BlueprintService() {}
    private String designerLease;
    private volatile String designerSnapshot = "{}";
    public String dashboardSnapshot() {
        String base=dashboardSnapshot;
        return base.substring(0,base.length()-1)+",\"designer\":"+designerSnapshot+"}";
    }
    public synchronized String beginDesign() {
        if(designerLease!=null)throw new IllegalStateException("designer_busy");
        designerLease=UUID.randomUUID().toString();return designerLease;
    }
    public synchronized boolean designActive(String lease){return lease!=null&&lease.equals(designerLease);}
    public synchronized void endDesign(String lease,boolean cancel){
        if(designActive(lease)){if(cancel)generation++;designerLease=null;}
    }
    public synchronized void publishDesigner(String lease,String snapshot){if(designActive(lease))designerSnapshot=snapshot;}
    public synchronized CompletableFuture<String> executeDesigner(String lease,JsonObject arguments){
        if(!designActive(lease))return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("designer_cancelled"));
        return enqueue(arguments);
    }
    public synchronized void worldLeft() {
        designerLease=null;designerSnapshot="{\"status\":\"cancelled\",\"message\":\"World session ended\"}";
        final long closedGeneration=++generation;
        pending=pending.handle((v,e)->{synchronized(this){if(generation==closedGeneration)reset();}return (Void)null;});
        dashboardSnapshot = "{\"available\":false,\"message\":\"World closed; blueprint session ended\"}";
    }
    private void reset() {
        ownerServer=null;draft=null;committed=null;source=null;lastLint=null;revision=0;committedRevision=0;
        committedOrigin=null;committedWorld=null;terrain=null;surface=null;terrainOrigin=null;terrainWorld=null;draftWorld=null;
    }
    // Serialize control operations, including asynchronous Graal evaluations. HTTP viewers
    // only read the immutable published string; they never call this control interface.
    public synchronized CompletableFuture<String> execute(JsonObject arguments) {
        if(designerLease!=null)return CompletableFuture.completedFuture("TOOL_ERROR: blueprint designer_busy");
        return enqueue(arguments);
    }
    private synchronized CompletableFuture<String> enqueue(JsonObject arguments) {
        var fixed = arguments.deepCopy();
        final long requestGeneration=generation;
        var result = pending.handle((v,e) -> (Void)null).thenCompose(v -> executeNow(fixed,requestGeneration));
        pending = result.handle((v,e) -> (Void)null);
        return result;
    }
    private synchronized void publish(long expectedGeneration) {
        if(expectedGeneration!=generation)throw new IllegalStateException("world_changed");
        var out = new JsonObject();out.addProperty("available",draft!=null);
        if(draft!=null)out.add("draft",JSON.toJsonTree(draft.snapshot(revision)));
        if(source!=null)out.addProperty("source",source);
        if(lastLint!=null)out.add("lint",lastLint);
        out.addProperty("revision",revision);out.addProperty("committedRevision",committedRevision);
        if(committedOrigin!=null)out.add("committedOrigin",JSON.toJsonTree(Blueprint.xyz(committedOrigin)));
        out.addProperty("scope","Current world session; dashboard is read-only");
        dashboardSnapshot=JSON.toJson(out);
    }
    private CompletableFuture<String> executeNow(JsonObject a,long expectedGeneration) {
        MinecraftClient client=MinecraftClient.getInstance();
        return CompletableFuture.supplyAsync(()->{
            if(client.world==null||client.player==null||client.getServer()==null)throw new IllegalStateException("singleplayer_required");
            if(expectedGeneration!=generation)throw new IllegalStateException("world_changed");
            if(ownerServer!=client.getServer()) {
                reset();ownerServer=client.getServer();
            }
            return client.getServer();
        },client::execute).thenCompose(server->{
            String worldKey=server.getSavePath(WorldSavePath.ROOT).toString();
            String op=a.get("op").getAsString();
            if(!op.equals("draft")&&!op.equals("sample")&&draft!=null&&!worldKey.equals(draftWorld))throw new IllegalStateException("draft_world_changed");
            if(op.equals("rule_docs"))return CompletableFuture.completedFuture(JSON.toJson(Map.of("components",library(),"ruleApi",BlueprintLint.resource("lint-api.js"),"defaultRules",BlueprintLint.defaults())));
            if(op.equals("lint")) {
                requireDraft();var fixed=draft;int fixedRevision=revision;
                var dimension=client.world.getRegistryKey();
                BlockPos origin=Blueprint.vector(a,"origin",terrainOrigin!=null?terrainOrigin:(committedOrigin!=null?committedOrigin:client.player.getBlockPos()));
                var rules=a.has("rules")?a.getAsJsonArray("rules").deepCopy():BlueprintLint.defaults();
                return server.submit(()->BlueprintLint.capture(fixed,fixedRevision,server.getWorld(dimension),origin)).thenCompose(snapshot->BlueprintLint.run(snapshot,rules)).thenApply(result->{if(expectedGeneration!=generation)throw new IllegalStateException("world_changed");lastLint=JsonParser.parseString(result);return result;});
            }
            if(op.equals("draft")) {
                JsonObject input=new JsonObject();if(terrain!=null)input.add("terrain",terrain.deepCopy());
                return GraalPolicyInvocation.query(library()+"\n"+a.get("source").getAsString()+"\nfunction query(world,input){return design(input);}",new JsonObject(),input)
                    .thenApplyAsync(tree->{
                        if(client.getServer()!=server||expectedGeneration!=generation)throw new IllegalStateException("world_changed");
                        if(terrainWorld!=null&&terrainWorld!=server.getWorld(client.world.getRegistryKey()))throw new IllegalStateException("terrain_world_changed");
                        Blueprint next=new Blueprint(tree.getAsJsonObject(),surface);
                        draft=next;revision++;draftWorld=worldKey;source=a.get("source").getAsString();lastLint=null;return JSON.toJson(draft.snapshot(revision));
                    },client::execute);
            }
            // Capture player identity and dimension on the client thread, then touch server state on its owner thread.
            UUID playerId=client.player.getUuid();var dimension=client.world.getRegistryKey();
            return server.submit(()->{
                if(expectedGeneration!=generation)throw new IllegalStateException("world_changed");
                var world=server.getWorld(dimension);var player=server.getPlayerManager().getPlayer(playerId);
                if(world==null||player==null)throw new IllegalStateException("world_changed");
                if(op.equals("get")){requireDraft();return JSON.toJson(draft.snapshot(revision));}
                if(op.equals("sample")) {
                    BlockPos origin=Blueprint.vector(a,"origin",player.getBlockPos());
                    if(!origin.isWithinDistance(player.getBlockPos(),96))throw new IllegalArgumentException("sample_too_far");
                    var heights=new LinkedHashMap<String,Integer>();var sampled=new ArrayList<Map<String,Object>>();int max=-128,min=128;
                    for(int x=-1;x<=14;x++)for(int z=-1;z<=14;z++) {
                        BlockPos p=origin.add(x,0,z);if(!world.isChunkLoaded(p))throw new IllegalStateException("terrain_chunk_unloaded");
                        int y=world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ())-1;
                        if(Math.abs(y-origin.getY())>48)throw new IllegalStateException("terrain_outside_capture_height");
                        heights.put(x+","+z,y-origin.getY());max=Math.max(max,y-origin.getY());min=Math.min(min,y-origin.getY());
                        for(int sy=Math.max(world.getBottomY(),y-2);sy<=y;sy++) sampled.add(Map.of("position",List.of(x,sy-origin.getY(),z),"state",Blueprint.stateText(world.getBlockState(new BlockPos(p.getX(),sy,p.getZ()))),"owner","terrain","source","observed"));
                    }
                    var t=new JsonObject();t.add("surface",JSON.toJsonTree(heights));t.addProperty("maxSurface",max);t.addProperty("minSurface",min);t.add("origin",JSON.toJsonTree(Blueprint.xyz(origin)));
                    terrain=t;surface=heights;terrainOrigin=origin;terrainWorld=world;draft=null;source=null;lastLint=null;
                    return JSON.toJson(Map.of("terrain",t,"cells",sampled,"note","Observed terrain context, never committed as design blocks; 16x16 height field and top three layers."));
                }
                if(op.equals("explain")) {
                    boolean worldCoordinates=a.has("world")&&a.get("world").getAsBoolean();
                    BlockPos p=Blueprint.vector(a.get("position"));
                    Blueprint target=draft;
                    if(worldCoordinates){if(committed==null||committedWorld!=world)throw new IllegalStateException("no_commit_in_this_world");target=committed;p=p.subtract(committedOrigin);}else requireDraft();
                    var cell=target.cells.get(p);var out=new LinkedHashMap<String,Object>();
                    out.put("position",Blueprint.xyz(p));out.put("specified",cell!=null);
                    if(cell!=null)out.putAll(Blueprint.describe(cell));
                    if(worldCoordinates){var actual=world.getBlockState(p.add(committedOrigin));out.put("actual",Blueprint.stateText(actual));out.put("matches",cell!=null&&actual.equals(cell.state()));out.put("revision",committedRevision);}
                    return JSON.toJson(out);
                }
                if(op.equals("verify")) {
                    if(committed==null||committedWorld!=world)throw new IllegalStateException("no_commit_in_this_world");
                    var mismatches=committed.cells.values().stream().filter(c->!world.getBlockState(c.position().add(committedOrigin)).equals(c.state())).map(Blueprint::describe).toList();
                    return JSON.toJson(Map.of("revision",committedRevision,"checked",committed.cells.size(),"mismatchCount",mismatches.size(),"mismatches",mismatches.stream().limit(30).toList()));
                }
                if(!server.getSavePath(WorldSavePath.ROOT).normalize().getFileName().toString().startsWith("Blueprint-"))throw new IllegalStateException("requires_Blueprint_scratch_world");
                if(op.equals("prepare")) {
                    if(!Boolean.getBoolean("airicraft.codexDriver"))throw new IllegalStateException("codex_driver_required");
                    player.changeGameMode(GameMode.CREATIVE);server.setDifficulty(net.minecraft.world.Difficulty.PEACEFUL,false);world.setTimeOfDay(6000);world.setWeather(0,6000,false,false);
                    return JSON.toJson(Map.of("creative",true,"world",worldKey,"position",Blueprint.xyz(player.getBlockPos())));
                }
                if(!player.isCreative())throw new IllegalStateException("creative_required");
                if(op.equals("save")) {
                    return JSON.toJson(Map.of("saved",server.save(false,true,true)));
                }
                if(op.equals("view")) {
                    if(!Boolean.getBoolean("airicraft.codexDriver"))throw new IllegalStateException("codex_driver_required");
                    BlockPos p=Blueprint.vector(a.get("position"));
                    player.teleport(world,p.getX()+.5,p.getY(),p.getZ()+.5,Set.<PositionFlag>of(),a.has("yaw")?a.get("yaw").getAsFloat():0,a.has("pitch")?a.get("pitch").getAsFloat():20,true);
                    player.getAbilities().flying=true;player.sendAbilitiesUpdate();return JSON.toJson(Map.of("position",Blueprint.xyz(p)));
                }
                if(!op.equals("commit"))throw new IllegalArgumentException("unknown_op");
                requireDraft();if(!worldKey.equals(draftWorld))throw new IllegalStateException("draft_world_changed");
                if(a.get("revision").getAsInt()!=revision)throw new IllegalStateException("stale_revision");
                BlockPos origin=Blueprint.vector(a.get("origin"));
                if(terrainOrigin!=null&&(!origin.equals(terrainOrigin)||terrainWorld!=world))throw new IllegalStateException("terrain_anchor_mismatch");
                for(var cell:draft.cells.values()) {
                    BlockPos p=cell.position().add(origin);
                    if(!world.isChunkLoaded(p)||p.getY()<world.getBottomY()||p.getY()>world.getTopYInclusive()||!world.getWorldBorder().contains(p)||!p.isWithinDistance(player.getBlockPos(),128))throw new IllegalStateException("commit_out_of_loaded_nearby_world");
                }
                for(var cell:draft.cells.values())world.setBlockState(cell.position().add(origin),cell.state(),2);
                committed=draft;committedOrigin=origin;committedWorld=world;committedRevision=revision;
                long matched=committed.cells.values().stream().filter(c->world.getBlockState(c.position().add(origin)).equals(c.state())).count();
                return JSON.toJson(Map.of("revision",revision,"written",draft.cells.size(),"matched",matched,"origin",Blueprint.xyz(origin),"mode","creative_direct_blocks"));
            });
        }).thenApply(result->{if(expectedGeneration!=generation)throw new IllegalStateException("world_changed");publish(expectedGeneration);return result;}).exceptionally(error->{Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();return "TOOL_ERROR: blueprint "+cause.getMessage();});
    }
    private void requireDraft(){if(draft==null)throw new IllegalStateException("no_draft");}
}
