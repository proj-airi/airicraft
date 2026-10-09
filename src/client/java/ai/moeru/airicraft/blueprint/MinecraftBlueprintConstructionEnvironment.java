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
    private final ConstructionEscape.Bounds constructionBounds;
    private java.util.List<java.util.Map.Entry<ConstructionEscape.Position,Integer>> retryRoutes;
    private final java.util.Map<BlueprintConstructionProgram.Cell,Double> travelEstimates=new java.util.HashMap<>();
    // Ordinary removal remains retryable. Do not regenerate a failed access macro
    // merely because dismantling its own tower advanced the geometry revision.
    private final java.util.Set<ConstructionEscape.Position> cleanupAccessAttempted=new java.util.HashSet<>();

    private final FailedRepairApproaches failedRepairApproaches=new FailedRepairApproaches();
    @Override public void repairFailed(AccessRepairSearch.Edit edit){failedRepairApproaches.reject(edit);}
    @Override public void geometryChanged(){failedRepairApproaches.clear();retryRoutes=null;travelEstimates.clear();}
    private ConstructionEscape.Position repairDestination;
    private BlueprintConstructionProgram.Cell repairTarget;
    @Override public BlueprintConstructionProgram.Cell repairTarget(){var next=repairTarget;repairTarget=null;return next;}
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
        constructionBounds=new ConstructionEscape.Bounds(cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).min().orElseThrow(),
            cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).min().orElseThrow(),
            cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).max().orElseThrow());
        previousAllowFlying=owner.getAbilities().mayfly;initiallyFlying=owner.getAbilities().flying;
        owner.getAbilities().mayfly=false;owner.getAbilities().flying=false;owner.onUpdateAbilities();
        var settings=PathfindSettings.current();previousBreak=settings.allowBreak();previousPlace=settings.allowPlace();
        setRouteEdits(false,false);
    }
    private BlockPos position(BlueprintConstructionProgram.Cell cell){return new BlockPos(cell.x(),cell.y(),cell.z());}
    private void requireWorld(){if(client.level!=world || client.player==null)throw new IllegalStateException("construction_world_changed");}
    @Override public String state(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);if(!world.hasChunkAt(pos))return "unknown_unloaded";
        var actual=world.getBlockState(pos);
        // Pane/fence/wall connections follow neighbors; report the designed state when only those differ so the cell counts as done.
        return BlueprintIntegrity.statesMatch(Blueprint.parseState(cell.state()),actual)?cell.state():Blueprint.stateText(actual);
    }
    @Override public boolean structuralSupport(BlueprintConstructionProgram.Cell cell){
        requireWorld();return Blueprint.parseState(cell.state()).isCollisionShapeFullBlock(world,position(cell));
    }
    @Override public boolean immediatelyPlaceable(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);var desired=Blueprint.parseState(cell.state());
        // Include shaped blocks when this eye position can reach a compatible face.
        // The executor remains authoritative for full state prediction and collision.
        if(client.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))>25
            || client.player.getBoundingBox().intersects(new AABB(pos))
            || !desired.canSurvive(world,pos))return false;
        return MinecraftScaffoldPlanner.placeable(client,world,client.player.getEyePosition(),pos,desired)
            && MinecraftConstructionEscape.permits(client,pos,constructionBounds,desired);
    }
    @Override public int continuationCount(BlueprintConstructionProgram.Cell placed,java.util.List<BlueprintConstructionProgram.Cell> remaining){
        requireWorld();var pos=position(placed);var desired=Blueprint.parseState(placed.state());
        // Current-stance candidates are ordinary cubes. Do not speculate about
        // paired blocks or neighbor-driven state changes as hypothetical supports.
        if(desired.getBlock().getClass()!=net.minecraft.world.level.block.Block.class)return 0;
        var geometry=MinecraftScaffoldPlanner.view(client,java.util.Map.of(pos,desired));
        var eye=client.player.getEyePosition();int count=0;
        for(var c:remaining){
            if(c.equals(placed))continue;
            var target=position(c);var state=Blueprint.parseState(c.state());
            if(!client.player.getBoundingBox().intersects(new AABB(target))
                && MinecraftScaffoldPlanner.placeable(client,geometry,eye,target,state))count++;
        }
        return count;
    }
    @Override public boolean retryReady(BlueprintConstructionProgram.Cell cell) {
        return Double.isFinite(travelTicks(cell));
    }
    @Override public double travelTicks(BlueprintConstructionProgram.Cell cell) {
        requireWorld();return travelEstimates.computeIfAbsent(cell,this::estimateTravelTicks);
    }
    private double estimateTravelTicks(BlueprintConstructionProgram.Cell cell) {
        var target=position(cell);var desired=Blueprint.parseState(cell.state());
        if(!world.hasChunkAt(target) || !MinecraftScaffoldPlanner.replaceableTarget(world.getBlockState(target)) || !desired.canSurvive(world,target))return Double.POSITIVE_INFINITY;
        if(retryRoutes==null)retryRoutes=MinecraftConstructionEscape.explore(client,
            MinecraftScaffoldPlanner.region(constructionBounds),java.util.Map.of(),null,true).steps().entrySet().stream()
            .sorted(java.util.Map.Entry.<ConstructionEscape.Position,Integer>comparingByValue()
                .thenComparingInt(e->e.getKey().x()).thenComparingInt(e->e.getKey().y()).thenComparingInt(e->e.getKey().z())).toList();
        for(var route:retryRoutes){
            var pose=route.getKey();
            var eye=new net.minecraft.world.phys.Vec3(pose.x()+.5,pose.y()+owner.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),pose.z()+.5);
            if(eye.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(target))>25)continue;
            if(!MinecraftScaffoldPlanner.placeable(client,world,eye,target,desired))continue;
            if(MinecraftConstructionEscape.explore(client,constructionBounds,MinecraftConstructionEscape.placementOverlay(target,desired),pose,false).escaped()){
                // Nominal five ticks per walking transition. This captures detours,
                // not acceleration, jumps or the executor's exact arrival time.
                return route.getValue()*5.0;
            }
        }
        return Double.POSITIVE_INFINITY;
    }
    @Override public boolean canToggle(BlueprintConstructionProgram.Cell cell) {
        requireWorld();var pos=position(cell);if(!world.hasChunkAt(pos))return false;
        var observed=world.getBlockState(pos);var desired=Blueprint.parseState(cell.state());
        return observed.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door
            && door.type().canOpenByHand() && !observed.getValue(net.minecraft.world.level.block.DoorBlock.POWERED)
            && observed.cycle(net.minecraft.world.level.block.DoorBlock.OPEN).equals(desired);
    }
    @Override public boolean hasMaterial(BlueprintConstructionProgram.Cell cell){
        requireWorld();var inventory=owner.getInventory();
        for(int slot=0;slot<inventory.getContainerSize();slot++){
            var stack=inventory.getItem(slot);
            if(!stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(cell.item()))return true;
        }
        return false;
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
        requireWorld();repairDestination=null;repairTarget=null;
        var bounds=new ConstructionEscape.Bounds(cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).min().orElseThrow(),
            cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).min().orElseThrow(),
            cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).max().orElseThrow());
        repairDiagnostic=new com.google.gson.JsonObject();
        repairDiagnostic.addProperty("start",client.player.position().toString());
        boolean connected=MinecraftConstructionEscape.canEscape(client,bounds,java.util.Map.of());
        if(connected){
            if(accessOnly){repairDiagnostic.addProperty("result","already_connected");return java.util.List.of();}
            var scaffold=MinecraftScaffoldPlanner.plan(client,cells,bounds);
            repairDestination=scaffold.destination();
            repairDiagnostic.addProperty("result",scaffold.edits().isEmpty()?"no_platform_found":scaffold.edits().stream().anyMatch(AccessRepairSearch.Edit::tower)?"tower_found":"platform_found");repairDiagnostic.addProperty("plan",scaffold.toString());if(!scaffold.edits().isEmpty())return scaffold.edits();
        }
        var pending=cells.stream().filter(c->!c.air() && !c.state().contains("half=upper") && MinecraftScaffoldPlanner.replaceableTarget(world.getBlockState(position(c)))).toList();
        var initialReach=connected?MinecraftConstructionEscape.explore(client,MinecraftScaffoldPlanner.region(bounds),java.util.Map.of(),null,true).reached():java.util.Set.<ConstructionEscape.Position>of();
        var candidates=cells.stream().filter(c->{
            var p=position(c);var state=world.getBlockState(p);
            if(p.equals(client.player.blockPosition().below()) || !c.state().equals(Blueprint.stateText(state))
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
        }).sorted(java.util.Comparator.comparingDouble((BlueprintConstructionProgram.Cell c)->
            repairCandidateScore(c,pending,client.player.position(),connected))).limit(32).toList();
        repairDiagnostic.addProperty("candidates",candidates.size());
        repairDiagnostic.addProperty("candidatePositions",candidates.stream().map(c->position(c).toShortString()).toList().toString());
        var model=new AccessRepairSearch.Model(){
            BlueprintConstructionProgram.Cell objective;
            ConstructionEscape.Position destination;
            boolean opensWork(java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> changes){
                var view=MinecraftScaffoldPlanner.view(client,changes);
                var reachable=MinecraftConstructionEscape.explore(client,MinecraftScaffoldPlanner.region(bounds),changes,null,true).reached();
                for(var target:pending)for(var pose:reachable){
                    var p=position(target);var from=new net.minecraft.world.phys.Vec3(pose.x()+.5,pose.y()+owner.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),pose.z()+.5);
                    if(from.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p))>25)continue;
                    var desired=Blueprint.parseState(target.state());
                    if(initialReach.contains(pose) && MinecraftScaffoldPlanner.placeable(client,world,from,p,desired))continue;
                    if(!MinecraftScaffoldPlanner.placeable(client,view,from,p,desired))continue;
                    var placed=new java.util.HashMap<>(changes);placed.putAll(MinecraftConstructionEscape.placementOverlay(p,Blueprint.parseState(target.state())));
                    if(!MinecraftConstructionEscape.explore(client,bounds,placed,pose,false).escaped())continue;
                    objective=target;destination=pose;return true;
                }
                return false;
            }

            java.util.Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay(java.util.List<AccessRepairSearch.Edit> path){
                var result=new java.util.HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
                for(var edit:path)result.put(new BlockPos(edit.position().x(),edit.position().y(),edit.position().z()),net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                return result;
            }
            public java.util.List<AccessRepairSearch.Edit> executableEdits(java.util.List<AccessRepairSearch.Edit> path){
                var changes=overlay(path);
                var view=MinecraftScaffoldPlanner.view(client,changes);
                var reachable=MinecraftConstructionEscape.explore(client,MinecraftScaffoldPlanner.region(bounds),changes,null,true).reached();
                var edits=new java.util.ArrayList<AccessRepairSearch.Edit>();
                for(var c:candidates){
                    var p=position(c);if(changes.containsKey(p))continue;
                    var after=new java.util.HashMap<>(changes);after.put(p,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    var stance=MinecraftScaffoldPlanner.reachableBreakStance(reachable,p,client.player.position(),owner.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),pose->{
                        var eye=new net.minecraft.world.phys.Vec3(pose.x()+.5,pose.y()+owner.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),pose.z()+.5);
                        return failedRepairApproaches.allows(
                            new ConstructionEscape.Position(c.x(),c.y(),c.z()),c.state(),"minecraft:air",pose,false)
                            && MinecraftScaffoldPlanner.breakVisible(client,view,eye,p)
                            && (!connected || MinecraftConstructionEscape.explore(client,bounds,after,pose,false).escaped());
                    });
                    if(stance.isPresent())edits.add(new AccessRepairSearch.Edit(new ConstructionEscape.Position(c.x(),c.y(),c.z()),c.state(),"minecraft:air",
                        (c.item().contains("glass")?30:8)+Math.sqrt(client.player.distanceToSqr(stance.get().x()+.5,stance.get().y(),stance.get().z()+.5))*.1,stance.get()));
                }
                return edits;
            }
            public boolean goal(java.util.List<AccessRepairSearch.Edit> path){
                var changes=overlay(path);
                return MinecraftConstructionEscape.canEscape(client,bounds,changes) && (!connected || !path.isEmpty() && opensWork(changes));
            }
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
        if(result.found()){
            repairTarget=model.objective;repairDestination=model.destination;
            if(repairTarget!=null)repairDiagnostic.addProperty("workTarget",position(repairTarget).toShortString());
            return result.edits();
        }
        // A rooftop may need added access, not a hole. Try owned scaffolding before
        // yielding no plan so the program can consider dismantling existing scaffolds.
        var scaffold=MinecraftScaffoldPlanner.plan(client,cells,bounds);
        repairDestination=scaffold.destination();
        repairDiagnostic.addProperty("scaffoldFallback",scaffold.toString());
        return scaffold.edits();
    }
    static double repairCandidateScore(BlueprintConstructionProgram.Cell cell,java.util.List<BlueprintConstructionProgram.Cell> pending,
        net.minecraft.world.phys.Vec3 player,boolean connected){
        double distance=player.distanceToSqr(cell.x()+.5,cell.y()+.5,cell.z()+.5);
        // While trapped, the search goal is escape, not access to unfinished work.
        // Keep nearby wall/ceiling cuts in the bounded candidate set even when the
        // remaining blueprint targets are on a different part of the roof.
        if(!connected)return distance;
        var p=new BlockPos(cell.x(),cell.y(),cell.z());
        return pending.stream().mapToDouble(t->p.distSqr(new BlockPos(t.x(),t.y(),t.z()))).min().orElse(0)+distance*.05;
    }
    @Override public java.util.List<AccessRepairSearch.Edit> cleanupPlan(java.util.List<BlueprintConstructionProgram.Cell> cells,java.util.Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts) {
        requireWorld();repairDestination=null;repairTarget=null;
        var bounds=new ConstructionEscape.Bounds(cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).min().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::x).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::y).max().orElseThrow(),cells.stream().mapToInt(BlueprintConstructionProgram.Cell::z).max().orElseThrow());
        return MinecraftScaffoldPlanner.cleanup(client,debts,bounds,cells,cleanupAccessAttempted);
    }
    @Override public double distanceSquared(BlueprintConstructionProgram.Cell cell){requireWorld();return client.player.distanceToSqr(cell.x()+.5,cell.y(),cell.z()+.5);}
    @Override public boolean ready(){
        requireWorld();if(!loadedDebt.isDone())return false;loadedDebt.join();
        if(!client.player.onGround() || client.player.getAbilities().flying)return false;
        // onGround describes the preceding movement tick. Removing the support block
        // must wait for gravity and landing before querying the next reachable region.
        var body=client.player.getBoundingBox();
        var support=new net.minecraft.world.phys.AABB(body.minX,body.minY-.01,body.minZ,body.maxX,body.minY,body.maxZ);
        return !client.level.noCollision(client.player,support);
    }
    @Override public void tick(){requireWorld();retryRoutes=null;travelEstimates.clear();
        if(client.player.getAbilities().flying)throw new IllegalStateException("construction_flight_detected");
        client.player.getAbilities().mayfly=false;
        if(!client.player.onGround())airborneTicks++;
        var position=client.player.position();travelled+=position.distanceTo(previousPosition);previousPosition=position;}
    @Override public com.google.gson.JsonObject metrics(){var value=new com.google.gson.JsonObject();value.addProperty("distanceTravelled",travelled);value.addProperty("initiallyFlying",initiallyFlying);value.addProperty("airborneTicks",airborneTicks);value.addProperty("flying",owner.getAbilities().flying);value.addProperty("grounded",owner.onGround());value.add("repairSearch",repairDiagnostic.deepCopy());return value;}
    private static void setRouteEdits(boolean breaking,boolean placing){var args=new com.google.gson.JsonObject();args.addProperty("allowBreak",breaking);args.addProperty("allowPlace",placing);PathfindSettings.apply(args);}
    @Override public void close(){if(client.player==owner){owner.getAbilities().mayfly=previousAllowFlying;owner.onUpdateAbilities();}setRouteEdits(previousBreak,previousPlace);}
}
