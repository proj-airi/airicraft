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
        if(!view.getBlockState(target).isAir() || !client.level.hasChunkAt(target))return false;
        for(var direction:Direction.values()){
            var support=target.relative(direction);var state=view.getBlockState(support);
            if(!state.isCollisionShapeFullBlock(view,support))continue;
            var face=direction.getOpposite();var point=Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getUnitVec3i()).scale(.5));
            if(visible(client,view,eye,support,point,face))return true;
        }
        return false;
    }
    record Plan(List<AccessRepairSearch.Edit> edits,ConstructionEscape.Position destination) {
        static Plan empty(){return new Plan(List.of(),null);}
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
            return !c.air() && client.level.getBlockState(p).isAir() && state.isCollisionShapeFullBlock(client.level,p) && state.equals(state.getBlock().defaultBlockState());
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
                    if(changes.containsKey(p) || (!desired.getOrDefault(p,"minecraft:air").equals("minecraft:air"))
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
                    var from=new Vec3(top.x()+.5,top.y()+client.player.getEyeHeight(),top.z()+.5);
                    if(targets.stream().anyMatch(c->placeable(client,geometry,from,new BlockPos(c.x(),c.y(),c.z())))
                        && MinecraftConstructionEscape.explore(client,bounds,changes,top,false).escaped())return top;
                }
                return null;
            }
            public boolean goal(List<AccessRepairSearch.Edit> path){return workPose(path)!=null;}
            public double estimate(List<AccessRepairSearch.Edit> path){return 0;}
            public Object stateKey(List<AccessRepairSearch.Edit> path){return Map.copyOf(overlay(path));}
        };
        var result=AccessRepairSearch.find(model,new AccessRepairSearch.Limits(2,16,24));
        return result.found()?new Plan(result.edits(),model.workPose(result.edits())):Plan.empty();
    }
    static List<AccessRepairSearch.Edit> cleanup(Minecraft client,Map<ConstructionEscape.Position,ConstructionRepairLedger.Debt> debts,ConstructionEscape.Bounds bounds){
        var reachable=MinecraftConstructionEscape.explore(client,region(bounds),Map.of(),null,true).reached();
        for(var entry:debts.entrySet()){
            if(!entry.getValue().requiredState().equals("minecraft:air"))continue;
            var p=block(entry.getKey());var state=client.level.getBlockState(p);if(state.isAir() || !Blueprint.stateText(state).equals(entry.getValue().intermediateState()))continue;
            var poses=reachable.stream().sorted(Comparator.comparingDouble(s->client.player.distanceToSqr(s.x()+.5,s.y(),s.z()+.5))).toList();
            for(var pose:poses){
                var eye=new Vec3(pose.x()+.5,pose.y()+client.player.getEyeHeight(),pose.z()+.5);
                if(eye.distanceToSqr(Vec3.atCenterOf(p))>20.25)continue;
                var hit=client.level.clip(new ClipContext(eye,Vec3.atCenterOf(p),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,client.player));
                if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(p))continue;
                if(!MinecraftConstructionEscape.explore(client,bounds,Map.of(p,Blocks.AIR.defaultBlockState()),pose,false).escaped())continue;
                return List.of(new AccessRepairSearch.Edit(entry.getKey(),Blueprint.stateText(state),"minecraft:air",1,pose));
            }
        }
        return List.of();
    }
}
