package ai.moeru.airicraft.blueprint;

import baritone.api.BaritoneAPI;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/** Live geometry adapter; tool effects remain owned by the ordinary task executors. */
public final class MinecraftBlueprintConstructionEnvironment implements BlueprintConstructionProgram.Environment {
    private final MinecraftClient client;
    private final ClientWorld world;
    private final boolean previousBreak,previousPlace,previousAllowFlying,initiallyFlying;
    private final net.minecraft.client.network.ClientPlayerEntity owner;
    private int airborneTicks;
    private ConstructionEscape.Position repairDestination;
    @Override public ConstructionEscape.Position repairDestination(){var next=repairDestination;repairDestination=null;return next;}
    private net.minecraft.util.math.Vec3d previousPosition;
    private double travelled;
    private com.google.gson.JsonObject repairDiagnostic=new com.google.gson.JsonObject();
    private static final java.util.concurrent.ExecutorService STORAGE=java.util.concurrent.Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"construction-journal");t.setDaemon(true);return t;});
    private final ConstructionRepairStore repairStore;
    private final java.util.concurrent.CompletableFuture<java.util.Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt>> loadedDebt;
    private java.util.Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> persistedDebt;
    @Override public java.util.Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> restoredDebt(){persistedDebt=loadedDebt.join();return persistedDebt;}
    @Override public java.util.concurrent.CompletableFuture<Void> persistDebt(java.util.Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debt){
        var snapshot=java.util.Map.copyOf(debt);
        if(snapshot.equals(persistedDebt))return java.util.concurrent.CompletableFuture.completedFuture(null);
        return java.util.concurrent.CompletableFuture.runAsync(()->{try{repairStore.save(snapshot);persistedDebt=snapshot;}catch(java.io.IOException e){throw new java.util.concurrent.CompletionException(e);}},STORAGE);
    }
    public MinecraftBlueprintConstructionEnvironment(MinecraftClient client,java.util.List<BlueprintConstructionProgram.Cell> cells) {
        String identity=client.world.getRegistryKey().getValue()+"\n"+cells.stream().map(Object::toString).sorted().collect(java.util.stream.Collectors.joining("\n"));
        String key;
        try{key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        repairStore=new ConstructionRepairStore(client.getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("airicraft/construction/"+key+".json"));
        loadedDebt=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return repairStore.load();}catch(java.io.IOException e){throw new java.util.concurrent.CompletionException(e);}},STORAGE);
        this.client=client;this.world=client.world;previousPosition=client.player.getPos();owner=client.player;
        previousAllowFlying=owner.getAbilities().allowFlying;initiallyFlying=owner.getAbilities().flying;
        owner.getAbilities().allowFlying=false;owner.getAbilities().flying=false;owner.sendAbilitiesUpdate();
        var settings=BaritoneAPI.getSettings();previousBreak=settings.allowBreak.value;previousPlace=settings.allowPlace.value;
        settings.allowBreak.value=false;settings.allowPlace.value=false;
    }
    private BlockPos position(BlueprintConstructionProgram.Cell cell){return new BlockPos(cell.x(),cell.y(),cell.z());}
    private void requireWorld(){if(client.world!=world || client.player==null)throw new IllegalStateException("construction_world_changed");}
    @Override public String state(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);return world.isChunkLoaded(pos)?Blueprint.stateText(world.getBlockState(pos)):"unknown_unloaded";
    }
    @Override public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);var desired=Blueprint.parseState(cell.state());
        // This ranking hint deliberately covers plain cubes only. The interaction executor
        // remains authoritative for exact state prediction, reach, and collision checks.
        if(client.player.getEyePos().squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(pos))>25
            || client.player.getBoundingBox().intersects(new Box(pos))
            || !desired.isFullCube(world,pos) || !desired.equals(desired.getBlock().getDefaultState()))return false;
        return MinecraftScaffoldPlanner.placeable(client,world,client.player.getEyePos(),pos);
    }
    @Override public boolean eligible(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);
        if(!world.isChunkLoaded(pos) || !world.getBlockState(pos).isReplaceable() || !world.getFluidState(pos).isEmpty()
            || client.player.getBoundingBox().intersects(new Box(pos)))return false;
        var desired=Blueprint.parseState(cell.state());
        if(!desired.canPlaceAt(world,pos))return false;
        for(var direction:Direction.values()) {
            var support=pos.offset(direction);
            if(world.isChunkLoaded(support) && !world.getBlockState(support).isReplaceable()
                && !world.getBlockState(support).getCollisionShape(world,support).isEmpty())return true;
        }
        return false;
    }
    @Override public java.util.List<AccessRepairSearch.Edit> repairPlan(java.util.List<BlueprintConstructionProgram.Cell> cells,boolean accessOnly) {
        requireWorld();repairDestination=null;
        var bounds=new ConstructionEscape.Bounds(cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).min().orElseThrow(),
            cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).min().orElseThrow(),
            cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).max().orElseThrow());
        repairDiagnostic=new com.google.gson.JsonObject();
        repairDiagnostic.addProperty("start",client.player.getPos().toString());
        if(MinecraftConstructionEscape.canEscape(client,bounds,java.util.Map.of())){
            if(accessOnly){repairDiagnostic.addProperty("result","already_connected");return java.util.List.of();}
            var scaffold=MinecraftScaffoldPlanner.plan(client,cells,bounds);
            repairDestination=scaffold.destination();
            repairDiagnostic.addProperty("result",scaffold.edits().isEmpty()?"no_platform_found":"platform_found");repairDiagnostic.addProperty("plan",scaffold.toString());return scaffold.edits();
        }
        var eye=client.player.getEyePos();int feetY=client.player.getBlockY();
        var candidates=cells.stream().filter(c->{
            var p=position(c);var state=world.getBlockState(p);
            if(c.y()<feetY || eye.squaredDistanceTo(net.minecraft.util.math.Vec3d.ofCenter(p))>20.25 || !c.state().equals(Blueprint.stateText(state))
                || world.getBlockEntity(p)!=null || !state.isFullCube(world,p) || state.getHardness(world,p)<0)return false;
            // Repair must be affordable even when breaking loses the original item (e.g. glass).
            if(!client.player.isCreative()) {
                boolean replacement=false;
                for(int slot=0;slot<client.player.getInventory().size();slot++) {
                    var stack=client.player.getInventory().getStack(slot);
                    if(net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString().equals(c.item()) && !stack.isEmpty())replacement=true;
                }
                if(!replacement || !client.player.canHarvest(state))return false;
            }
            return true;
        }).sorted(java.util.Comparator.comparingDouble(this::distanceSquared)).limit(20).toList();
        repairDiagnostic.addProperty("candidates",candidates.size());
        repairDiagnostic.addProperty("candidatePositions",candidates.stream().map(c->position(c).toShortString()).toList().toString());
        var model=new AccessRepairSearch.Model(){
            java.util.Map<BlockPos,net.minecraft.block.BlockState> overlay(java.util.List<AccessRepairSearch.Edit> path){
                var result=new java.util.HashMap<BlockPos,net.minecraft.block.BlockState>();
                for(var edit:path)result.put(new BlockPos(edit.position().x(),edit.position().y(),edit.position().z()),net.minecraft.block.Blocks.AIR.getDefaultState());
                return result;
            }
            public java.util.List<AccessRepairSearch.Edit> executableEdits(java.util.List<AccessRepairSearch.Edit> path){
                var changes=overlay(path);
                net.minecraft.world.BlockView view=new net.minecraft.world.BlockView(){
                    public net.minecraft.block.BlockState getBlockState(BlockPos p){return changes.getOrDefault(p,world.getBlockState(p));}
                    public net.minecraft.fluid.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
                    public net.minecraft.block.entity.BlockEntity getBlockEntity(BlockPos p){return null;}
                    public int getHeight(){return world.getHeight();}
                    public int getBottomY(){return world.getBottomY();}
                };
                var edits=new java.util.ArrayList<AccessRepairSearch.Edit>();
                for(var c:candidates){
                    var p=position(c);if(changes.containsKey(p))continue;
                    var hit=view.raycast(new net.minecraft.world.RaycastContext(eye,net.minecraft.util.math.Vec3d.ofCenter(p),net.minecraft.world.RaycastContext.ShapeType.OUTLINE,net.minecraft.world.RaycastContext.FluidHandling.NONE,client.player));
                    if(hit.getType()==net.minecraft.util.hit.HitResult.Type.BLOCK && hit.getBlockPos().equals(p))
                        edits.add(new AccessRepairSearch.Edit(new ConstructionEscape.Position(c.x(),c.y(),c.z()),c.state(),"minecraft:air",c.item().contains("glass")?30:8));
                }
                return edits;
            }
            public boolean goal(java.util.List<AccessRepairSearch.Edit> path){return MinecraftConstructionEscape.canEscape(client,bounds,overlay(path));}
            public double estimate(java.util.List<AccessRepairSearch.Edit> path){return 0;}
            public Object stateKey(java.util.List<AccessRepairSearch.Edit> path){return java.util.Set.copyOf(overlay(path).keySet());}
        };
        var result=AccessRepairSearch.find(model,new AccessRepairSearch.Limits(3,12,32));
        repairDiagnostic.addProperty("result",result.reason());repairDiagnostic.addProperty("expanded",result.expanded());
        repairDiagnostic.addProperty("plan",result.edits().toString());
        var allRemoved=candidates.stream().map(c->new AccessRepairSearch.Edit(new ConstructionEscape.Position(c.x(),c.y(),c.z()),c.state(),"minecraft:air",8)).toList();
        var trace=MinecraftConstructionEscape.explore(client,bounds,model.overlay(allRemoved));
        repairDiagnostic.addProperty("allCandidatesRemovedConnects",trace.escaped());
        repairDiagnostic.addProperty("reachableByHeight",trace.reached().stream().collect(java.util.stream.Collectors.groupingBy(ConstructionEscape.Position::y,java.util.stream.Collectors.counting())).toString());
        repairDiagnostic.addProperty("lowestReachable",trace.reached().stream().sorted(java.util.Comparator.comparingInt(ConstructionEscape.Position::y)).limit(15).toList().toString());
        if(!result.found())throw new IllegalStateException("access_repair_unresolved: "+repairDiagnostic);
        return result.edits();
    }
    @Override public java.util.List<AccessRepairSearch.Edit> cleanupPlan(java.util.List<BlueprintConstructionProgram.Cell> cells,java.util.Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts) {
        requireWorld();repairDestination=null;
        var bounds=new ConstructionEscape.Bounds(cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).max().orElseThrow());
        return MinecraftScaffoldPlanner.cleanup(client,debts,bounds);
    }
    @Override public double distanceSquared(BlueprintConstructionProgram.Cell cell){requireWorld();return client.player.squaredDistanceTo(cell.x()+.5,cell.y(),cell.z()+.5);}
    @Override public boolean ready(){requireWorld();if(!loadedDebt.isDone())return false;loadedDebt.join();return client.player.isOnGround() && !client.player.getAbilities().flying;}
    @Override public void tick(){requireWorld();
        if(client.player.getAbilities().flying)throw new IllegalStateException("construction_flight_detected");
        client.player.getAbilities().allowFlying=false;
        if(!client.player.isOnGround())airborneTicks++;
        var position=client.player.getPos();travelled+=position.distanceTo(previousPosition);previousPosition=position;}
    @Override public com.google.gson.JsonObject metrics(){var value=new com.google.gson.JsonObject();value.addProperty("distanceTravelled",travelled);value.addProperty("initiallyFlying",initiallyFlying);value.addProperty("airborneTicks",airborneTicks);value.addProperty("flying",owner.getAbilities().flying);value.addProperty("grounded",owner.isOnGround());value.add("repairSearch",repairDiagnostic.deepCopy());return value;}
    @Override public void close(){if(client.player==owner){owner.getAbilities().allowFlying=previousAllowFlying;owner.sendAbilitiesUpdate();}var settings=BaritoneAPI.getSettings();settings.allowBreak.value=previousBreak;settings.allowPlace.value=previousPlace;}
}
