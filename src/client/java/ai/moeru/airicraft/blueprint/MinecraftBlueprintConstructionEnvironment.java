package ai.moeru.airicraft.blueprint;

import ai.moeru.airicraft.agent.navigation.PathfindSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.Direction;

/** Live geometry adapter; tool effects remain owned by the ordinary task executors. */
public final class MinecraftBlueprintConstructionEnvironment implements BlueprintConstructionProgram.Environment {
    private final Minecraft client;
    private final ClientLevel world;
    private final boolean previousBreak,previousPlace,previousAllowFlying,initiallyFlying;
    private final net.minecraft.client.player.LocalPlayer owner;
    private int airborneTicks;
    private ConstructionEscape.Position repairDestination;
    @Override public ConstructionEscape.Position repairDestination(){var next=repairDestination;repairDestination=null;return next;}
    private net.minecraft.world.phys.Vec3 previousPosition;
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
    public MinecraftBlueprintConstructionEnvironment(Minecraft client,java.util.List<BlueprintConstructionProgram.Cell> cells) {
        String identity=client.level.dimension().location()+"\n"+cells.stream().map(Object::toString).sorted().collect(java.util.stream.Collectors.joining("\n"));
        String key;
        try{key=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        repairStore=new ConstructionRepairStore(client.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("airicraft/construction/"+key+".json"));
        loadedDebt=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return repairStore.load();}catch(java.io.IOException e){throw new java.util.concurrent.CompletionException(e);}},STORAGE);
        this.client=client;this.world=client.level;previousPosition=client.player.position();owner=client.player;
        previousAllowFlying=owner.getAbilities().mayfly;initiallyFlying=owner.getAbilities().flying;
        owner.getAbilities().mayfly=false;owner.getAbilities().flying=false;owner.onUpdateAbilities();
        var settings=PathfindSettings.current();previousBreak=settings.allowBreak();previousPlace=settings.allowPlace();
        setRouteEdits(false,false);
    }
    private BlockPos position(BlueprintConstructionProgram.Cell cell){return new BlockPos(cell.x(),cell.y(),cell.z());}
    private void requireWorld(){if(client.level!=world || client.player==null)throw new IllegalStateException("construction_world_changed");}
    @Override public String state(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);return world.hasChunkAt(pos)?Blueprint.stateText(world.getBlockState(pos)):"unknown_unloaded";
    }
    @Override public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);var desired=Blueprint.parseState(cell.state());
        // This ranking hint deliberately covers plain cubes only. The interaction executor
        // remains authoritative for exact state prediction, reach, and collision checks.
        if(client.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))>25
            || client.player.getBoundingBox().intersects(new AABB(pos))
            || !desired.isCollisionShapeFullBlock(world,pos) || !desired.equals(desired.getBlock().defaultBlockState()))return false;
        return MinecraftScaffoldPlanner.placeable(client,world,client.player.getEyePosition(),pos);
    }
    @Override public boolean eligible(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);
        if(!world.hasChunkAt(pos) || !world.getBlockState(pos).canBeReplaced() || !world.getFluidState(pos).isEmpty()
            || client.player.getBoundingBox().intersects(new AABB(pos)))return false;
        var desired=Blueprint.parseState(cell.state());
        if(!desired.canSurvive(world,pos))return false;
        for(var direction:Direction.values()) {
            var support=pos.relative(direction);
            if(world.hasChunkAt(support) && !world.getBlockState(support).canBeReplaced()
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
        repairDiagnostic.addProperty("start",client.player.position().toString());
        if(MinecraftConstructionEscape.canEscape(client,bounds,java.util.Map.of())){
            if(accessOnly){repairDiagnostic.addProperty("result","already_connected");return java.util.List.of();}
            var scaffold=MinecraftScaffoldPlanner.plan(client,cells,bounds);
            repairDestination=scaffold.destination();
            repairDiagnostic.addProperty("result",scaffold.edits().isEmpty()?"no_platform_found":"platform_found");repairDiagnostic.addProperty("plan",scaffold.toString());return scaffold.edits();
        }
        var eye=client.player.getEyePosition();int feetY=client.player.getBlockY();
        var candidates=cells.stream().filter(c->{
            var p=position(c);var state=world.getBlockState(p);
            if(c.y()<feetY || eye.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p))>20.25 || !c.state().equals(Blueprint.stateText(state))
                || world.getBlockEntity(p)!=null || !state.isCollisionShapeFullBlock(world,p) || state.getDestroySpeed(world,p)<0)return false;
            // Repair must be affordable even when breaking loses the original item (e.g. glass).
            if(!client.player.isCreative()) {
                boolean replacement=false;
                for(int slot=0;slot<client.player.getInventory().getContainerSize();slot++) {
                    var stack=client.player.getInventory().getItem(slot);
                    if(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(c.item()) && !stack.isEmpty())replacement=true;
                }
                if(!replacement || !client.player.hasCorrectToolForDrops(state))return false;
            }
            return true;
        }).sorted(java.util.Comparator.comparingDouble(this::distanceSquared)).limit(20).toList();
        repairDiagnostic.addProperty("candidates",candidates.size());
        repairDiagnostic.addProperty("candidatePositions",candidates.stream().map(c->position(c).toShortString()).toList().toString());
        var model=new AccessRepairSearch.Model(){
            java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay(java.util.List<AccessRepairSearch.Edit> path){
                var result=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
                for(var edit:path)result.put(new BlockPos(edit.position().x(),edit.position().y(),edit.position().z()),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                return result;
            }
            public java.util.List<AccessRepairSearch.Edit> executableEdits(java.util.List<AccessRepairSearch.Edit> path){
                var changes=overlay(path);
                net.minecraft.world.level.BlockGetter view=new net.minecraft.world.level.BlockGetter(){
                    public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos p){return changes.getOrDefault(p,world.getBlockState(p));}
                    public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
                    public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){return null;}
                    public int getHeight(){return world.getHeight();}
                    public int getMinY(){return world.getMinY();}
                };
                var edits=new java.util.ArrayList<AccessRepairSearch.Edit>();
                for(var c:candidates){
                    var p=position(c);if(changes.containsKey(p))continue;
                    var hit=view.clip(new net.minecraft.world.level.ClipContext(eye,net.minecraft.world.phys.Vec3.atCenterOf(p),net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,client.player));
                    if(hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(p))
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
    @Override public double distanceSquared(BlueprintConstructionProgram.Cell cell){requireWorld();return client.player.distanceToSqr(cell.x()+.5,cell.y(),cell.z()+.5);}
    @Override public boolean ready(){requireWorld();if(!loadedDebt.isDone())return false;loadedDebt.join();return client.player.onGround() && !client.player.getAbilities().flying;}
    @Override public void tick(){requireWorld();
        if(client.player.getAbilities().flying)throw new IllegalStateException("construction_flight_detected");
        client.player.getAbilities().mayfly=false;
        if(!client.player.onGround())airborneTicks++;
        var position=client.player.position();travelled+=position.distanceTo(previousPosition);previousPosition=position;}
    @Override public com.google.gson.JsonObject metrics(){var value=new com.google.gson.JsonObject();value.addProperty("distanceTravelled",travelled);value.addProperty("initiallyFlying",initiallyFlying);value.addProperty("airborneTicks",airborneTicks);value.addProperty("flying",owner.getAbilities().flying);value.addProperty("grounded",owner.onGround());value.add("repairSearch",repairDiagnostic.deepCopy());return value;}
    private static void setRouteEdits(boolean breaking,boolean placing){var args=new com.google.gson.JsonObject();args.addProperty("allowBreak",breaking);args.addProperty("allowPlace",placing);PathfindSettings.apply(args);}
    @Override public void close(){if(client.player==owner){owner.getAbilities().mayfly=previousAllowFlying;owner.onUpdateAbilities();}setRouteEdits(previousBreak,previousPlace);}
}
