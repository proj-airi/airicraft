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
    private BlueprintStore store;
    private boolean restored;
    private String draftId, lastCommitId, storageError;
    private final LinkedHashMap<String,JsonObject> savedDesigns=new LinkedHashMap<>();
    private final LinkedHashMap<String,Blueprint> committedPlans=new LinkedHashMap<>();
    private final LinkedHashMap<String,JsonObject> integrity=new LinkedHashMap<>();
    private int maintenanceTicks, scanDesign, scanCell, scanChecked, scanUnknown, scanMismatch;
    private final JsonArray scanFindings=new JsonArray();
    private boolean maintenancePending;
    private static final java.util.concurrent.ExecutorService STORAGE=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"blueprint-storage");t.setDaemon(true);return t;});


    BlueprintService() {}
    private String designerLease;
    private volatile String designerSnapshot = "{}";
    private java.util.function.Consumer<JsonObject> designerRecorder=event->{};
    public synchronized void designerRecorder(java.util.function.Consumer<JsonObject> recorder){designerRecorder=Objects.requireNonNull(recorder);}
    public synchronized void recordDesigner(String lease,JsonObject event){if(designActive(lease))designerRecorder.accept(event.deepCopy());}
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
        if(designerLease!=null){var event=JsonParser.parseString(designerSnapshot).getAsJsonObject();event.remove("transcript");event.addProperty("runId",designerLease);event.addProperty("kind","status");event.addProperty("text","cancelled: world session ended");event.addProperty("at",System.currentTimeMillis());designerRecorder.accept(event);}
        designerLease=null;designerSnapshot="{\"status\":\"cancelled\",\"message\":\"World session ended\"}";
        final long closedGeneration=++generation;
        pending=pending.handle((v,e)->{synchronized(this){if(generation==closedGeneration)reset();}return (Void)null;});
        dashboardSnapshot = "{\"available\":false,\"message\":\"World closed; blueprint session ended\"}";
    }
    private void reset() {
        store=null;restored=false;draftId=null;lastCommitId=null;storageError=null;savedDesigns.clear();committedPlans.clear();integrity.clear();resetScan();
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
        out.addProperty("scope","Saved in this world; dashboard is read-only");
        out.addProperty("activeBlueprintId",draftId);
        out.add("savedDesigns",JSON.toJsonTree(savedDesigns.values()));
        out.add("integrity",JSON.toJsonTree(integrity));
        if(storageError!=null)out.addProperty("storageError",storageError);
        dashboardSnapshot=JSON.toJson(out);
    }
    /** Pin the semantic draft as immutable construction input on the client thread. */
    public synchronized List<BlueprintConstructionProgram.Cell> constructionCells(MinecraftClient client,int expectedRevision,BlockPos origin) {
        requireDraft();
        if(client.player==null || client.world==null || client.getServer()==null || ownerServer!=client.getServer()
            || !client.getServer().getSavePath(WorldSavePath.ROOT).toString().equals(draftWorld))throw new IllegalStateException("construction_world_changed");
        if(revision!=expectedRevision)throw new IllegalStateException("blueprint_revision_changed");
        var result=new ArrayList<BlueprintConstructionProgram.Cell>();
        for(var cell:draft.cells.values()) {
            var p=cell.position().add(origin);
            if(!client.world.isChunkLoaded(p) || !client.world.getWorldBorder().contains(p) || !p.isWithinDistance(client.player.getBlockPos(),128)
                || p.getY()<client.world.getBottomY() || p.getY()>client.world.getTopYInclusive())throw new IllegalStateException("construction_out_of_loaded_nearby_world");
            result.add(new BlueprintConstructionProgram.Cell(p.getX(),p.getY(),p.getZ(),Blueprint.stateText(cell.state()),
                net.minecraft.registry.Registries.ITEM.getId(cell.state().getBlock().asItem()).toString(),cell.owner()));
        }
        return List.copyOf(result);
    }
    private CompletableFuture<String> executeNow(JsonObject a,long expectedGeneration) {
        MinecraftClient client=MinecraftClient.getInstance();
        return CompletableFuture.supplyAsync(()->{
            if(client.world==null||client.player==null||client.getServer()==null)throw new IllegalStateException("singleplayer_required");
            if(expectedGeneration!=generation)throw new IllegalStateException("world_changed");
            if(ownerServer!=client.getServer()) {
                reset();ownerServer=client.getServer();store=new BlueprintStore(ownerServer.getSavePath(WorldSavePath.ROOT).resolve("airicraft/blueprints.json"));
            }
            return client.getServer();
        },client::execute).thenCompose(server->restoreSession(server,expectedGeneration).thenApply(v->server)).thenCompose(server->{
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
                        String nextId=a.has("blueprintId")?a.get("blueprintId").getAsString():(draftId==null?UUID.randomUUID().toString():draftId);
                        if(!savedDesigns.containsKey(nextId)&&savedDesigns.size()>=32)throw new IllegalStateException("saved_blueprint_limit_32");
                        draft=next;draftId=nextId;revision++;draftWorld=worldKey;source=a.get("source").getAsString();lastLint=null;
                        var record=savedDesigns.computeIfAbsent(draftId,id->{var r=new JsonObject();r.addProperty("id",id);return r;});
                        record.add("draft",captureDesign(client.world.getRegistryKey().getValue().toString(),terrainOrigin));
                        return JSON.toJson(draft.snapshot(revision));
                    },client::execute);
            }
            // Capture player identity and dimension on the client thread, then touch server state on its owner thread.
            UUID playerId=client.player.getUuid();var dimension=client.world.getRegistryKey();
            return server.submit(()->{
                if(expectedGeneration!=generation)throw new IllegalStateException("world_changed");
                var world=server.getWorld(dimension);var player=server.getPlayerManager().getPlayer(playerId);
                if(world==null||player==null)throw new IllegalStateException("world_changed");
                if(op.equals("maintenance")){scanIntegrity(server);return "{}";}
                if(op.equals("list"))return JSON.toJson(savedDesigns.values().stream().map(r->Map.of("id",r.get("id"),"name",r.getAsJsonObject("draft").getAsJsonObject("tree").get("id"),"revision",r.getAsJsonObject("draft").get("revision"),"committed",r.has("committed"))).toList());
                if(op.equals("load")){
                    String id=a.get("blueprintId").getAsString();var record=savedDesigns.get(id);if(record==null)throw new IllegalArgumentException("unknown_blueprint_id");
                    var d=record.getAsJsonObject("draft");if(savedWorld(server,d)!=world)throw new IllegalArgumentException("blueprint_dimension_mismatch");
                    var next=new Blueprint(d.getAsJsonObject("tree"),readSurface(d));draft=next;draftId=id;source=d.get("source").getAsString();revision++;d.addProperty("revision",revision);d.getAsJsonObject("snapshot").addProperty("revision",revision);draftWorld=worldKey;lastLint=null;
                    surface=readSurface(d);terrain=d.has("terrain")?d.getAsJsonObject("terrain"):null;terrainOrigin=d.has("origin")?Blueprint.vector(d.get("origin")):null;terrainWorld=terrainOrigin==null?null:world;
                    lastCommitId=record.has("committed")?id:null;committed=committedPlans.get(id);
                    if(committed!=null){var c=record.getAsJsonObject("committed");committedRevision=c.get("revision").getAsInt();committedOrigin=Blueprint.vector(c.get("origin"));committedWorld=savedWorld(server,c);}else{committedRevision=0;committedOrigin=null;committedWorld=null;}
                    return currentDraft();
                }
                if(op.equals("get")){requireDraft();return currentDraft();}
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
                    rememberTerrain(t,heights,origin,world);
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
                    var mismatches=committed.cells.values().stream().filter(c->!BlueprintIntegrity.matches(Blueprint.stateText(c.state()),Blueprint.stateText(world.getBlockState(c.position().add(committedOrigin))))).map(Blueprint::describe).toList();
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
                committed=draft;committedOrigin=origin;committedWorld=world;committedRevision=revision;lastCommitId=draftId;
                savedDesigns.get(draftId).add("committed",captureDesign(dimension.getValue().toString(),origin));
                committedPlans.put(draftId,committed);integrity.remove(draftId);resetScan();
                long matched=committed.cells.values().stream().filter(c->world.getBlockState(c.position().add(origin)).equals(c.state())).count();
                return JSON.toJson(Map.of("revision",revision,"written",draft.cells.size(),"matched",matched,"origin",Blueprint.xyz(origin),"mode","creative_direct_blocks"));
            });
        }).thenCompose(result->{
            if(!Set.of("draft","commit","load").contains(a.get("op").getAsString()))return CompletableFuture.completedFuture(result);
            if(expectedGeneration!=generation)return CompletableFuture.failedFuture(new IllegalStateException("world_changed"));
            var data=new JsonObject();data.addProperty("version",1);data.addProperty("activeId",draftId);data.addProperty("lastCommitId",lastCommitId);data.addProperty("revision",revision);data.add("designs",JSON.toJsonTree(savedDesigns.values()));
            BlueprintStore target=store;
            return CompletableFuture.supplyAsync(()->{try{target.save(data);return result;}catch(Exception e){if(expectedGeneration==generation)storageError=e.toString();throw new java.util.concurrent.CompletionException(e);}},STORAGE);
        }).thenApply(result->{if(expectedGeneration!=generation)throw new IllegalStateException("world_changed");publish(expectedGeneration);return result;}).exceptionally(error->{Throwable cause=error;while(cause.getCause()!=null)cause=cause.getCause();if(expectedGeneration==generation)publish(expectedGeneration);return "TOOL_ERROR: blueprint "+cause.getMessage();});
    }
    private String currentDraft(){var out=JSON.toJsonTree(draft.snapshot(revision)).getAsJsonObject();out.addProperty("blueprintId",draftId);if(terrainOrigin!=null)out.add("origin",JSON.toJsonTree(Blueprint.xyz(terrainOrigin)));return JSON.toJson(out);}
    private JsonObject captureDesign(String dimension,BlockPos origin) {
        var out=new JsonObject();out.addProperty("source",source);out.add("tree",draft.tree.deepCopy());out.addProperty("revision",revision);out.addProperty("dimension",dimension);
        if(origin!=null)out.add("origin",JSON.toJsonTree(Blueprint.xyz(origin)));
        if(terrain!=null)out.add("terrain",terrain.deepCopy());
        if(surface!=null)out.add("surface",JSON.toJsonTree(surface));
        out.add("snapshot",JSON.toJsonTree(draft.snapshot(revision)));
        return out;
    }
    private static Map<String,Integer> readSurface(JsonObject data) {
        if(!data.has("surface"))return null;
        var result=new LinkedHashMap<String,Integer>();data.getAsJsonObject("surface").entrySet().forEach(e->result.put(e.getKey(),e.getValue().getAsInt()));return result;
    }
    private static ServerWorld savedWorld(net.minecraft.server.MinecraftServer server,JsonObject data){
        return server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,net.minecraft.util.Identifier.of(data.get("dimension").getAsString())));
    }
    private CompletableFuture<Void> restoreSession(net.minecraft.server.MinecraftServer server,long expectedGeneration) {
        if(restored)return CompletableFuture.completedFuture(null);
        if(storageError!=null)return CompletableFuture.failedFuture(new IllegalStateException(storageError));
        BlueprintStore target=store;
        return CompletableFuture.supplyAsync(()->{try{return target.load();}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}},STORAGE).thenAcceptAsync(data->{
            if(expectedGeneration!=generation||ownerServer!=server)throw new IllegalStateException("world_changed");
            if(data.has("designs"))for(var value:data.getAsJsonArray("designs")){
                var record=value.getAsJsonObject();String id=record.get("id").getAsString();
                if(savedDesigns.size()>=32)throw new IllegalStateException("saved_blueprint_limit_32");
                if(record.has("committed")){var c=record.getAsJsonObject("committed");committedPlans.put(id,new Blueprint(c.getAsJsonObject("tree"),readSurface(c)));}
                savedDesigns.put(id,record);
            }
            if(data.has("activeId")&&!data.get("activeId").isJsonNull()){
                draftId=data.get("activeId").getAsString();var d=savedDesigns.get(draftId).getAsJsonObject("draft");
                draft=new Blueprint(d.getAsJsonObject("tree"),readSurface(d));source=d.get("source").getAsString();revision=data.get("revision").getAsInt();
                draftWorld=server.getSavePath(WorldSavePath.ROOT).toString();surface=readSurface(d);terrain=d.has("terrain")?d.getAsJsonObject("terrain"):null;
                terrainOrigin=d.has("origin")?Blueprint.vector(d.get("origin")):null;terrainWorld=terrainOrigin==null?null:savedWorld(server,d);
            }
            if(data.has("lastCommitId")&&!data.get("lastCommitId").isJsonNull()){
                lastCommitId=data.get("lastCommitId").getAsString();var c=savedDesigns.get(lastCommitId).getAsJsonObject("committed");
                committed=committedPlans.get(lastCommitId);committedRevision=c.get("revision").getAsInt();committedOrigin=Blueprint.vector(c.get("origin"));committedWorld=savedWorld(server,c);
            }
            restored=true;
        },MinecraftClient.getInstance()::execute).exceptionally(e->{if(expectedGeneration==generation)storageError=e.toString();throw new java.util.concurrent.CompletionException(e);});
    }
    /** Schedule bounded read-only world checks; no filesystem IO or scanning on the client tick. */
    public synchronized void tick(MinecraftClient client){
        if(client.world==null||client.player==null||client.getServer()==null||maintenancePending||++maintenanceTicks%20!=0)return;
        maintenancePending=true;
        var op=new JsonObject();op.addProperty("op","maintenance");
        enqueue(op).whenComplete((v,e)->{synchronized(this){maintenancePending=false;}});
    }
    private void resetScan(){scanDesign=0;scanCell=0;scanChecked=0;scanUnknown=0;scanMismatch=0;while(!scanFindings.isEmpty())scanFindings.remove(0);}
    private void scanIntegrity(net.minecraft.server.MinecraftServer server){
        if(committedPlans.isEmpty())return;
        var ids=new ArrayList<>(committedPlans.keySet());if(scanDesign>=ids.size())scanDesign=0;
        String id=ids.get(scanDesign);var record=savedDesigns.get(id).getAsJsonObject("committed");
        var world=savedWorld(server,record);var origin=Blueprint.vector(record.get("origin"));var cells=new ArrayList<>(committedPlans.get(id).cells.values());
        int end=Math.min(cells.size(),scanCell+256);
        for(;scanCell<end;scanCell++){
            var cell=cells.get(scanCell);var p=cell.position().add(origin);
            if(world==null||!world.isChunkLoaded(p)){scanUnknown++;continue;}
            scanChecked++;String actual=Blueprint.stateText(world.getBlockState(p)),expected=Blueprint.stateText(cell.state());
            if(!BlueprintIntegrity.matches(expected,actual)){scanMismatch++;if(scanFindings.size()<30){var f=new JsonObject();f.addProperty("owner",cell.owner());f.add("position",JSON.toJsonTree(Blueprint.xyz(p)));f.addProperty("expected",expected);f.addProperty("actual",actual);scanFindings.add(f);}}
        }
        if(scanCell==cells.size()){
            var result=new JsonObject();result.addProperty("status",scanMismatch>0?"mismatch":scanUnknown>0?"unknown":"matching");result.addProperty("checked",scanChecked);result.addProperty("unknown",scanUnknown);result.addProperty("mismatchCount",scanMismatch);result.addProperty("checkedAt",System.currentTimeMillis());result.add("findings",scanFindings.deepCopy());integrity.put(id,result);
            int next=(scanDesign+1)%ids.size();resetScan();scanDesign=next;
        }
    }
    // Sampling updates authoring input; only a successful draft replaces the published design.
    void rememberTerrain(JsonObject captured,Map<String,Integer> heights,BlockPos origin,ServerWorld world) {
        terrain=captured;surface=heights;terrainOrigin=origin;terrainWorld=world;
    }
    private void requireDraft(){if(draft==null)throw new IllegalStateException("no_draft");}
}
