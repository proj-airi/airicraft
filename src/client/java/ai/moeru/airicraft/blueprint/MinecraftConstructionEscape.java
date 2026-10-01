package ai.moeru.airicraft.blueprint;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Queries live collision boxes, with a conservative solid overlay for the proposed placement. */
public final class MinecraftConstructionEscape {
    private MinecraftConstructionEscape() { }
    private static AABB body(ConstructionEscape.Position p,int y) {
        return new AABB(p.x()+.2,y+.001,p.z()+.2,p.x()+.8,y+1.8,p.z()+.8);
    }
    static List<AABB> movementSweeps(ConstructionEscape.Position a,ConstructionEscape.Position b) {
        int high=Math.max(a.y(),b.y());
        return List.of(body(a,high).minmax(body(b,high)), body(b,high).minmax(body(b,b.y())));
    }
    static boolean passable(AABB sweep,List<AABB> current,List<AABB> toggled,boolean canToggle) {
        return current.stream().noneMatch(sweep::intersects) || (canToggle && toggled.stream().noneMatch(sweep::intersects));
    }
    public static boolean permits(Minecraft client,BlockPos target,ConstructionEscape.Bounds bounds,boolean doubleHeight) {
        var overlay=new HashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
        overlay.put(target,net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        if(doubleHeight)overlay.put(target.above(),net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        return canEscape(client,bounds,overlay);
    }
    public static boolean canEscape(Minecraft client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay) {
        return explore(client,bounds,overlay).escaped();
    }
    public static ConstructionEscape.Reachability explore(Minecraft client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay) {
        return explore(client,bounds,overlay,null,false);
    }
    public static ConstructionEscape.Reachability explore(Minecraft client,ConstructionEscape.Bounds bounds,Map<BlockPos,net.minecraft.world.level.block.state.BlockState> overlay,ConstructionEscape.Position start,boolean complete) {
        if(client.level==null || client.player==null)return new ConstructionEscape.Reachability(false,Set.of());
        var world=client.level;
        var cache=new HashMap<BlockPos,List<AABB>>();
        class Geometry implements ConstructionEscape.Geometry {
            List<AABB> shapes(BlockPos pos) {
                return cache.computeIfAbsent(pos.immutable(),p->{
                    if(!world.hasChunkAt(p) || !world.getFluidState(p).isEmpty()
                       )return List.of(new AABB(p));
                    return overlay.getOrDefault(p,world.getBlockState(p)).getCollisionShape(world,p).toAabbs().stream().map(b->b.move(p)).toList();
                });
            }
            boolean clear(AABB box) {
                for(int x=(int)Math.floor(box.minX);x<=Math.floor(box.maxX);x++)
                    for(int y=(int)Math.floor(box.minY);y<=Math.floor(box.maxY);y++)
                        for(int z=(int)Math.floor(box.minZ);z<=Math.floor(box.maxZ);z++) {
                            var pos=new BlockPos(x,y,z);var current=shapes(pos);
                            if(current.stream().noneMatch(box::intersects))continue;
                            var state=overlay.getOrDefault(pos,world.getBlockState(pos));
                            boolean toggle=world.hasChunkAt(pos) && world.getFluidState(pos).isEmpty()
                                && state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock door && door.type().canOpenByHand();
                            var alternative=toggle?state.cycle(net.minecraft.world.level.block.DoorBlock.OPEN).getCollisionShape(world,pos).toAabbs().stream().map(b->b.move(pos)).toList():List.<AABB>of();
                            if(!passable(box,current,alternative,toggle))return false;
                        }
                return true;
            }
            public boolean standable(ConstructionEscape.Position p) {
                if(!clear(body(p,p.y())))return false;
                for(var support:shapes(new BlockPos(p.x(),p.y()-1,p.z())))
                    if(Math.abs(support.maxY-p.y())<.001 && support.maxX>p.x()+.2 && support.minX<p.x()+.8
                        && support.maxZ>p.z()+.2 && support.minZ<p.z()+.8)return true;
                return false;
            }
            public boolean traversable(ConstructionEscape.Position a,ConstructionEscape.Position b) {
                // Horizontal clearance at the higher elevation plus the complete descent shaft.
                // Checking only the raised sweep could incorrectly pass through an intermediate ledge.
                return movementSweeps(a,b).stream().allMatch(this::clear);
            }
        }
        var feet=client.player.blockPosition();
        var initial=start!=null?start:new ConstructionEscape.Position(feet.getX(),feet.getY(),feet.getZ());
        return complete?ConstructionEscape.component(initial,bounds,new Geometry()):ConstructionEscape.explore(initial,bounds,new Geometry());
    }
}
