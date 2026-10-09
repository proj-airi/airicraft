package ai.moeru.airicraft.blueprint;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.*;
import java.util.*;

/** Small work-platform search. Every proposed edit is reachable from the current interaction pose. */
final class MinecraftScaffoldPlanner {
    private MinecraftScaffoldPlanner() { }
    static BlockPos block(ConstructionEscape.Position p){return new BlockPos(p.x(),p.y(),p.z());}
    static ConstructionEscape.Position position(BlockPos p){return new ConstructionEscape.Position(p.getX(),p.getY(),p.getZ());}
    static ConstructionEscape.Bounds region(ConstructionEscape.Bounds b){return new ConstructionEscape.Bounds(b.minX()-4,b.minY()-2,b.minZ()-4,b.maxX()+4,b.maxY()+3,b.maxZ()+4);}
    static BlockGetter view(Minecraft client,Map<BlockPos,BlockState> overlay){
        return new BlockGetter(){
            public BlockState getBlockState(BlockPos p){return overlay.getOrDefault(p,client.level.getBlockState(p));}
            public net.minecraft.world.level.material.FluidState getFluidState(BlockPos p){return getBlockState(p).getFluidState();}
            public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos p){return null;}
            public int getHeight(){return client.level.getHeight();}
            public int getMinY(){return client.level.getMinY();}
        };
    }
    static boolean visible(Minecraft client,BlockGetter view,Vec3 eye,BlockPos support,Vec3 point,Direction face){
        if(eye.distanceToSqr(point)>20.25)return false;
        var inward=point.subtract(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(.001));
        var hit=view.clip(new ClipContext(eye,inward,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,client.player));
        return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(support) && hit.getDirection()==face;
    }
    static boolean placeable(Minecraft client,BlockGetter view,Vec3 eye,BlockPos target){
        return placeable(client,view,eye,target,null);
    }
    static boolean compatibleNeighbours(BlockState desired,BlockGetter view,BlockPos target){
        if(desired!=null && desired.getBlock() instanceof FenceBlock fence){
            for(var direction:Direction.Plane.HORIZONTAL){
                var pos=target.relative(direction);var neighbor=view.getBlockState(pos);
                boolean connected=fence.connectsTo(neighbor,neighbor.isFaceSturdy(view,pos,direction.getOpposite()),direction);
                var property=switch(direction){case NORTH->FenceBlock.NORTH;case SOUTH->FenceBlock.SOUTH;case EAST->FenceBlock.EAST;case WEST->FenceBlock.WEST;default->throw new IllegalStateException();};
                if(desired.getValue(property)!=connected)return false;
            }
        }
        return true;
    }
    static boolean compatibleApproach(BlockState desired,BlockPos target,Direction face,Vec3 eye,Vec3 point){
        if(desired!=null && desired.getBlock() instanceof SlabBlock){
            var actual=face==Direction.DOWN || face!=Direction.UP && point.y-target.getY()>.5
                ?net.minecraft.world.level.block.state.properties.SlabType.TOP:net.minecraft.world.level.block.state.properties.SlabType.BOTTOM;
            if(desired.getValue(SlabBlock.TYPE)!=actual)return false;
        }
        if(desired!=null && desired.getBlock() instanceof StairBlock){
            var look=point.subtract(eye);
            var facing=Direction.fromYRot((float)Math.toDegrees(Math.atan2(-look.x,look.z)));
            var half=face==Direction.DOWN || face!=Direction.UP && point.y-target.getY()>.5
                ?net.minecraft.world.level.block.state.properties.Half.TOP:net.minecraft.world.level.block.state.properties.Half.BOTTOM;
            // Vanilla StairBlock uses horizontal look direction and clicked face/height.
            // This is a necessary approach check, not a replacement for the executor's
            // full state prediction (which also checks shape and waterlogging).
            return desired.getValue(StairBlock.FACING)==facing && desired.getValue(StairBlock.HALF)==half;
        }
        return true;
    }
    static Optional<ConstructionEscape.Position> reachableBreakStance(Collection<ConstructionEscape.Position> reachable,
        BlockPos target,Vec3 currentFeet,double eyeHeight,java.util.function.Predicate<ConstructionEscape.Position> usable){
        return reachable.stream().filter(p->!block(p).below().equals(target))
            .filter(p->breakStanceWithinReach(new Vec3(p.x()+.5,p.y()+eyeHeight,p.z()+.5),target))
            .sorted(Comparator.comparingDouble(p->currentFeet.distanceToSqr(p.x()+.5,p.y(),p.z()+.5)))
            .filter(usable).findFirst();
    }
    static boolean breakStanceWithinReach(Vec3 nominalEye,BlockPos target){
        // Navigation reaches a cell, not its exact centre. Reserve the full
        // half-cell offset in each horizontal axis before promising break access.
        double dx=Math.abs(nominalEye.x-target.getX()-.5)+.5;
        double dz=Math.abs(nominalEye.z-target.getZ()-.5)+.5;
        double dy=nominalEye.y-target.getY()-.5;
        return dx*dx+dy*dy+dz*dz<=20.25;
    }
    static boolean breakWithinReach(Vec3 eye,BlockPos target){
        // BlockBreakTaskExecutor rejects by centre distance before aiming at a face.
        return eye.distanceToSqr(Vec3.atCenterOf(target))<=20.25;
    }
    static boolean breakVisible(Minecraft client,BlockGetter geometry,Vec3 eye,BlockPos target){
        if(!breakWithinReach(eye,target))return false;
        return ai.moeru.airicraft.agent.control.CameraController.blockAim(target,geometry.getBlockState(target).getShape(geometry,target),eye,point->{
            if(eye.distanceToSqr(point)>20.25)return false;
            var hit=geometry.clip(new ClipContext(eye,point,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,client.player));
            return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(target);
        }).isPresent();
    }
    static boolean replaceableTarget(BlockState state){
        return state.canBeReplaced() && state.getFluidState().isEmpty();
    }
    static boolean placeable(Minecraft client,BlockGetter view,Vec3 eye,BlockPos target,BlockState desired){
        if(!replaceableTarget(view.getBlockState(target)) || !client.level.hasChunkAt(target) || !compatibleNeighbours(desired,view,target))return false;
        for(var direction:Direction.values()){
            var support=target.relative(direction);var state=view.getBlockState(support);
            if(state.isAir() || state.canBeReplaced())continue;
            var face=direction.getOpposite();
            if(ai.moeru.airicraft.agent.tasks.PlacementSupportPoints.findFirst(support,face,state.getShape(view,support),
                point->compatibleApproach(desired,target,face,eye,point) && visible(client,view,eye,support,point,face)).isPresent())return true;
        }
        return false;
    }
    record Plan(List<AccessRepairSearch.Edit> edits,ConstructionEscape.Position destination,String diagnostic) {
        Plan(List<AccessRepairSearch.Edit> edits,ConstructionEscape.Position destination){this(edits,destination,"");}
        static Plan empty(){return new Plan(List.of(),null);}
    }
    static boolean accessTarget(BlockState state){
        // Shape and orientation restrict the eventual click, not eligibility for access.
        // Paired upper halves are produced by placing the lower half.
        var half=net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF;
        return !state.isAir() && !(state.hasProperty(half)
            && state.getValue(half)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
    }
    static Plan plan(Minecraft client,List<BlueprintConstructionProgram.Cell> cells,ConstructionEscape.Bounds bounds){
        var inventory=client.player.getInventory();String material=null;int available=0;
        for(var item:List.of("minecraft:dirt","minecraft:cobblestone","minecraft:oak_planks")){
            int count=0;for(int slot=0;slot<inventory.getContainerSize();slot++){
                var stack=inventory.getItem(slot);if(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item))count+=stack.getCount();
            }
            if(count>0){material=item;available=client.player.isCreative()?Integer.MAX_VALUE:count;break;}
        }
        if(material==null)return Plan.empty();
        final String scaffold=material;final int stock=available;
        var desired=new HashMap<BlockPos,String>();for(var c:cells)desired.put(new BlockPos(c.x(),c.y(),c.z()),c.state());
        var targets=cells.stream().filter(c->{var p=new BlockPos(c.x(),c.y(),c.z());var state=Blueprint.parseState(c.state());
            return accessTarget(state) && replaceableTarget(client.level.getBlockState(p));
        }).sorted(Comparator.comparingDouble(c->client.player.distanceToSqr(c.x()+.5,c.y()+.5,c.z()+.5))).limit(24).toList();
        if(targets.isEmpty())return Plan.empty();
        var initial=MinecraftConstructionEscape.explore(client,region(bounds),Map.of(),null,true).reached();
        var eye=client.player.getEyePosition();var feet=client.player.blockPosition();
        var model=new AccessRepairSearch.Model(){
            Map<BlockPos,BlockState> overlay(List<AccessRepairSearch.Edit> path){var map=new HashMap<BlockPos,BlockState>();for(var e:path)map.put(block(e.position()),Blueprint.parseState(e.after()));return map;}
            public List<AccessRepairSearch.Edit> executableEdits(List<AccessRepairSearch.Edit> path){
                if(path.size()>=stock)return List.of();
                var changes=overlay(path);var geometry=view(client,changes);var edits=new ArrayList<AccessRepairSearch.Edit>();
                for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=0;dy<=1;dy++){
                    var p=feet.offset(dx,dy,dz);
                    if(changes.containsKey(p) || !geometry.getBlockState(p).isAir() || (!desired.getOrDefault(p,"minecraft:air").equals("minecraft:air"))
                        || new AABB(p).intersects(client.player.getBoundingBox()) || !placeable(client,geometry,eye,p))continue;
                    edits.add(new AccessRepairSearch.Edit(position(p),"minecraft:air",scaffold,2));
                }
                edits.sort(Comparator.comparingDouble(e->targets.stream().mapToDouble(c->block(e.position()).distSqr(new BlockPos(c.x(),c.y(),c.z()))).min().orElse(0)));
                return edits.stream().limit(16).toList();
            }
            ConstructionEscape.Position workPose(List<AccessRepairSearch.Edit> path){
                if(path.isEmpty())return null;
                var changes=overlay(path);var geometry=view(client,changes);
                var reachable=MinecraftConstructionEscape.explore(client,region(bounds),changes,null,true).reached();
                for(var edit:path){
                    var top=position(block(edit.position()).above());if(initial.contains(top) || !reachable.contains(top))continue;
                    var from=new Vec3(top.x()+.5,top.y()+client.player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),top.z()+.5);
                    if(targets.stream().anyMatch(c->placeable(client,geometry,from,new BlockPos(c.x(),c.y(),c.z()),Blueprint.parseState(c.state())))
                        && MinecraftConstructionEscape.explore(client,bounds,changes,top,false).escaped())return top;
                }
                return null;
            }
            public boolean goal(List<AccessRepairSearch.Edit> path){return workPose(path)!=null;}
            public double estimate(List<AccessRepairSearch.Edit> path){return 0;}
            public Object stateKey(List<AccessRepairSearch.Edit> path){return Map.copyOf(overlay(path));}
        };
        var result=AccessRepairSearch.find(model,new AccessRepairSearch.Limits(2,16,24));
        return result.found()?new Plan(result.edits(),model.workPose(result.edits())):towerPlan(client,targets,desired,bounds,scaffold,stock,initial);
    }

    /** Greedy, bounded support closure used only to rank work poses, never to authorize effects. */
    static <T> int workPatchSize(List<T> targets,java.util.function.Predicate<T> placeable,
        java.util.function.Consumer<T> place){
        var remaining=new ArrayList<>(targets);int completed=0;
        boolean progress;
        do {
            progress=false;
            for(var it=remaining.iterator();it.hasNext();){
                var target=it.next();if(!placeable.test(target))continue;
                place.accept(target);it.remove();completed++;progress=true;
            }
        } while(progress && !remaining.isEmpty());
        return completed;
    }
    static double towerUtility(int work,int height,double approachDistance){
        // Each temporary block must both be placed and removed. Retain locality
        // between comparable patches rather than crossing the site for a tie.
        return work/(1.0+2.0*height+.25*approachDistance);
    }
    private static int workPatch(Minecraft client,List<BlueprintConstructionProgram.Cell> targets,
        Map<BlockPos,BlockState> tower,Vec3 eye){
        var changes=new HashMap<>(tower);var geometry=view(client,changes);
        double feet=eye.y-client.player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING);
        var body=new AABB(eye.x-.3,feet,eye.z-.3,eye.x+.3,feet+1.8,eye.z+.3);
        return workPatchSize(targets,c->{
            var p=new BlockPos(c.x(),c.y(),c.z());
            return !body.intersects(new AABB(p)) && placeable(client,geometry,eye,p,Blueprint.parseState(c.state()));
        },c->{
            var p=new BlockPos(c.x(),c.y(),c.z());var state=Blueprint.parseState(c.state());
            // Predict only ordinary full cubes as new supports. Paired blocks,
            // stairs and other stateful shapes still count as individual work.
            if(state.getBlock().getClass()==Block.class && state.isCollisionShapeFullBlock(geometry,p))changes.put(p,state);
        });
    }

    static List<BlockPos> extensionSupports(List<BlockPos> targets,BlockPos stance){
        return targets.stream().flatMap(target->java.util.Arrays.stream(Direction.values()).map(target::relative))
            .distinct().filter(p->p.distSqr(stance)<=16)
            .sorted(Comparator.comparingDouble((BlockPos p)->p.distSqr(stance))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ))
            .limit(16).toList();
    }
    static List<ConstructionEscape.Position> towerOrigins(List<ConstructionEscape.Position> viable,
        ConstructionEscape.Bounds bounds,java.util.function.ToDoubleFunction<ConstructionEscape.Position> score){
        var sorted=viable.stream().sorted(Comparator.comparingDouble(score)
            .thenComparingInt(ConstructionEscape.Position::x)
            .thenComparingInt(ConstructionEscape.Position::y)
            .thenComparingInt(ConstructionEscape.Position::z)).toList();
        // Near targets may all be underneath an overhang. Preserve exterior tower
        // access without increasing the bounded number of expensive height searches.
        var selected=new LinkedHashSet<ConstructionEscape.Position>();
        sorted.stream().filter(bounds::outside).limit(12).forEach(selected::add);
        for(var origin:sorted){if(selected.size()>=24)break;selected.add(origin);}
        return List.copyOf(selected);
    }
    private static Plan towerPlan(Minecraft client,List<BlueprintConstructionProgram.Cell> targets,
        Map<BlockPos,String> desired,ConstructionEscape.Bounds bounds,String material,int stock,
        Set<ConstructionEscape.Position> reachable) {
        return towerPlan(client,targets,desired,bounds,material,stock,reachable,
            null);
    }
    private static Plan towerPlan(Minecraft client,List<BlueprintConstructionProgram.Cell> targets,
        Map<BlockPos,String> desired,ConstructionEscape.Bounds bounds,String material,int stock,
        Set<ConstructionEscape.Position> reachable,java.util.function.BiPredicate<BlockGetter,Vec3> usable) {
        var viableOrigins=reachable.stream().filter(origin->{
            var p=block(origin);
            return desired.getOrDefault(p,"minecraft:air").equals("minecraft:air") && client.level.getBlockState(p).isAir()
                && client.level.getBlockState(p.below()).isCollisionShapeFullBlock(client.level,p.below())
                && client.level.noCollision(client.player,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+3.05,p.getZ()+.8));
        }).toList();
        var origins=towerOrigins(viableOrigins,bounds,p->targets.stream()
            .mapToDouble(c->block(p).distSqr(new BlockPos(c.x(),c.y(),c.z()))).min().orElse(0)
            + client.player.distanceToSqr(p.x()+.5,p.y(),p.z()+.5)*.1);
        record Candidate(Plan plan,Map<BlockPos,BlockState> changes,double utility) { }
        var candidates=new ArrayList<Candidate>();
        int visibilityMatches=0,escapeRejected=0;
        for(var origin:origins){
            var base=block(origin);var support=base.below();
            if(!client.level.getBlockState(support).isCollisionShapeFullBlock(client.level,support))continue;
            var changes=new HashMap<BlockPos,BlockState>();var edits=new ArrayList<AccessRepairSearch.Edit>();
            // A short horizon cannot discover access to an isolated high roof: none
            // of its intermediate heights reaches final work. Search up to that work,
            // bounded by stock and a 32-block macro; execution still verifies every step.
            int targetTop=targets.stream().mapToInt(BlueprintConstructionProgram.Cell::y).max().orElse(base.getY());
            int towerLimit=Math.min(Math.min(stock,32),Math.max(0,targetTop-base.getY()+1));
            for(int height=1;height<=towerLimit;height++){
                var p=base.above(height-1);
                if(!desired.getOrDefault(p,"minecraft:air").equals("minecraft:air") || !client.level.getBlockState(p).isAir()
                    || !client.level.noCollision(client.player,new AABB(p.getX()+.2,p.getY(),p.getZ()+.2,p.getX()+.8,p.getY()+3.05,p.getZ()+.8)))break;
                changes.put(p,Blueprint.parseState(material));
                edits.add(new AccessRepairSearch.Edit(position(p),"minecraft:air",material,3,position(p),true));
                var top=position(p.above());var geometry=view(client,changes);
                var eye=new Vec3(top.x()+.5,top.y()+client.player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),top.z()+.5);
                if(usable!=null){
                    // Cleanup seeks one particular debt; keep its short access plan.
                    if(!usable.test(geometry,eye))continue;
                    visibilityMatches++;
                    if(!MinecraftConstructionEscape.explore(client,bounds,changes,top,false).escaped()){escapeRejected++;continue;}
                    return new Plan(List.copyOf(edits),top,"cleanup tower from "+origin);
                }
                int work=workPatch(client,targets,changes,eye);
                if(work==0 && height<stock){
                    // Diagonal staircase cells have no shared face. Extend the tower
                    // by one legal support before requiring immediately placeable work.
                    for(var extension:extensionSupports(targets.stream().map(c->new BlockPos(c.x(),c.y(),c.z())).toList(),block(top))){
                        if(!desired.getOrDefault(extension,"minecraft:air").equals("minecraft:air")
                            || !geometry.getBlockState(extension).isAir()
                            || new AABB(extension).intersects(new AABB(top.x()+.2,top.y(),top.z()+.2,top.x()+.8,top.y()+1.8,top.z()+.8))
                            || !placeable(client,geometry,eye,extension))continue;
                        var extended=new HashMap<>(changes);extended.put(extension,Blueprint.parseState(material));
                        int extendedWork=workPatch(client,targets,extended,eye);if(extendedWork==0)continue;
                        var extendedEdits=new ArrayList<>(edits);
                        extendedEdits.add(new AccessRepairSearch.Edit(position(extension),"minecraft:air",material,2,top));
                        double approach=Math.sqrt(client.player.distanceToSqr(origin.x()+.5,origin.y(),origin.z()+.5));
                        candidates.add(new Candidate(new Plan(List.copyOf(extendedEdits),top,
                            "tower with extension from "+origin+" patch="+extendedWork),Map.copyOf(extended),towerUtility(extendedWork,height+1,approach)));
                    }
                }
                if(work==0)continue;
                visibilityMatches++;
                double approach=Math.sqrt(client.player.distanceToSqr(origin.x()+.5,origin.y(),origin.z()+.5));
                double utility=towerUtility(work,height,approach);
                candidates.add(new Candidate(new Plan(List.copyOf(edits),top,
                    "tower from "+origin+" patch="+work+" height="+height+" utility="+utility),Map.copyOf(changes),utility));

            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::utility).reversed());
        for(var candidate:candidates){
            if(MinecraftConstructionEscape.explore(client,bounds,candidate.changes(),candidate.plan().destination(),false).escaped())return candidate.plan();
            escapeRejected++;
        }
        return new Plan(List.of(),null,"tower origins="+reachable.size()+" viable="+viableOrigins.size()
            +" sampled="+origins.size()+" visibilityMatches="+visibilityMatches+" escapeRejected="+escapeRejected);
    }
    static boolean ownedScaffold(ConstructionRepairLedger.Debt debt,BlockState observed){
        if(!debt.requiredState().equals("minecraft:air") || observed.isAir())return false;
        if(Blueprint.stateText(observed).equals(debt.intermediateState()))return true;
        // Grass spreads naturally onto a placed dirt scaffold. Ownership comes
        // from the persisted pre-placement debt, never from finding grass nearby.
        return "minecraft:dirt".equals(debt.intermediateState()) && observed.is(Blocks.GRASS_BLOCK);
    }
    static AccessRepairSearch.Edit nearestCleanup(Map<ConstructionEscape.Position,Integer> routes,
            Set<ConstructionEscape.Position> debts,
            java.util.function.BiFunction<ConstructionEscape.Position,ConstructionEscape.Position,AccessRepairSearch.Edit> removal){
        var tops=debts.stream().filter(p->debts.stream().noneMatch(q->q.x()==p.x() && q.z()==p.z() && q.y()>p.y()))
            .sorted(Comparator.comparingInt(ConstructionEscape.Position::y).reversed()
                .thenComparingInt(ConstructionEscape.Position::x).thenComparingInt(ConstructionEscape.Position::z)).toList();
        var poses=routes.entrySet().stream().sorted(Map.Entry.<ConstructionEscape.Position,Integer>comparingByValue()
            .thenComparingInt(e->e.getKey().x()).thenComparingInt(e->e.getKey().y()).thenComparingInt(e->e.getKey().z())).toList();
        for(var pose:poses)for(var p:tops){
            var edit=removal.apply(p,pose.getKey());if(edit!=null)return edit;
        }
        return null;
    }
    static List<AccessRepairSearch.Edit> cleanup(Minecraft client,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts,ConstructionEscape.Bounds bounds,List<BlueprintConstructionProgram.Cell> cells,Set<ConstructionEscape.Position> accessAttempted){
        var reachability=MinecraftConstructionEscape.explore(client,region(bounds),Map.of(),null,true);
        var reachable=reachability.reached();
        var owned=new HashSet<ConstructionEscape.Position>();
        debts.forEach((p,debt)->{if(ownedScaffold(debt,client.level.getBlockState(block(p))))owned.add(p);});
        // Choose a short route to a safe removal, rather than sweeping heights
        // globally across distant towers. Never undercut another owned debt above.
        var removal=nearestCleanup(reachability.steps(),owned,(position,pose)->{
            var p=block(position);var state=client.level.getBlockState(p);
            var eye=new Vec3(pose.x()+.5,pose.y()+client.player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),pose.z()+.5);
            if(!breakStanceWithinReach(eye,p) || !breakVisible(client,client.level,eye,p))return null;
            var afterPose=pose;
            // An owned underfoot scaffold may be dismantled by a one-block drop onto
            // the next intact full support. Never apply this to arbitrary terrain.
            if(pose.equals(position(p.above()))){
                if(!client.level.getBlockState(p.below()).isCollisionShapeFullBlock(client.level,p.below()))return null;
                afterPose=position(p);
            }
            if(!MinecraftConstructionEscape.explore(client,bounds,Map.of(p,Blocks.AIR.defaultBlockState()),afterPose,false).escaped())return null;
            return new AccessRepairSearch.Edit(position,Blueprint.stateText(state),"minecraft:air",1,pose);
        });
        if(removal!=null)return List.of(removal);
        // Cleanup can itself need access. Queue removal of the original debt before
        // teardown so newly placed access cannot be selected for cleanup prematurely.
        var desired=new HashMap<BlockPos,String>();for(var c:cells)desired.put(new BlockPos(c.x(),c.y(),c.z()),c.state());
        String material=null;int stock=0;
        for(var item:List.of("minecraft:dirt","minecraft:cobblestone","minecraft:oak_planks")){
            int count=0;var inventory=client.player.getInventory();
            for(int slot=0;slot<inventory.getContainerSize();slot++){
                var stack=inventory.getItem(slot);
                if(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item))count+=stack.getCount();
            }
            if(count>0){material=item;stock=client.player.isCreative()?32:count;break;}
        }
        if(material==null)return List.of();
        for(var entry:debts.entrySet()){
            if(accessAttempted.contains(entry.getKey()))continue;
            var p=block(entry.getKey());var state=client.level.getBlockState(p);
            if(!ownedScaffold(entry.getValue(),state))continue;
            var target=new BlueprintConstructionProgram.Cell(p.getX(),p.getY(),p.getZ(),"minecraft:air","minecraft:air","temporary_access");
            // Start on an exterior-connected stance that remains safe after the old
            // scaffold is gone. The straight new tower provides its own return route.
            var origins=new HashSet<ConstructionEscape.Position>();
            for(var origin:reachable.stream().filter(o->o.x()<bounds.minX() || o.x()>bounds.maxX() || o.z()<bounds.minZ() || o.z()>bounds.maxZ())
                .sorted(Comparator.comparingDouble(o->block(o).distSqr(p))).limit(64).toList()){
                if(block(origin).below().equals(p))continue;
                if(MinecraftConstructionEscape.explore(client,bounds,Map.of(p,Blocks.AIR.defaultBlockState()),origin,false).escaped())origins.add(origin);
            }
            var plan=towerPlan(client,List.of(target),desired,bounds,material,stock,origins,
                (geometry,eye)->breakStanceWithinReach(eye,p) && breakVisible(client,geometry,eye,p));
            if(!plan.edits().isEmpty()){
                accessAttempted.add(entry.getKey());
                return cleanupSequence(plan,entry.getKey(),Blueprint.stateText(state));
            }
        }
        return List.of();
    }
    static List<AccessRepairSearch.Edit> cleanupSequence(Plan tower,ConstructionEscape.Position target,String state){
        var edits=new ArrayList<>(tower.edits());
        edits.add(new AccessRepairSearch.Edit(target,state,"minecraft:air",1,tower.destination()));
        for(int i=tower.edits().size()-1;i>=0;i--){
            var placed=tower.edits().get(i);
            edits.add(new AccessRepairSearch.Edit(placed.position(),placed.after(),"minecraft:air",1,position(block(placed.position()).above())));
        }
        return List.copyOf(edits);
    }
}
